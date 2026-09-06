package dev.utaa.linimal.patches.features.home

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.ApkArchitecture
import app.morphe.patcher.patch.PatchAvailability
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import dev.utaa.linimal.patches.features.premium.premiumSettingsRowPatch
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

private const val HOME_FEATURED_COLLECTIONS_HOOK =
    "Ldev/utaa/linimal/extension/features/HomeFeaturedCollectionsHooks;->shouldSuppress()Z"

/**
 * ホームの特集枠を描く Compose file。見出し 1 行と、カードごとにメニューを持つ 2 列の動画グリッドは
 * この file の composable が描いています。
 */
private const val FEATURED_GRID_SOURCE = "Home26FeedShortFormGridKt"
private const val FEATURED_GRID_SOURCE_FILE = "Home26FeedShortFormGrid.kt"

/**
 * 特集枠 module の view data が `toString()` に必ず残す marker。
 *
 * <p>26.11.0 では renderer の第 2 引数を feed module state の難読化型（`Ll72/f;`）で固定していましたが、
 * この型名は版ごとに変わります。代わりに第 1 引数の view data を非難読化の marker で固定し、
 * module state の位置は型を問わない object 型としてだけ検証します。</p>
 */
internal const val FEATURED_GRID_VIEW_DATA_MARKER = "GcsHomeFeedUnitShortFormGrid(id="

/** 特集枠の module renderer は 1 件だけです。解決できなければ一切注入しません。 */
internal const val HOME_FEATURED_COLLECTIONS_TARGET_COUNT = 1

/**
 * 特集枠 module の view state 型を最低 2 つ組み立てることを、renderer の必須条件にします。
 * 見出しを含む grid 全体の state と、カード 1 枚ごとの state の両方がここで作られます。
 */
internal const val FEATURED_GRID_STATE_TYPE_MINIMUM = 2

/** 特集枠 module の view data。`toString()` の marker だけを anchor にします。 */
private val featuredGridViewDataFingerprint = Fingerprint(strings = listOf(FEATURED_GRID_VIEW_DATA_MARKER))

/**
 * 特集枠の grid を描く composable の package を、coroutine の DebugMetadata から導きます。
 * source metadata は R8 後も残るため、難読化された class 名を anchor にせずに済みます。
 */
private fun featuredGridSourceMetadataFingerprint(debugMetadataType: String) = Fingerprint(
    custom = { _, classDef ->
        val source = debugMetadataSource(classDef, debugMetadataType)
        source != null &&
            source.className.contains(FEATURED_GRID_SOURCE) &&
            source.sourceFile == FEATURED_GRID_SOURCE_FILE
    },
)

/**
 * ホームの特集枠を、LINE 自身の skip 経路へ倒して描かせません。
 *
 * <p>composable が本体を実行するかどうかを決める `Composer.shouldExecute` の結果だけを書き換えます。
 * 抑制時に通るのは、LINE が再 composition で本体を省くときと同じ `skipToGroupEnd` と
 * `endRestartGroup` の経路です。設定 OFF・未初期化・例外時は元の結果をそのまま使います。</p>
 */
