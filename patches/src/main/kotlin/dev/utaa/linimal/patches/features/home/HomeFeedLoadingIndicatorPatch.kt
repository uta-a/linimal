package dev.utaa.linimal.patches.features.home

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.ApkArchitecture
import app.morphe.patcher.patch.PatchAvailability
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import dev.utaa.linimal.patches.shared.Constants
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.PatchStatus
import dev.utaa.linimal.patches.status.PatchStatusRecord
import dev.utaa.linimal.patches.status.patchStatusCollector
import dev.utaa.linimal.patches.status.unsafeFeatureStatus
import dev.utaa.linimal.patches.util.BOOLEAN
import dev.utaa.linimal.patches.util.INT
import dev.utaa.linimal.patches.util.VOID
import dev.utaa.linimal.patches.util.composeShouldExecuteGate
import dev.utaa.linimal.patches.util.composeShouldExecuteSuppression
import dev.utaa.linimal.patches.util.debugMetadataSource
import dev.utaa.linimal.patches.util.resolveComposeRuntime
import dev.utaa.linimal.patches.util.resolveDebugMetadataType

private const val HOME_FEED_LOADING_INDICATOR_HOOK =
    "Ldev/utaa/linimal/extension/features/HomeFeedLoadingIndicatorHooks;->shouldSuppress()Z"

/**
 * ホームのフィードを描く GCS ページの UI を表す coroutine の DebugMetadata。R8 後もこの class 名は
 * 平文で残るため、難読化された package 名を patch へ書き込まずに済みます。
 */
private const val GCS_PAGE_UI_CLASS_PREFIX = "com.linecorp.line.gcs.page.ui."

/**
 * LINE Design System の spinner が持つ角度の data class。`toString()` に残るこの marker から
 * spinner の package を導きます。読み込み表示は Material3 の CircularProgressIndicator ではなく
 * こちらで描かれています。
 */
private const val LDS_SPINNER_ANGLES_MARKER = "LdsSpinnerAngles(arcStartAngleDegrees="

/** spinner composable の引数の数。`(size, Modifier, Boolean, Composer, $$changed, $$default)`。 */
private const val LDS_SPINNER_PARAMETER_COUNT = 6

/** spinner composable の引数のうち、Modifier が入る位置。 */
private const val LDS_SPINNER_MODIFIER_INDEX = 1

/** 読み込み表示の renderer は 1 件だけです。解決できなければ一切注入しません。 */
internal const val HOME_FEED_LOADING_INDICATOR_TARGET_COUNT = 1

private fun gcsPageUiMetadataFingerprint(debugMetadataType: String) = Fingerprint(
    custom = { _, classDef ->
        debugMetadataSource(classDef, debugMetadataType)?.className?.startsWith(GCS_PAGE_UI_CLASS_PREFIX) == true
    },
)

private val ldsSpinnerAnglesFingerprint = Fingerprint(strings = listOf(LDS_SPINNER_ANGLES_MARKER))

/**
 * LDS spinner 本体かどうか。`(size, Modifier, Boolean, Composer, int, int)` を返り値 void で取ります。
 *
 * <p>26.11.0 では Modifier の難読化型（`Ly3/j;`）を patch に持っていましたが、この型名は版ごとに
 * 変わります。位置と他の引数の型だけで spinner を 1 件に絞り、Modifier の型は絞り込めた
 * spinner の引数から取り出します。</p>
 */
internal fun isLdsSpinnerSignature(method: Method, composer: String): Boolean {
    val parameters = method.parameterTypes.map { it.toString() }
    return method.returnType == VOID &&
        parameters.size == LDS_SPINNER_PARAMETER_COUNT &&
        parameters[0].startsWith("L") &&
        parameters[LDS_SPINNER_MODIFIER_INDEX].startsWith("L") &&
        parameters[2] == BOOLEAN &&
        parameters[3] == composer &&
        parameters[4] == INT &&
        parameters[5] == INT
}

