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

import dev.utaa.linimal.extension.settings.FeatureCatalog;

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
        parser.parse(reportJson("ERROR", 1, 1)); // opcode/register/reference shape rejected after one raw match
        parser.parse(reportJson("DISABLED", 0, 0));

        assertInvalid(reportJson("OK", 1, 0));
        assertInvalid(reportJson("TARGET_NOT_FOUND", 1, 1));
        assertInvalid(reportJson("PARTIAL", 1, 2));
        assertInvalid(reportJson("ERROR", 1, 0));
        assertInvalid(reportJson("DISABLED", 1, 0));
        assertInvalid(reportJson("DISABLED", 0, 1));
    }

    @Test
    public void featureStatusRequiresEveryRequiredPatchToBePresentAndOk() {
        String settingsResource = patchJson(
                "linimal.patch.settings-resource", "linimal.settings", "OK", 1, 1);
        String settingsEntryOk = patchJson(
                "linimal.patch.settings-entry", "linimal.settings", "OK", 2, 2);
        String settingsEntryPartial = patchJson(
                "linimal.patch.settings-entry", "linimal.settings", "PARTIAL", 2, 1);

        PatchStatusReport complete = parser.parse(
                "{\"schemaVersion\":1,\"patches\":[" + settingsResource + "," + settingsEntryOk + "]}");
        PatchStatusReport nonOk = parser.parse(
                "{\"schemaVersion\":1,\"patches\":[" + settingsResource + "," + settingsEntryPartial + "]}");
        PatchStatusReport missing = parser.parse(
                "{\"schemaVersion\":1,\"patches\":[" + settingsResource + "]}");

        assertEquals(PatchStatus.OK, complete.getFeatureStatus("linimal.settings"));
        assertEquals(PatchStatus.PARTIAL, nonOk.getFeatureStatus("linimal.settings"));
        assertEquals(PatchStatus.ERROR, missing.getFeatureStatus("linimal.settings"));
        assertNull(complete.getFeatureStatus("linimal.missing"));
        assertNull(complete.getFeatureStatus(null));
    }

    @Test
    public void agentISettingsShapeErrorRemainsReadableAtRuntime() {
        String patch = patchJson(
                "linimal.patch.agent-i-settings",
                "linimal.agent-i-settings",
                "ERROR",
                2,
                1);

        PatchStatusReport report = parser.parse(
                "{\"schemaVersion\":1,\"patches\":[" + patch + "]}");

        assertEquals(PatchStatus.ERROR,
                report.getFeatureStatus("linimal.agent-i-settings"));
    }

    @Test
    public void everyCatalogFeatureHasAnExplicitPatchRequirement() {
        for (FeatureCatalog.Entry entry : FeatureCatalog.entries()) {
            assertNotNull(entry.getFeatureId(),
                    PatchStatusRequirements.requiredPatchIds(entry.getFeatureId()));
        }
        assertNotNull(PatchStatusRequirements.requiredPatchIds(
                "linimal.read-receipts-main-chat"));
    }

    @Test
    public void homeFeedPostCardsAndPremiumSettingsRowResolveFromTheirOwnRequiredPatch() {
        String postCards = patchJson(
                "linimal.patch.home-feed-post-cards",
                "linimal.home-feed-post-cards", "OK", 1, 1);
        String premiumRow = patchJson(
                "linimal.patch.premium-settings-row",
                "linimal.premium-settings-row", "PARTIAL", 2, 1);

        PatchStatusReport report = parser.parse(
                "{\"schemaVersion\":1,\"patches\":[" + postCards + "," + premiumRow + "]}");

        assertEquals(PatchStatus.OK, report.getFeatureStatus("linimal.home-feed-post-cards"));
        assertEquals(PatchStatus.PARTIAL, report.getFeatureStatus("linimal.premium-settings-row"));
        assertNull(report.getFeatureStatus("linimal.home-recommendations"));
        assertNull(report.getFeatureStatus("linimal.premium"));
    }

    @Test
    public void homeFeaturedCollectionsResolvesFromItsOwnRequiredPatch() {
        String featured = patchJson(
                "linimal.patch.home-featured-collections",
                "linimal.home-featured-collections", "OK", 1, 1);

        PatchStatusReport report = parser.parse(
                "{\"schemaVersion\":1,\"patches\":[" + featured + "]}");

        assertEquals(PatchStatus.OK, report.getFeatureStatus("linimal.home-featured-collections"));
        assertNull(report.getFeatureStatus("linimal.home-feed-post-cards"));
        assertNull(report.getFeatureStatus("linimal.home-trending"));
    }

    @Test
    public void homeAdsRequireCatalogPerformanceAndGenericGates() {
        String flow = patchJson(
                "linimal.patch.home-top-ad-module-gate",
                "linimal.home-top-ad", "OK", 1, 1);
        String catalog = patchJson(
                "linimal.patch.home-top-ad-catalog-gate",
                "linimal.home-top-ad", "OK", 2, 2);
        String generic = patchJson(
                "linimal.patch.home-gcs-ad-module-gate",
                "linimal.home-top-ad", "OK", 1, 1);

        PatchStatusReport complete = parser.parse(
                "{\"schemaVersion\":1,\"patches\":[" + flow + "," + catalog + "," + generic + "]}");
        PatchStatusReport missingGeneric = parser.parse(
                "{\"schemaVersion\":1,\"patches\":[" + flow + "," + catalog + "]}");

        assertEquals(PatchStatus.OK, complete.getFeatureStatus("linimal.home-top-ad"));
        assertEquals(PatchStatus.ERROR, missingGeneric.getFeatureStatus("linimal.home-top-ad"));
    }

    @Test
    public void readReceiptFeatureIsErrorWhenOneRequiredPatchIsMissing() {
        String gate = patchJson(
                "linimal.patch.read-receipts-main-chat-gate",
                "linimal.read-receipts-main-chat", "OK", 1, 1);
        String queue = patchJson(
                "linimal.patch.read-receipts-main-chat-pending-queue-clear",
                "linimal.read-receipts-main-chat", "OK", 1, 1);
        String caller = patchJson(
                "linimal.patch.read-receipts-main-chat-manual-caller",
                "linimal.read-receipts-main-chat", "OK", 1, 1);
        String registration = patchJson(
                "linimal.patch.read-receipts-main-chat-supplier-registration",
                "linimal.read-receipts-main-chat", "OK", 1, 1);

        PatchStatusReport report = parser.parse(
                "{\"schemaVersion\":1,\"patches\":["
                        + gate + "," + queue + "," + caller + "," + registration + "]}");

        assertEquals(PatchStatus.ERROR,
                report.getFeatureStatus("linimal.read-receipts-main-chat"));
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

    private static String patchJson(
            String patchId,
            String featureId,
            String status,
            int expectedTargetCount,
            int actualTargetCount) {
        return "{"
                + "\"patchId\":\"" + patchId + "\","
                + "\"featureId\":\"" + featureId + "\","
                + "\"status\":\"" + status + "\","
                + "\"expectedTargetCount\":" + expectedTargetCount + ","
                + "\"actualTargetCount\":" + actualTargetCount
                + "}";
    }

    private static String reportJson(String status, int expectedTargetCount, int actualTargetCount) {
        return "{\"schemaVersion\":1,\"patches\":["
                + patchJson(
                        "linimal.patch.premium",
                        "linimal.premium",
                        status,
                        expectedTargetCount,
                        actualTargetCount)
                + "]}";
    }
}
