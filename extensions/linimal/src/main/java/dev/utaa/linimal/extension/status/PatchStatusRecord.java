package dev.utaa.linimal.extension.status;

/** build-time status report 内の patch 1 件。 */
public final class PatchStatusRecord {
    private final String patchId;
    private final String featureId;
    private final PatchStatus status;
    private final int expectedTargetCount;
    private final int actualTargetCount;
    private final String reason;

    PatchStatusRecord(
            String patchId,
            String featureId,
            PatchStatus status,
            int expectedTargetCount,
            int actualTargetCount,
            String reason) {
        this.patchId = patchId;
        this.featureId = featureId;
        this.status = status;
        this.expectedTargetCount = expectedTargetCount;
        this.actualTargetCount = actualTargetCount;
        this.reason = reason;
    }

    public String getPatchId() {
        return patchId;
    }

    public String getFeatureId() {
        return featureId;
    }

    public PatchStatus getStatus() {
        return status;
    }

    public int getExpectedTargetCount() {
        return expectedTargetCount;
    }

    public int getActualTargetCount() {
        return actualTargetCount;
    }

    /** build-time 側で sanitize 済みの diagnostic label を返します。 */
    public String getReason() {
        return reason;
    }
}
