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
import dev.utaa.linimal.patches.util.VOID
import dev.utaa.linimal.patches.util.composeShouldExecuteGate
import dev.utaa.linimal.patches.util.composeShouldExecuteSuppression
import dev.utaa.linimal.patches.util.debugMetadataSource
import dev.utaa.linimal.patches.util.resolveComposeRuntime
import dev.utaa.linimal.patches.util.resolveDebugMetadataType

private const val HOME_RECENT_HISTORY_HOOK =
    "Ldev/utaa/linimal/extension/features/HomeRecentHistoryHooks;->shouldSuppress()Z"

/**
 * ホーム上部に横並びで出る activity card を描く Compose file。「最近の履歴」も「サービス」も
 * この file の composable が 1 枚ずつ描いています。
 */
private const val ACTIVITY_CARD_SOURCE = "ActivityCardContentsKt"
private const val ACTIVITY_CARD_SOURCE_FILE = "ActivityCardContents.kt"

/**
 * 「最近の履歴」card の内容を表す data class が `toString()` に必ず残す marker。
 *
 * <p>card の種別（天気・スタンプ・誕生日・固定サービスなど）は同じ sealed 型の subclass で表され、
 * 型名は難読化されます。data class の `toString()` に残るこの marker だけが、「最近の履歴」を
 * 「サービス」（`FixedService`）から区別できる非難読化の手掛かりです。</p>
 */
internal const val RECENTLY_USED_SERVICE_MARKER = "RecentlyUsedService(id="

/** 「最近の履歴」card の renderer は 1 件だけです。解決できなければ一切注入しません。 */
internal const val HOME_RECENT_HISTORY_TARGET_COUNT = 1

/**
 * card renderer の引数の最小数。`(card, ..., Composer, $$changed)` の 3 つは version が変わっても
 * 並びが変わらないため、その間に挟まる引数の数は問いません。
 */
internal const val RECENT_HISTORY_CARD_MINIMUM_PARAMETER_COUNT = 3

/**
 * activity card を描く composable の package を、coroutine の DebugMetadata から導きます。
 * source metadata は R8 後も残るため、難読化された class 名を anchor にせずに済みます。
 */
private fun activityCardSourceMetadataFingerprint(debugMetadataType: String) = Fingerprint(
    custom = { _, classDef ->
        val source = debugMetadataSource(classDef, debugMetadataType)
        source != null &&
            source.className.contains(ACTIVITY_CARD_SOURCE) &&
            source.sourceFile == ACTIVITY_CARD_SOURCE_FILE
    },
)

/** 「最近の履歴」card の内容を表す data class。`toString()` の marker だけを anchor にします。 */
private val recentlyUsedServiceCardFingerprint = Fingerprint(strings = listOf(RECENTLY_USED_SERVICE_MARKER))

/**
 * ホーム上部の「最近の履歴」card を、LINE 自身の skip 経路へ倒して描かせません。
 *
 * <h2>対象の特定</h2>
 * <p>まず activity card を描く composable の package を DebugMetadata から求めます。次に
 * `RecentlyUsedService(id=` という非難読化の `toString()` marker から「最近の履歴」card の
 * 内容型を求め、その型を第 1 引数に取る composable を先の package から 1 件だけ選びます。
 * 隣に並ぶ「サービス」card は別の内容型（`FixedService`）を取る別の composable のため、
 * この絞り込みには入りません。</p>
 *
 * <h2>抑制の方法</h2>
 * <p>composable が本体を実行するかどうかを決める `Composer.shouldExecute` の結果だけを書き換えます。
 * 抑制時に通るのは、LINE が再 composition で本体を省くときと同じ `skipToGroupEnd` と
 * `endRestartGroup` の経路です。設定 OFF・未初期化・例外時は元の結果をそのまま使います。</p>
 */
