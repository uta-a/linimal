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
