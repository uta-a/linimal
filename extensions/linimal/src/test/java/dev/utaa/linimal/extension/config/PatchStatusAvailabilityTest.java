package dev.utaa.linimal.extension.config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import dev.utaa.linimal.extension.status.PatchStatusReadResult;
import dev.utaa.linimal.extension.status.PatchStatusRepository;

public final class PatchStatusAvailabilityTest {
    /** 必須 patch が登録済みの feature でないと利用可能にならないため、実在の ID を使います。 */
    @Test
    public void onlyFullyAppliedFeaturesAreAvailable() {
        FeatureAvailability availability = PatchStatusAvailability.of(read(
                "{\"schemaVersion\":1,\"patches\":["
                        + patch("linimal.patch.premium-unsend", "linimal.premium", "OK", 1, 1) + ","
                        + patch("linimal.patch.premium-settings-row", "linimal.premium-settings-row",
                                "PARTIAL", 2, 1)
                        + "]}"));

        assertTrue(availability.isAvailable("linimal.premium"));
        assertFalse(availability.isAvailable("linimal.premium-settings-row"));
    }

    @Test
    public void featuresMissingFromTheReportAreUnavailable() {
        FeatureAvailability availability = PatchStatusAvailability.of(read(
                "{\"schemaVersion\":1,\"patches\":["
                        + patch("linimal.patch.premium-unsend", "linimal.premium", "OK", 1, 1)
                        + "]}"));

        assertFalse(availability.isAvailable("linimal.unrecorded"));
        assertFalse(availability.isAvailable(null));
    }

    @Test
    public void unreadableReportMakesEveryFeatureUnavailable() {
        FeatureAvailability broken = PatchStatusAvailability.of(
                read("{\"schemaVersion\":1,\"patches\":["));

        assertFalse(broken.isAvailable("linimal.premium"));
        assertFalse(PatchStatusAvailability.of(null).isAvailable("linimal.premium"));
    }

    private static PatchStatusReadResult read(String json) {
        return PatchStatusRepository.read(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    private static String patch(
            String patchId, String featureId, String status, int expected, int actual) {
        return "{\"patchId\":\"" + patchId + "\",\"featureId\":\"" + featureId + "\","
                + "\"status\":\"" + status + "\",\"expectedTargetCount\":" + expected + ","
                + "\"actualTargetCount\":" + actual + "}";
    }
}