val homeRecentHistoryPatch = bytecodePatch(
    name = "ホームの最近の履歴",
    description = "ホーム上部にある最近使用したサービスの枠を、実行時設定で非表示にできるようにします。",
) {
    compatibleWith(Constants.LINE_COMPATIBILITY)
    availability { _, architecture ->
        if (architecture == ApkArchitecture.ARM64_V8A) {
            PatchAvailability.REQUIRED
        } else {
            PatchAvailability.UNAVAILABLE
        }
    }
    // 機能パッチは単一の直列チェーンを成し、この patch の後段に homeFeedLoadingIndicatorPatch が続きます。
    dependsOn(homeFeaturedCollectionsPatch)

    execute {
        val composeRuntime = resolveComposeRuntime()
        val debugMetadataType = resolveDebugMetadataType()
        if (composeRuntime == null || debugMetadataType == null) {
            patchStatusCollector.record(
                homeRecentHistoryUnappliedRecord(0, "HomeRecentHistoryRuntimeAnchorNotResolved"),
            )
            return@execute
        }

        val activityCardPackages = activityCardSourceMetadataFingerprint(debugMetadataType).matchAllOrNull().orEmpty()
            .map { it.originalClassDef.type }
            .toSet()
            .let(::activityCardPackagePrefixes)
        if (activityCardPackages.size != 1) {
            patchStatusCollector.record(
                homeRecentHistoryUnappliedRecord(
                    activityCardPackages.size,
                    "HomeRecentHistorySourcePackageNotUnique",
                ),
            )
            return@execute
        }
        val activityCardPackage = activityCardPackages.single()

        // 26.14.0 では同じ marker を持つ card 内容型が Home26 と Global Home の 2 つに増えました。
        // 内容型そのものを 1 件に絞る代わりに、activity card の package にある renderer が
        // 1 件だけであることで対象を確定します。
        val cardContentTypes = recentlyUsedServiceCardFingerprint.matchAllOrNull().orEmpty()
            .map { it.originalClassDef.type }
            .toSet()
        if (cardContentTypes.isEmpty()) {
            patchStatusCollector.record(
                homeRecentHistoryUnappliedRecord(0, "HomeRecentHistoryCardContentTypeNotFound"),
            )
            return@execute
        }

        /**
         * 「最近の履歴」card の renderer。難読化された class / method 名ではなく、card の内容型を
         * 第 1 引数に取ること、composer lifecycle の呼出し、そして activity card の package で
         * 絞り込みます。
         */
        val rendererFingerprint = Fingerprint(
            returnType = VOID,
            custom = { method, classDef ->
                classDef.type.startsWith(activityCardPackage) &&
                    isRecentHistoryCardRendererSignature(method, cardContentTypes, composeRuntime.composer)
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
        if (renderers.size != HOME_RECENT_HISTORY_TARGET_COUNT) {
            patchStatusCollector.record(
                homeRecentHistoryUnappliedRecord(renderers.size, "HomeRecentHistoryRendererNotUnique"),
            )
            return@execute
        }

        val method = renderers.single().method
        val gate = composeShouldExecuteGate(method, composeRuntime)
        if (gate == null) {
            // cardinality は揃っていても注入位置の shape が崩れている場合は、何も変更しません。
            patchStatusCollector.record(
                unsafeFeatureStatus(
                    patchId = PatchId.HOME_RECENT_HISTORY,
                    expectedTargetCount = HOME_RECENT_HISTORY_TARGET_COUNT,
                    actualTargetCount = renderers.size,
                    reason = "HomeRecentHistoryShapeMismatch",
                ),
            )
            return@execute
        }

        method.addInstructionsWithLabels(
            gate.branchIndex,
            composeShouldExecuteSuppression(gate, HOME_RECENT_HISTORY_HOOK),
        )

        patchStatusCollector.record(
            patchId = PatchId.HOME_RECENT_HISTORY,
            expectedTargetCount = HOME_RECENT_HISTORY_TARGET_COUNT,
            actualTargetCount = HOME_RECENT_HISTORY_TARGET_COUNT,
            reason = "HomeRecentHistorySuppressionGuarded",
        )
    }
}

/**
 * 対象を見つけられなかった場合の記録。0 件は TARGET_NOT_FOUND、複数見つかった場合は
 * 意図した 1 件に絞り込めていないため ERROR として raw match count を残します。
 */
internal fun homeRecentHistoryUnappliedRecord(resolvedCount: Int, reason: String) = PatchStatusRecord(
    patchId = PatchId.HOME_RECENT_HISTORY,
    status = if (resolvedCount == 0) PatchStatus.TARGET_NOT_FOUND else PatchStatus.ERROR,
    expectedTargetCount = HOME_RECENT_HISTORY_TARGET_COUNT,
    actualTargetCount = resolvedCount,
    reason = reason,
)

/** DebugMetadata が指す class の package。source file が 1 つに収まらなければ何も注入しません。 */
internal fun activityCardPackagePrefixes(sourceTypes: Set<String>): Set<String> = sourceTypes
    .mapNotNull { type ->
        val separator = type.lastIndexOf('/')
        if (separator > 0) type.substring(0, separator + 1) else null
    }
    .toSet()

/**
 * card renderer の引数の並び。card ごとに中間の引数（表示位置・log 情報・callback）が変わるため、
 * 先頭の内容型と末尾の composer・`$$changed` の位置だけを検証します。
 */
internal fun isRecentHistoryCardRendererSignature(
    method: Method,
    cardContentTypes: Set<String>,
    composer: String,
): Boolean {
    val parameters = method.parameterTypes.map { it.toString() }
    return parameters.size >= RECENT_HISTORY_CARD_MINIMUM_PARAMETER_COUNT &&
        parameters.first() in cardContentTypes &&
        parameters[parameters.size - 2] == composer &&
        parameters.last() == INT
}
