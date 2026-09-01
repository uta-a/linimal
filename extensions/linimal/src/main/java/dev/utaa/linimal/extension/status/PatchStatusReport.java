package dev.utaa.linimal.extension.status;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 検証済みの build-time patch status report。 */
public final class PatchStatusReport {
    /** Premium suppression hook に対応する安定した feature ID。 */
    public static final String PREMIUM_FEATURE_ID = "linimal.premium";

    private final int schemaVersion;
    private final List<PatchStatusRecord> patches;

    PatchStatusReport(int schemaVersion, List<PatchStatusRecord> patches) {
        this.schemaVersion = schemaVersion;
        this.patches = Collections.unmodifiableList(new ArrayList<>(patches));
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public List<PatchStatusRecord> getPatches() {
        return patches;
    }

    /** 指定 feature の全 patch が OK である場合だけ OK を返します。 */
    public PatchStatus getFeatureStatus(String featureId) {
        if (featureId == null) {
            return null;
        }
        PatchStatus nonOkStatus = null;
        boolean found = false;
        for (PatchStatusRecord patch : patches) {
            if (!featureId.equals(patch.getFeatureId())) {
                continue;
            }
            found = true;
            if (patch.getStatus() != PatchStatus.OK && nonOkStatus == null) {
                nonOkStatus = patch.getStatus();
            }
        }
        if (!found) {
            return null;
        }
        return nonOkStatus == null ? PatchStatus.OK : nonOkStatus;
    }

    public PatchStatus getPremiumStatus() {
        return getFeatureStatus(PREMIUM_FEATURE_ID);
    }
}