/**
 * ホームのフィードが読み込み中のときに出る円形の読み込み表示を抑制します。
 *
 * <h2>対象の特定</h2>
 * <p>実機で出ている読み込み表示は Material3 の CircularProgressIndicator ではなく、LINE 独自の
 * design system の spinner です。まず `LdsSpinnerAngles(arcStartAngleDegrees=` という
 * 非難読化の `toString()` marker から spinner の package を求め、その中から
 * `(size, Modifier, Boolean, Composer, int, int)` を取る composable を 1 件だけ選びます。</p>
 *
 * <p>次に、ホームのフィードを描く GCS ページの UI package を DebugMetadata の元 class 名
 * （`com.linecorp.line.gcs.page.ui.`）から求め、**その package から呼ばれていて**、かつ
 * `(Modifier, Composer, int)` を取り spinner を呼ぶ composable を 1 件だけ選びます。
 * spinner を呼ぶ同じ形の composable は APK 全体に 11 件ありますが、GCS ページから呼ばれるのは
 * この 1 件だけです。</p>
 *
 * <h2>抑制の方法</h2>
 * <p>LINE 自身の skip 経路（`shouldExecute` が false のときに通る `skipToGroupEnd` と
 * `endRestartGroup`）へ合流させるだけで、新しい制御フローを作りません。設定が OFF のときは
 * 元の値へ戻します。</p>
 */
val homeFeedLoadingIndicatorPatch = bytecodePatch(
    name = "ホームの読み込み表示",
    description = "ホームのフィードが読み込み中に出る円形の読み込み表示を、実行時設定で非表示にできるようにします。",
) {
    compatibleWith(Constants.LINE_COMPATIBILITY)
    availability { _, architecture ->
        if (architecture == ApkArchitecture.ARM64_V8A) {
            PatchAvailability.REQUIRED
        } else {
            PatchAvailability.UNAVAILABLE
        }
    }
    // 機能パッチは単一の直列チェーンを成し、この patch の後段に noOpProbePatch が続きます。
    dependsOn(homeRecentHistoryPatch)

    execute {
        val composeRuntime = resolveComposeRuntime()
        val debugMetadataType = resolveDebugMetadataType()
        if (composeRuntime == null || debugMetadataType == null) {
            patchStatusCollector.record(
                homeFeedLoadingIndicatorUnappliedRecord(0, "HomeFeedLoadingIndicatorRuntimeAnchorNotResolved"),
            )
            return@execute
        }

        val spinnerPackages = homeFeedLoadingIndicatorPackagePrefixes(
            ldsSpinnerAnglesFingerprint.matchAllOrNull().orEmpty().map { it.originalClassDef.type }.toSet(),
        )
        if (spinnerPackages.size != 1) {
            patchStatusCollector.record(
                homeFeedLoadingIndicatorUnappliedRecord(
                    spinnerPackages.size,
                    "HomeFeedLoadingIndicatorSpinnerPackageNotUnique",
                ),
            )
            return@execute
        }
        val spinnerPackage = spinnerPackages.single()

        val spinnerFingerprint = Fingerprint(
            returnType = VOID,
            custom = { method, classDef ->
                classDef.type.startsWith(spinnerPackage) && isLdsSpinnerSignature(method, composeRuntime.composer)
            },
        )
        val spinners = spinnerFingerprint.matchAllOrNull().orEmpty()
        if (spinners.size != 1) {
            patchStatusCollector.record(
                homeFeedLoadingIndicatorUnappliedRecord(
                    spinners.size,
                    "HomeFeedLoadingIndicatorSpinnerNotUnique",
                ),
            )
            return@execute
        }
        val spinner = spinners.single().originalMethod
        // Modifier の難読化型は spinner の引数から取り出します。
        val modifier = spinner.parameterTypes[LDS_SPINNER_MODIFIER_INDEX].toString()

        val gcsPagePackages = homeFeedLoadingIndicatorPackagePrefixes(
            gcsPageUiMetadataFingerprint(debugMetadataType).matchAllOrNull().orEmpty()
                .map { it.originalClassDef.type }
                .toSet(),
        )
        // GCS ページの UI は subpackage へ分かれるため、1 つに絞らず全体を対象にします。
        if (gcsPagePackages.isEmpty()) {
            patchStatusCollector.record(
                homeFeedLoadingIndicatorUnappliedRecord(0, "HomeFeedLoadingIndicatorGcsPagePackageNotFound"),
            )
            return@execute
        }

        val candidateFingerprint = Fingerprint(
            returnType = VOID,
            custom = { method, _ ->
                isLoadingIndicatorRendererSignature(method, modifier, composeRuntime.composer)
            },
            filters = listOf(
                methodCall(
                    definingClass = spinner.definingClass,
                    name = spinner.name,
                    parameters = spinner.parameterTypes.map { it.toString() },
                    returnType = VOID,
                ),
            ),
        )
        val candidates = candidateFingerprint.matchAllOrNull().orEmpty()
            .associateBy { match -> methodKey(match.originalMethod) }
        if (candidates.isEmpty()) {
            patchStatusCollector.record(
                homeFeedLoadingIndicatorUnappliedRecord(0, "HomeFeedLoadingIndicatorRendererNotFound"),
            )
            return@execute
        }

        // GCS ページの UI から呼ばれているものだけを残します。同じ形の composable は他画面にも
        // ありますが、ホームのフィードから到達するのはこの 1 件だけです。
        val gcsPageMethods = Fingerprint(
            custom = { _, classDef -> gcsPagePackages.any { classDef.type.startsWith(it) } },
        ).matchAllOrNull().orEmpty()
        val calledFromGcsPage = gcsPageMethods
            .flatMap { match -> calledMethodKeys(match.originalMethod) }
            .toSet()
        val renderers = candidates.filterKeys { it in calledFromGcsPage }.values.toList()
        if (renderers.size != HOME_FEED_LOADING_INDICATOR_TARGET_COUNT) {
            patchStatusCollector.record(
                homeFeedLoadingIndicatorUnappliedRecord(
                    renderers.size,
                    "HomeFeedLoadingIndicatorRendererNotUnique",
                ),
            )
            return@execute
        }

        val method = renderers.single().method
        val gate = composeShouldExecuteGate(method, composeRuntime)
        if (gate == null) {
            // cardinality は揃っていても注入位置の shape が崩れている場合は、何も変更しません。
            patchStatusCollector.record(
                unsafeFeatureStatus(
                    patchId = PatchId.HOME_FEED_LOADING_INDICATOR,
                    expectedTargetCount = HOME_FEED_LOADING_INDICATOR_TARGET_COUNT,
                    actualTargetCount = renderers.size,
                    reason = "HomeFeedLoadingIndicatorShapeMismatch",
                ),
            )
            return@execute
        }

        method.addInstructionsWithLabels(
            gate.branchIndex,
            composeShouldExecuteSuppression(gate, HOME_FEED_LOADING_INDICATOR_HOOK),
        )

        patchStatusCollector.record(
            patchId = PatchId.HOME_FEED_LOADING_INDICATOR,
            expectedTargetCount = HOME_FEED_LOADING_INDICATOR_TARGET_COUNT,
            actualTargetCount = HOME_FEED_LOADING_INDICATOR_TARGET_COUNT,
            reason = "HomeFeedLoadingIndicatorSuppressionGuarded",
        )
    }
}

