package dev.utaa.linimal.extension.status;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 検証済みの build-time patch status report。 */
public final class PatchStatusReport {
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

    /**
     * 指定 feature の必須 patch がすべて存在し、すべて OK である場合だけ OK を返します。
     *
     * <p>レポートに 1 件も記録がない feature は「この build には存在しない」意味で null を返します。
     * 記録はあるが必須 patch が {@link PatchStatusRequirements} に未登録の feature は、完全に適用
     * されたことを確認できないため ERROR を返し、利用可能にはしません。</p>
     */
    public PatchStatus getFeatureStatus(String featureId) {
        if (featureId == null) {
            return null;
        }
        PatchStatus nonOkStatus = null;
        Set<String> recordedPatchIds = new LinkedHashSet<>();
        for (PatchStatusRecord patch : patches) {
            if (!featureId.equals(patch.getFeatureId())) {
                continue;
            }
            recordedPatchIds.add(patch.getPatchId());
            if (patch.getStatus() != PatchStatus.OK && nonOkStatus == null) {
                nonOkStatus = patch.getStatus();
            }
        }
        if (recordedPatchIds.isEmpty()) {
            return null;
        }

        Set<String> requiredPatchIds = PatchStatusRequirements.requiredPatchIds(featureId);
        if (requiredPatchIds == null) {
            // 必要な patch が分からない以上、記録がすべて OK でも完全適用とは言えません。
            // 未登録を OK に倒すと、requirements への追加漏れがそのまま機能の誤有効化になります。
            return PatchStatus.ERROR;
        }
        if (!requiredPatchIds.equals(recordedPatchIds)) {
            return PatchStatus.ERROR;
        }
        return nonOkStatus == null ? PatchStatus.OK : nonOkStatus;
    }

    /** レポートに含まれる feature ID を、記録順のまま重複なく返します。 */
    public List<String> getFeatureIds() {
        List<String> featureIds = new ArrayList<>();
        for (PatchStatusRecord patch : patches) {
            if (!featureIds.contains(patch.getFeatureId())) {
                featureIds.add(patch.getFeatureId());
            }
        }
        return featureIds;
    }
}