val homeFeaturedCollectionsPatch = bytecodePatch(
    name = "ホームの特集枠",
    description = "ホームの特集枠にある動画のグリッドを、実行時設定で非表示にできるようにします。",
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
    dependsOn(premiumSettingsRowPatch)

    execute {
        val composeRuntime = resolveComposeRuntime()
        val debugMetadataType = resolveDebugMetadataType()
        if (composeRuntime == null || debugMetadataType == null) {
            patchStatusCollector.record(
                homeFeaturedCollectionsUnappliedRecord(0, "HomeFeaturedCollectionsRuntimeAnchorNotResolved"),
            )
            return@execute
        }

        val gridPackages = featuredGridSourceMetadataFingerprint(debugMetadataType).matchAllOrNull().orEmpty()
            .map { it.originalClassDef.type }
            .toSet()
            .let(::featuredGridPackagePrefixes)
        if (gridPackages.size != 1) {
            patchStatusCollector.record(
                homeFeaturedCollectionsUnappliedRecord(
                    gridPackages.size,
                    "HomeFeaturedGridSourcePackageNotUnique",
                ),
            )
            return@execute
        }
        val gridPackage = gridPackages.single()

        val viewDataTypes = featuredGridViewDataFingerprint.matchAllOrNull().orEmpty()
            .map { it.originalClassDef.type }
            .toSet()
        if (viewDataTypes.size != 1) {
            patchStatusCollector.record(
                homeFeaturedCollectionsUnappliedRecord(
                    viewDataTypes.size,
                    "HomeFeaturedGridViewDataNotUnique",
                ),
            )
            return@execute
        }
        val viewDataType = viewDataTypes.single()

        /**
         * 特集枠の module renderer。難読化された class / method 名ではなく、view data を第 1 引数に
         * 取ること、grid state を組み立てていること、composer lifecycle の呼出しで絞り込みます。
         */
        val rendererFingerprint = Fingerprint(
            returnType = VOID,
            custom = { method, _ ->
                isFeaturedCollectionsRendererSignature(method, viewDataType, composeRuntime.composer) &&
                    featuredGridStateTypes(method, gridPackage).size >= FEATURED_GRID_STATE_TYPE_MINIMUM
            },
            // `endRestartGroup` は method 名を安定して導出できないため filter から外し、
            // 注入前の shape 判定側で「呼出しが 1 件だけあること」を確認します。
            filters = listOf(
                methodCall(
                    definingClass = composeRuntime.composerImpl,
                    name = composeRuntime.shouldExecute,
                    parameters = listOf(INT, BOOLEAN),
                    returnType = BOOLEAN,
                    opcode = Opcode.INVOKE_VIRTUAL,
                ),
                methodCall(
                    definingClass = composeRuntime.composerImpl,
                    name = composeRuntime.skipToGroupEnd,
                    parameters = emptyList(),
                    returnType = VOID,
                    opcode = Opcode.INVOKE_VIRTUAL,
                ),
            ),
        )
        val renderers = rendererFingerprint.matchAllOrNull().orEmpty()
        if (renderers.size != HOME_FEATURED_COLLECTIONS_TARGET_COUNT) {
            patchStatusCollector.record(
                homeFeaturedCollectionsUnappliedRecord(
                    renderers.size,
                    "HomeFeaturedCollectionsRendererNotUnique",
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
                    patchId = PatchId.HOME_FEATURED_COLLECTIONS,
                    expectedTargetCount = HOME_FEATURED_COLLECTIONS_TARGET_COUNT,
                    actualTargetCount = renderers.size,
                    reason = "HomeFeaturedCollectionsShapeMismatch",
                ),
            )
            return@execute
        }

        method.addInstructionsWithLabels(
            gate.branchIndex,
            composeShouldExecuteSuppression(gate, HOME_FEATURED_COLLECTIONS_HOOK),
        )

        patchStatusCollector.record(
            patchId = PatchId.HOME_FEATURED_COLLECTIONS,
            expectedTargetCount = HOME_FEATURED_COLLECTIONS_TARGET_COUNT,
            actualTargetCount = HOME_FEATURED_COLLECTIONS_TARGET_COUNT,
            reason = "HomeFeaturedCollectionsSuppressionGuarded",
        )
    }
}

/**
 * 対象を見つけられなかった場合の記録。0 件は TARGET_NOT_FOUND、複数見つかった場合は
 * 意図した 1 件に絞り込めていないため ERROR として raw match count を残します。
 */
internal fun homeFeaturedCollectionsUnappliedRecord(resolvedCount: Int, reason: String) = PatchStatusRecord(
    patchId = PatchId.HOME_FEATURED_COLLECTIONS,
    status = if (resolvedCount == 0) PatchStatus.TARGET_NOT_FOUND else PatchStatus.ERROR,
    expectedTargetCount = HOME_FEATURED_COLLECTIONS_TARGET_COUNT,
    actualTargetCount = resolvedCount,
    reason = reason,
)

/** DebugMetadata が指す class の package。source file が 1 つに収まらなければ何も注入しません。 */
internal fun featuredGridPackagePrefixes(sourceTypes: Set<String>): Set<String> = sourceTypes
    .mapNotNull { type ->
        val separator = type.lastIndexOf('/')
        if (separator > 0) type.substring(0, separator + 1) else null
    }
    .toSet()

/**
 * module renderer の引数の並び。module state の型は版ごとに変わるため、その位置は型を問わず、
 * view data・composer・changed flag の並びだけを検証します。
 */
internal fun isFeaturedCollectionsRendererSignature(
    method: Method,
    viewDataType: String,
    composer: String,
): Boolean {
    val parameters = method.parameterTypes.map { it.toString() }
    return parameters.size == 4 &&
        parameters[0] == viewDataType &&
        parameters[1].startsWith("L") &&
        parameters[2] == composer &&
        parameters[3] == INT
}

/** renderer が組み立てる、特集枠 grid の composable と同じ package の view state 型。 */
internal fun featuredGridStateTypes(method: Method, gridPackage: String): Set<String> =
    method.implementation?.instructions?.toList().orEmpty()
        .filter { it.opcode == Opcode.NEW_INSTANCE }
        .mapNotNull { ((it as? ReferenceInstruction)?.reference as? TypeReference)?.type }
        .filter { it.startsWith(gridPackage) }
        .toSet()
