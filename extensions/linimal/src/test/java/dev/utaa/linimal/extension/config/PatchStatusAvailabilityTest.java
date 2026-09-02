package dev.utaa.linimal.extension.config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import dev.utaa.linimal.extension.status.PatchStatusReadResult;
import dev.utaa.linimal.extension.status.PatchStatusRepository;

public final class PatchStatusAvailabilityTest {
    @Test
    public void onlyFullyAppliedFeaturesAreAvailable() {
        FeatureAvailability availability = PatchStatusAvailability.of(read(
                "{\"schemaVersion\":1,\"patches\":["
                        + patch("linimal.patch.applied", "linimal.applied", "OK", 1, 1) + ","
                        + patch("linimal.patch.partial", "linimal.partial", "PARTIAL", 2, 1)
                        + "]}"));

        assertTrue(availability.isAvailable("linimal.applied"));
        assertFalse(availability.isAvailable("linimal.partial"));
    }

    @Test
    public void featuresMissingFromTheReportAreUnavailable() {
        FeatureAvailability availability = PatchStatusAvailability.of(read(
                "{\"schemaVersion\":1,\"patches\":["
                        + patch("linimal.patch.applied", "linimal.applied", "OK", 1, 1)
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
