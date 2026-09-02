package dev.utaa.linimal.extension.config;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import dev.utaa.linimal.extension.status.PatchStatus;
import dev.utaa.linimal.extension.status.PatchStatusReadResult;
import dev.utaa.linimal.extension.status.PatchStatusReport;

/** build-time の patch status report を、feature ID ごとの利用可否へ変換します。 */
final class PatchStatusAvailability implements FeatureAvailability {
    private final Set<String> availableFeatureIds;

    private PatchStatusAvailability(Set<String> availableFeatureIds) {
        this.availableFeatureIds = availableFeatureIds;
    }

    /**
     * report を読めなかった場合は、すべての機能を利用不可として扱います。
     * asset の不在、破損、サイズ超過のいずれも同じ扱いで、LINE の元の動作へ戻します。
     */
    static FeatureAvailability of(PatchStatusReadResult result) {
        if (result == null || !result.isAvailable()) {
            return FeatureAvailability.NONE;
        }
        return of(result.getReport());
    }

    /** 必須 patch がすべて適用済みで OK の feature ID だけを利用可能とします。 */
    private static FeatureAvailability of(PatchStatusReport report) {
        if (report == null) {
            return FeatureAvailability.NONE;
        }
        Set<String> availableFeatureIds = new LinkedHashSet<>();
        for (String featureId : report.getFeatureIds()) {
            if (report.getFeatureStatus(featureId) == PatchStatus.OK) {
                availableFeatureIds.add(featureId);
            }
        }
        return new PatchStatusAvailability(Collections.unmodifiableSet(availableFeatureIds));
    }

    @Override
    public boolean isAvailable(String featureId) {
        return featureId != null && availableFeatureIds.contains(featureId);
    }
}
