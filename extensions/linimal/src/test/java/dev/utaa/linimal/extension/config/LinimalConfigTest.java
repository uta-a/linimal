package dev.utaa.linimal.extension.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class LinimalConfigTest {
    @Test
    public void beforeInitializationHooksPreserveOriginalBehavior() {
        LinimalConfig config = LinimalConfig.get();

        assertEquals(LinimalConfigHealth.ERROR, config.getRuntimeHealth());
        assertFalse(config.isAdsSuppressionEnabled());
        assertFalse(config.isPremiumSuppressionEnabled());
        assertEquals(ReadReceiptMode.NORMAL, config.getReadReceiptMode());
    }

    @Test
    public void defaultsMatchV1SpecificationAndInitializeSchema() {
        InMemoryBackend backend = new InMemoryBackend();
        LinimalConfig config = configFor(backend);

        assertEquals(LinimalConfigHealth.OK, config.getRuntimeHealth());
        assertTrue(config.isAdsSuppressionEnabled());
        assertTrue(config.isLineAiSuppressionEnabled());
        assertTrue(config.isPremiumSuppressionEnabled());
        assertTrue(config.isVoomSuppressionEnabled());
        assertTrue(config.isNewsSuppressionEnabled());
        assertFalse(config.isWalletSuppressionEnabled());
        assertTrue(config.isHomeRecommendationsSuppressionEnabled());
        assertTrue(config.isHomeTrendingSuppressionEnabled());
        assertTrue(config.isChatCalendarSuppressionEnabled());
        assertTrue(config.isChatLineGiftSuppressionEnabled());
        assertTrue(config.isChatLinePaySuppressionEnabled());
        assertEquals(ReadReceiptMode.NORMAL, config.getReadReceiptMode());
        assertFalse(config.isExternalBrowserOverrideEnabled());
        assertFalse(config.isDebugLoggingEnabled());
        assertEquals(
                LinimalConfigSchema.CURRENT_VERSION,
                backend.values.get(LinimalConfigSchema.SCHEMA_VERSION_KEY));
    }

    @Test
    public void v0MigrationPreservesExistingCompatibleValues() {
        InMemoryBackend backend = new InMemoryBackend();
        backend.values.put(LinimalConfigSchema.SCHEMA_VERSION_KEY, 0);
        backend.values.put(LinimalConfigSchema.ADS_ENABLED_KEY, false);
        backend.values.put(LinimalConfigSchema.READ_RECEIPT_MODE_KEY, "manual");

        LinimalConfig config = configFor(backend);

        assertEquals(LinimalConfigHealth.OK, config.getRuntimeHealth());
        assertFalse(config.isAdsSuppressionEnabled());
        assertEquals(ReadReceiptMode.MANUAL, config.getReadReceiptMode());
        assertEquals(
                LinimalConfigSchema.CURRENT_VERSION,
                backend.values.get(LinimalConfigSchema.SCHEMA_VERSION_KEY));
    }

    @Test
    public void invalidBooleanFailsOpenForEveryHook() {
        InMemoryBackend backend = new InMemoryBackend();
        backend.values.put(LinimalConfigSchema.ADS_ENABLED_KEY, "true");

        LinimalConfig config = configFor(backend);

        assertEquals(LinimalConfigHealth.ERROR, config.getRuntimeHealth());
        assertFalse(config.isAdsSuppressionEnabled());
        assertFalse(config.isPremiumSuppressionEnabled());
        assertFalse(config.isHomeRecommendationsSuppressionEnabled());
        assertFalse(config.isChatLinePaySuppressionEnabled());
        assertFalse(config.isExternalBrowserOverrideEnabled());
        assertEquals(ReadReceiptMode.NORMAL, config.getReadReceiptMode());
    }

    @Test
    public void invalidReadReceiptModeFailsOpen() {
        InMemoryBackend backend = new InMemoryBackend();
        backend.values.put(LinimalConfigSchema.READ_RECEIPT_MODE_KEY, "automatic");

        LinimalConfig config = configFor(backend);

        assertEquals(LinimalConfigHealth.ERROR, config.getRuntimeHealth());
        assertFalse(config.isLineAiSuppressionEnabled());
        assertEquals(ReadReceiptMode.NORMAL, config.getReadReceiptMode());
    }

    @Test
    public void invalidSchemaTypeFailsOpenWithoutOverwritingIt() {
        InMemoryBackend backend = new InMemoryBackend();
        backend.values.put(LinimalConfigSchema.SCHEMA_VERSION_KEY, "1");

        LinimalConfig config = configFor(backend);

        assertEquals(LinimalConfigHealth.ERROR, config.getRuntimeHealth());
        assertFalse(config.isAdsSuppressionEnabled());
        assertEquals("1", backend.values.get(LinimalConfigSchema.SCHEMA_VERSION_KEY));
    }

    @Test
    public void unsupportedSchemaFailsOpenWithoutOverwritingIt() {
        InMemoryBackend backend = new InMemoryBackend();
        backend.values.put(
                LinimalConfigSchema.SCHEMA_VERSION_KEY,
                LinimalConfigSchema.CURRENT_VERSION + 1);

        LinimalConfig config = configFor(backend);

        assertEquals(LinimalConfigHealth.ERROR, config.getRuntimeHealth());
        assertFalse(config.isNewsSuppressionEnabled());
        assertEquals(
                LinimalConfigSchema.CURRENT_VERSION + 1,
                backend.values.get(LinimalConfigSchema.SCHEMA_VERSION_KEY));
    }

    @Test
    public void readFailureFailsOpen() {
        LinimalConfig config = LinimalConfig.fromStoreForTesting(
                LinimalConfigStore.forTesting(new FailingReadBackend()));

        assertEquals(LinimalConfigHealth.ERROR, config.getRuntimeHealth());
        assertFalse(config.isPremiumSuppressionEnabled());
        assertEquals(ReadReceiptMode.NORMAL, config.getReadReceiptMode());
    }

    @Test
    public void semanticWritesPersistAndRefreshTheTypedSnapshot() {
        InMemoryBackend backend = new InMemoryBackend();
        LinimalConfig config = configFor(backend);

        config.setPremiumSuppressionEnabled(false);
        config.setWalletSuppressionEnabled(true);
        config.setReadReceiptMode(ReadReceiptMode.MANUAL);
        config.setExternalBrowserOverrideEnabled(true);

        assertEquals(LinimalConfigHealth.OK, config.getRuntimeHealth());
        assertFalse(config.isPremiumSuppressionEnabled());
        assertTrue(config.isWalletSuppressionEnabled());
        assertEquals(ReadReceiptMode.MANUAL, config.getReadReceiptMode());
        assertTrue(config.isExternalBrowserOverrideEnabled());
        assertEquals(false, backend.values.get(LinimalConfigSchema.PREMIUM_ENABLED_KEY));
        assertEquals(true, backend.values.get(LinimalConfigSchema.WALLET_ENABLED_KEY));
        assertEquals("manual", backend.values.get(LinimalConfigSchema.READ_RECEIPT_MODE_KEY));
        assertEquals(true, backend.values.get(LinimalConfigSchema.EXTERNAL_BROWSER_ENABLED_KEY));
    }

    @Test
    public void nullPersistedValuesAreInvalidRatherThanDefaults() {
        InMemoryBackend backend = new InMemoryBackend();
        backend.values.put(LinimalConfigSchema.DEBUG_LOGGING_ENABLED_KEY, null);

        LinimalConfig config = configFor(backend);

        assertEquals(LinimalConfigHealth.ERROR, config.getRuntimeHealth());
        assertFalse(config.isDebugLoggingEnabled());
    }

    @Test
    public void unknownStoredReadReceiptModeIsRejectedExactly() {
        try {
            ReadReceiptMode.fromStoredValue("NORMAL");
            fail("Uppercase mode must not be accepted");
        } catch (ConfigStoreException expected) {
            // 想定どおりです。stored value は安定した厳密な schema 契約です。
        }
    }

    private static LinimalConfig configFor(InMemoryBackend backend) {
        return LinimalConfig.fromStoreForTesting(LinimalConfigStore.forTesting(backend));
    }

    private static final class InMemoryBackend implements LinimalConfigStore.PreferenceBackend {
        private final Map<String, Object> values = new HashMap<>();

        @Override
        public Map<String, ?> readAll() {
            return Collections.unmodifiableMap(new HashMap<>(values));
        }

        @Override
        public boolean write(Map<String, Object> updates) {
            values.putAll(updates);
            return true;
        }
    }

    private static final class FailingReadBackend implements LinimalConfigStore.PreferenceBackend {
        @Override
        public Map<String, ?> readAll() {
            throw new IllegalStateException("Storage unavailable");
        }

        @Override
        public boolean write(Map<String, Object> updates) {
            return false;
        }
    }
}
