package dev.utaa.linimal.patches.status

/**
 * 任意機能の target cardinality を一貫して report へ残す小さな helper。
 * shared transform は、この helper を feature ごとに呼び出して設定 UI の可用性を独立に保ちます。
 */
internal fun recordFeatureStatus(
    patchIds: Iterable<PatchId>,
    expectedTargetCount: Int,
    actualTargetCount: Int,
    reason: String,
) {
    patchIds.forEach { patchId ->
        patchStatusCollector.record(
            patchId = patchId,
            expectedTargetCount = expectedTargetCount,
            actualTargetCount = actualTargetCount,
            reason = reason,
        )
    }
}

/**
 * fingerprint cardinality は一意でも、注入位置の opcode/register/reference shape が安全でない場合の結果。
 * actual は fingerprint が実際に見つけた target 数のまま保持し、runtime parser もこの ERROR 契約を受け入れる。
 */
internal fun unsafeFeatureStatus(
    patchId: PatchId,
    expectedTargetCount: Int,
    actualTargetCount: Int,
    reason: String,
): PatchStatusRecord = PatchStatusRecord(
    patchId = patchId,
    status = PatchStatus.ERROR,
    expectedTargetCount = expectedTargetCount,
    actualTargetCount = actualTargetCount,
    reason = reason,
)

internal fun recordUnsafeFeatureStatus(
    patchIds: Iterable<PatchId>,
    expectedTargetCount: Int,
    actualTargetCount: Int,
    reason: String,
) {
    patchIds.forEach { patchId ->
        patchStatusCollector.record(
            unsafeFeatureStatus(patchId, expectedTargetCount, actualTargetCount, reason),
        )
    }
}

/**
 * 注入をまったく行わずに終了した場合の結果。
 *
 * <p>[recordFeatureStatus] は件数から status を導くため、`actualTargetCount` が
 * `expectedTargetCount` と一致すると **OK** になります。対象は見つかったが注入しなかった経路で
 * これを使うと、設定 UI がその機能を利用可能として表示し、トグルを操作しても何も起きません。</p>
 *
 * <p>この helper は「見つからなかった」ときだけ TARGET_NOT_FOUND とし、見つかったうえで
 * 適用しなかった場合は ERROR を記録します。runtime parser は ERROR を
 * `actualTargetCount >= 1` の条件でそのまま受け付けます。</p>
 */
internal fun recordUnappliedFeatureStatus(
    patchIds: Iterable<PatchId>,
    expectedTargetCount: Int,
    matchCount: Int,
    reason: String,
) {
    if (matchCount == 0) {
        recordFeatureStatus(patchIds, expectedTargetCount, 0, reason)
    } else {
        recordUnsafeFeatureStatus(patchIds, expectedTargetCount, matchCount, reason)
    }
}
