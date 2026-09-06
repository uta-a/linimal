package dev.utaa.linimal.patches.features.home

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.ApkArchitecture
import app.morphe.patcher.patch.PatchAvailability
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import dev.utaa.linimal.patches.shared.Constants
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.PatchStatus
import dev.utaa.linimal.patches.status.PatchStatusRecord
import dev.utaa.linimal.patches.status.patchStatusCollector
import dev.utaa.linimal.patches.status.unsafeFeatureStatus
import dev.utaa.linimal.patches.util.BOOLEAN
import dev.utaa.linimal.patches.util.INT
import dev.utaa.linimal.patches.util.STRING
import dev.utaa.linimal.patches.util.VOID
import dev.utaa.linimal.patches.util.composeShouldExecuteGate
import dev.utaa.linimal.patches.util.composeShouldExecuteSuppression
import dev.utaa.linimal.patches.util.resolveComposeRuntime

private const val HOME_TRENDING_HOOK =
    "Ldev/utaa/linimal/extension/features/HomeTrendingHooks;->shouldSuppress()Z"

/**
 * Home26 の話題枠 module の view data が `toString()` に必ず残す marker。
 *
 * <p>26.11.0 では `GcsHomeFeedMatomeSingleModuleController` の coroutine DebugMetadata を anchor に
 * していましたが、26.14.0 ではこの controller から suspend lambda が消え、DebugMetadata そのものが
 * 存在しなくなりました。module の view data は data class のまま残るため、`toString()` の marker を
 * anchor にします。隣に並ぶ carousel 版（`GcsHomeFeedMatomeCarousel`）は別 module なので入りません。</p>
 */
internal const val MATOME_VIEW_DATA_MARKER = "GcsHomeFeedMatomeSingle(item="

/** 話題枠の module renderer は 1 件だけです。解決できなければ一切注入しません。 */
internal const val HOME_TRENDING_TARGET_COUNT = 1

/** 話題 module の view data。`toString()` の marker だけを anchor にします。 */
private val matomeViewDataFingerprint = Fingerprint(strings = listOf(MATOME_VIEW_DATA_MARKER))

/**
 * 話題 module の restartable composable。難読化された class / method 名ではなく、
 * module renderer の引数の並びと composer lifecycle の呼出しで絞り込みます。
 */
private fun matomeRendererFingerprint(
    viewDataType: String,
    composer: String,
    composerImpl: String,
    shouldExecute: String,
    skipToGroupEnd: String,
) = Fingerprint(
    returnType = VOID,
    custom = { method, _ -> isMatomeModuleRendererSignature(method, viewDataType, composer) },
    // `endRestartGroup` は method 名を安定して導出できないため filter から外し、
    // 注入前の shape 判定側で「呼出しが 1 件だけあること」を確認します。
    filters = listOf(
        methodCall(
            definingClass = composerImpl,
            name = shouldExecute,
            parameters = listOf(INT, BOOLEAN),
            returnType = BOOLEAN,
            opcode = Opcode.INVOKE_VIRTUAL,
        ),
        methodCall(
            definingClass = composerImpl,
            name = skipToGroupEnd,
            parameters = emptyList(),
            returnType = VOID,
            opcode = Opcode.INVOKE_VIRTUAL,
        ),
    ),
)

/**
 * Home の話題枠を、LINE 自身の skip 経路へ倒して描かせません。
 *
 * <p>composable が本体を実行するかどうかを決める `Composer.shouldExecute` の結果だけを書き換えます。
 * 抑制時に通るのは、LINE が再 composition で本体を省くときと同じ `skipToGroupEnd` と
 * `endRestartGroup` の経路です。新しい control flow を作らないため、composer の group 整合は
 * 変わらず、Home が composed のまま設定を切り替えても経路は分岐しません。
 * 設定 OFF・未初期化・例外時は元の結果をそのまま使います。</p>
 */
