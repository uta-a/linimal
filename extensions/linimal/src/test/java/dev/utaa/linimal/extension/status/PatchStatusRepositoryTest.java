package dev.utaa.linimal.extension.status;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class PatchStatusRepositoryTest {
    private final PatchStatusParser parser = new PatchStatusParser();

    @Test
    public void parsesValidJsonStrictly() {
        PatchStatusReport report = parser.parse(reportJson("OK", 1, 1));

        assertEquals(PatchStatusParser.SCHEMA_VERSION, report.getSchemaVersion());
        assertEquals(1, report.getPatches().size());
        assertEquals("linimal.patch.premium", report.getPatches().get(0).getPatchId());
        assertEquals(PatchStatus.OK, report.getPatches().get(0).getStatus());
    }

    @Test
    public void readsAssetEquivalentInput() throws Exception {
        InputStream input = getClass().getResourceAsStream("/linimal/patch-status.json");
        assertNotNull(input);
        try {
            PatchStatusReadResult result = PatchStatusRepository.read(input);

            assertTrue(result.isAvailable());
            assertEquals(PatchStatus.OK, result.getReport().getPremiumStatus());
        } finally {
            input.close();
        }
    }

    @Test
    public void unknownSchemaReturnsErrorInsteadOfThrowing() {
        PatchStatusReadResult result = read("{\"schemaVersion\":2,\"patches\":[]}");

        assertEquals(PatchStatusReadResult.State.ERROR, result.getState());
        assertFalse(result.isAvailable());
    }

    @Test
    public void malformedJsonReturnsErrorInsteadOfThrowing() {
        PatchStatusReadResult result = read("{\"schemaVersion\":1,\"patches\":[");

        assertEquals(PatchStatusReadResult.State.ERROR, result.getState());
        assertFalse(result.isAvailable());
    }

    @Test
    public void inputOver64KiBReturnsErrorInsteadOfParsingIt() {
        byte[] oversized = new byte[PatchStatusRepository.MAX_ASSET_BYTES + 1];
        Arrays.fill(oversized, (byte) ' ');

        PatchStatusReadResult result = PatchStatusRepository.read(new ByteArrayInputStream(oversized));

        assertEquals(PatchStatusReadResult.State.ERROR, result.getState());
        assertTrue(result.getReason().contains("64 KiB"));
    }

    @Test
    public void duplicatePatchIdIsRejected() {
        String patch = "{\"patchId\":\"linimal.patch.premium\",\"featureId\":\"linimal.premium\","
                + "\"status\":\"OK\",\"expectedTargetCount\":1,\"actualTargetCount\":1}";

        assertInvalid("{\"schemaVersion\":1,\"patches\":[" + patch + "," + patch + "]}");
    }

    @Test
    public void negativeCountsAreRejected() {
        assertInvalid(reportJson("OK", -1, 0));
        assertInvalid(reportJson("OK", 0, -1));
    }

    @Test
    public void statusMustMatchCollectorCountRules() {
        parser.parse(reportJson("OK", 0, 0));
        parser.parse(reportJson("TARGET_NOT_FOUND", 1, 0));
        parser.parse(reportJson("PARTIAL", 2, 1));
        parser.parse(reportJson("ERROR", 1, 2));
        parser.parse(reportJson("DISABLED", 0, 0));

        assertInvalid(reportJson("OK", 1, 0));
        assertInvalid(reportJson("TARGET_NOT_FOUND", 1, 1));
        assertInvalid(reportJson("PARTIAL", 1, 2));
        assertInvalid(reportJson("ERROR", 1, 1));
        assertInvalid(reportJson("DISABLED", 1, 0));
        assertInvalid(reportJson("DISABLED", 0, 1));
    }

    @Test
    public void premiumStatusRequiresEveryPremiumPatchToBeOk() {
        String premiumOk = "{\"patchId\":\"linimal.patch.premium-a\",\"featureId\":\"linimal.premium\","
                + "\"status\":\"OK\",\"expectedTargetCount\":1,\"actualTargetCount\":1}";
        String premiumPartial = "{\"patchId\":\"linimal.patch.premium-b\",\"featureId\":\"linimal.premium\","
                + "\"status\":\"PARTIAL\",\"expectedTargetCount\":2,\"actualTargetCount\":1}";

        PatchStatusReport report = parser.parse(
                "{\"schemaVersion\":1,\"patches\":[" + premiumOk + "," + premiumPartial + "]}");

        assertEquals(PatchStatus.PARTIAL, report.getPremiumStatus());
        assertNull(report.getFeatureStatus("linimal.missing"));
        assertNull(report.getFeatureStatus(null));
    }

    @Test
    public void unknownFieldsAreRejected() {
        assertInvalid("{\"schemaVersion\":1,\"patches\":[],\"unexpected\":true}");
    }

    private PatchStatusReadResult read(String json) {
        return PatchStatusRepository.read(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    private void assertInvalid(String json) {
        try {
            parser.parse(json);
            fail("Expected strict schema validation failure");
        } catch (IllegalArgumentException expected) {
            // 想定どおりです。asset の schema は runtime に対する厳密な契約です。
        }
    }

    private static String reportJson(String status, int expectedTargetCount, int actualTargetCount) {
        return "{\"schemaVersion\":1,\"patches\":[{"
                + "\"patchId\":\"linimal.patch.premium\","
                + "\"featureId\":\"linimal.premium\","
                + "\"status\":\"" + status + "\","
                + "\"expectedTargetCount\":" + expectedTargetCount + ","
                + "\"actualTargetCount\":" + actualTargetCount
                + "}]}";
    }
}