/**
 * 対象を見つけられなかった場合の記録。0 件は TARGET_NOT_FOUND、複数見つかった場合は
 * 意図した 1 件に絞り込めていないため ERROR として raw match count を残します。
 */
internal fun homeFeedLoadingIndicatorUnappliedRecord(resolvedCount: Int, reason: String): PatchStatusRecord =
    PatchStatusRecord(
        patchId = PatchId.HOME_FEED_LOADING_INDICATOR,
        status = if (resolvedCount == 0) PatchStatus.TARGET_NOT_FOUND else PatchStatus.ERROR,
        expectedTargetCount = HOME_FEED_LOADING_INDICATOR_TARGET_COUNT,
        actualTargetCount = resolvedCount,
        reason = reason,
    )

/** DebugMetadata が指す class の package。source file が 1 つに収まらなければ何も注入しません。 */
internal fun homeFeedLoadingIndicatorPackagePrefixes(sourceTypes: Set<String>): Set<String> = sourceTypes
    .mapNotNull { type ->
        val separator = type.lastIndexOf('/')
        if (separator > 0) type.substring(0, separator + 1) else null
    }
    .toSet()

/**
 * renderer の引数の並び。読み込み表示の composable は view data を持たず、Modifier と Composer と
 * `$$changed` だけを取ります。
 */
internal fun isLoadingIndicatorRendererSignature(method: Method, modifier: String, composer: String): Boolean {
    val parameters = method.parameterTypes.map { it.toString() }
    return parameters == listOf(modifier, composer, INT)
}

/**
 * 呼び出し関係の突き合わせに使うキー。
 *
 * <p>定義クラスと名前だけでは、同じクラスに同名の overload があると別のメソッドを同一視します。
 * 引数の型と戻り値まで含めた descriptor を使い、対象を取り違えないようにします。</p>
 */
internal fun methodKey(definingClass: String, name: String, parameterTypes: List<String>, returnType: String): String =
    "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun methodKey(method: Method): String = methodKey(
    method.definingClass,
    method.name,
    method.parameterTypes.map { it.toString() },
    method.returnType,
)

/** メソッドが呼び出している method reference のキー一覧。 */
private fun calledMethodKeys(method: Method): List<String> =
    method.implementation?.instructions?.toList().orEmpty().mapNotNull { instruction ->
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        reference?.let {
            methodKey(it.definingClass, it.name, it.parameterTypes.map(CharSequence::toString), it.returnType)
        }
    }