val homeTrendingPatch = bytecodePatch(
    name = "ホームの話題枠",
    description = "ホームの話題・トレンド枠を、実行時設定で非表示にできるようにします。",
) {
    compatibleWith(Constants.LINE_COMPATIBILITY)
    availability { _, architecture ->
        if (architecture == ApkArchitecture.ARM64_V8A) {
            PatchAvailability.REQUIRED
        } else {
            PatchAvailability.UNAVAILABLE
        }
    }
    dependsOn(homeContentsRecommendationPatch)

    execute {
        val composeRuntime = resolveComposeRuntime()
        if (composeRuntime == null) {
            patchStatusCollector.record(
                homeTrendingUnappliedRecord(0, "HomeTrendingRuntimeAnchorNotResolved"),
            )
            return@execute
        }

        val viewDataTypes = matomeViewDataFingerprint.matchAllOrNull().orEmpty()
            .map { it.originalClassDef.type }
            .toSet()
        if (viewDataTypes.size != HOME_TRENDING_TARGET_COUNT) {
            patchStatusCollector.record(
                homeTrendingUnappliedRecord(viewDataTypes.size, "HomeMatomeModuleViewDataNotUnique"),
            )
            return@execute
        }

        val renderers = matomeRendererFingerprint(
            viewDataType = viewDataTypes.single(),
            composer = composeRuntime.composer,
            composerImpl = composeRuntime.composerImpl,
            shouldExecute = composeRuntime.shouldExecute,
            skipToGroupEnd = composeRuntime.skipToGroupEnd,
        ).matchAllOrNull().orEmpty()
        if (renderers.size != HOME_TRENDING_TARGET_COUNT) {
            patchStatusCollector.record(
                homeTrendingUnappliedRecord(renderers.size, "HomeMatomeModuleRendererNotUnique"),
            )
            return@execute
        }

        val method = renderers.single().method
        val gate = composeShouldExecuteGate(method, composeRuntime)
        if (gate == null) {
            // cardinality は揃っていても注入位置の shape が崩れている場合は、何も変更しません。
            patchStatusCollector.record(
                unsafeFeatureStatus(
                    patchId = PatchId.HOME_MATOME_SINGLE_MODULE,
                    expectedTargetCount = HOME_TRENDING_TARGET_COUNT,
                    actualTargetCount = renderers.size,
                    reason = "HomeMatomeModuleShapeMismatch",
                ),
            )
            return@execute
        }

        method.addInstructionsWithLabels(
            gate.branchIndex,
            composeShouldExecuteSuppression(gate, HOME_TRENDING_HOOK),
        )

        patchStatusCollector.record(
            patchId = PatchId.HOME_MATOME_SINGLE_MODULE,
            expectedTargetCount = HOME_TRENDING_TARGET_COUNT,
            actualTargetCount = HOME_TRENDING_TARGET_COUNT,
            reason = "HomeMatomeModuleSuppressionGuarded",
        )
    }
}

/**
 * 対象を見つけられなかった場合の記録。0 件は TARGET_NOT_FOUND、複数見つかった場合は
 * 意図した 1 件に絞り込めていないため ERROR として raw match count を残します。
 */
internal fun homeTrendingUnappliedRecord(resolvedCount: Int, reason: String) = PatchStatusRecord(
    patchId = PatchId.HOME_MATOME_SINGLE_MODULE,
    status = if (resolvedCount == 0) PatchStatus.TARGET_NOT_FOUND else PatchStatus.ERROR,
    expectedTargetCount = HOME_TRENDING_TARGET_COUNT,
    actualTargetCount = resolvedCount,
    reason = reason,
)

/**
 * module renderer の引数の並び。module state の型は版ごとに変わるため、その位置は型を問わず、
 * module id・view data・composer・changed flag の並びだけを検証します。
 */
internal fun isMatomeModuleRendererSignature(method: Method, viewDataType: String, composer: String): Boolean {
    val parameters = method.parameterTypes.map { it.toString() }
    return parameters.size == 5 &&
        parameters[0] == STRING &&
        parameters[1] == viewDataType &&
        parameters[2].startsWith("L") &&
        parameters[3] == composer &&
        parameters[4] == INT
}
