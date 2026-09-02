package dev.utaa.linimal.extension.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class LinimalConfigTest {
    @Test
    public void beforeInitializationHooksPreserveOriginalBehavior() {
        LinimalConfig config = LinimalConfig.get();

        assertEquals(LinimalConfigHealth.ERROR, config.getRuntimeHealth());
        assertFalse(config.isSmartChannelAdsSuppressionEnabled());
        assertFalse(config.isHomeTopAdSuppressionEnabled());
        assertFalse(config.isAgentIHomeHeaderSuppressionEnabled());
        assertFalse(config.isAgentIChatInformationSuppressionEnabled());
        assertFalse(config.isAgentIWalletHeaderSuppressionEnabled());
        assertFalse(config.isAgentISettingsSuppressionEnabled());
        assertFalse(config.isAgentIChatComposerSuppressionEnabled());
        assertFalse(config.isLineAiMessageContextMenuSuppressionEnabled());
        assertFalse(config.isLineAiGalleryViewerSuppressionEnabled());
        assertFalse(config.isShoppingSuppressionEnabled());
        assertFalse(config.isAdsSuppressionEnabled());
        assertFalse(config.isLineAiSuppressionEnabled());
        assertFalse(config.isPremiumSuppressionEnabled());
        assertFalse(config.isPremiumSettingsRowSuppressionEnabled());
        assertFalse(config.isHomeFeedPostCardsSuppressionEnabled());
        assertFalse(config.isHomeFeaturedCollectionsSuppressionEnabled());
        assertEquals(ReadReceiptMode.NORMAL, config.getReadReceiptMode());
    }

    @Test
    public void defaultsMatchV2SpecificationAndInitializeSchema() {
        InMemoryBackend backend = new InMemoryBackend();
        LinimalConfig config = configFor(backend);

        assertEquals(LinimalConfigHealth.OK, config.getRuntimeHealth());
        assertTrue(config.isSmartChannelAdsSuppressionEnabled());
        assertTrue(config.isHomeTopAdSuppressionEnabled());
        assertTrue(config.isAgentIHomeHeaderSuppressionEnabled());
        assertTrue(config.isAgentIChatInformationSuppressionEnabled());
        assertTrue(config.isAgentIWalletHeaderSuppressionEnabled());
        assertTrue(config.isAgentISettingsSuppressionEnabled());
        assertTrue(config.isAgentIChatComposerSuppressionEnabled());
        assertTrue(config.isLineAiMessageContextMenuSuppressionEnabled());
        assertTrue(config.isLineAiGalleryViewerSuppressionEnabled());
        assertTrue(config.isShoppingSuppressionEnabled());
        assertTrue(config.isAdsSuppressionEnabled());
        assertTrue(config.isLineAiSuppressionEnabled());
        assertTrue(config.isPremiumSuppressionEnabled());
        assertTrue(config.isPremiumSettingsRowSuppressionEnabled());
        assertTrue(config.isVoomSuppressionEnabled());
        assertTrue(config.isNewsSuppressionEnabled());
        assertFalse(config.isWalletSuppressionEnabled());
        assertFalse(config.isMiniSuppressionEnabled());
        assertTrue(config.isHomeRecommendationsSuppressionEnabled());
        assertTrue(config.isHomeTrendingSuppressionEnabled());
        assertTrue(config.isHomeFeedPostCardsSuppressionEnabled());
        assertTrue(config.isHomeFeaturedCollectionsSuppressionEnabled());
        assertTrue(config.isChatCalendarSuppressionEnabled());
        assertTrue(config.isChatLineGiftSuppressionEnabled());
        assertTrue(config.isChatLinePaySuppressionEnabled());
        assertEquals(ReadReceiptMode.NORMAL, config.getReadReceiptMode());
        assertFalse(config.isExternalBrowserOverrideEnabled());
        assertFalse(config.isDebugLoggingEnabled());
        assertEquals(
                LinimalConfigSchema.CURRENT_VERSION,
                backend.values.get(LinimalConfigSchema.SCHEMA_VERSION_KEY));
        assertEquals(true, backend.values.get(LinimalConfigSchema.SMART_CHANNEL_ADS_ENABLED_KEY));
        assertEquals(true, backend.values.get(LinimalConfigSchema.HOME_TOP_AD_ENABLED_KEY));
        assertEquals(true, backend.values.get(LinimalConfigSchema.AGENT_I_HOME_HEADER_ENABLED_KEY));
        assertEquals(true, backend.values.get(LinimalConfigSchema.AGENT_I_CHAT_INFORMATION_ENABLED_KEY));
        assertEquals(true, backend.values.get(LinimalConfigSchema.AGENT_I_WALLET_HEADER_ENABLED_KEY));
        assertEquals(true, backend.values.get(LinimalConfigSchema.AGENT_I_SETTINGS_ENABLED_KEY));
        assertEquals(true, backend.values.get(LinimalConfigSchema.AGENT_I_CHAT_COMPOSER_ENABLED_KEY));
        assertEquals(true, backend.values.get(LinimalConfigSchema.AGENT_I_CHAT_LIST_SEARCH_ENABLED_KEY));
        assertEquals(true, backend.values.get(LinimalConfigSchema.LINE_AI_MESSAGE_CONTEXT_MENU_ENABLED_KEY));
        assertEquals(true, backend.values.get(LinimalConfigSchema.LINE_AI_GALLERY_VIEWER_ENABLED_KEY));
        assertEquals(true, backend.values.get(LinimalConfigSchema.SHOPPING_ENABLED_KEY));
    }

    @Test
    public void v1AdsEnabledMigratesToBothAdLocations() {
        InMemoryBackend backend = v1Backend();
        backend.values.put(LinimalConfigSchema.ADS_ENABLED_KEY, true);

        LinimalConfig config = configFor(backend);

        assertTrue(config.isSmartChannelAdsSuppressionEnabled());
        assertTrue(config.isHomeTopAdSuppressionEnabled());
        assertEquals(true, backend.values.get(LinimalConfigSchema.SMART_CHANNEL_ADS_ENABLED_KEY));
        assertEquals(true, backend.values.get(LinimalConfigSchema.HOME_TOP_AD_ENABLED_KEY));
    }

    @Test
    public void v1AdsDisabledMigratesToBothAdLocations() {
        InMemoryBackend backend = v1Backend();
        backend.values.put(LinimalConfigSchema.ADS_ENABLED_KEY, false);

        LinimalConfig config = configFor(backend);

        assertFalse(config.isSmartChannelAdsSuppressionEnabled());
        assertFalse(config.isHomeTopAdSuppressionEnabled());
        assertEquals(false, backend.values.get(LinimalConfigSchema.SMART_CHANNEL_ADS_ENABLED_KEY));
        assertEquals(false, backend.values.get(LinimalConfigSchema.HOME_TOP_AD_ENABLED_KEY));
    }

    @Test
    public void v1LineAiEnabledMigratesToAgentIChatInformation() {
        InMemoryBackend backend = v1Backend();
        backend.values.put(LinimalConfigSchema.LINE_AI_ENABLED_KEY, true);

        LinimalConfig config = configFor(backend);

        assertAllAgentIStates(config, true);
        assertAllAgentIStoredValues(backend.values, true);
    }

    @Test
    public void v1LineAiDisabledMigratesToAgentIChatInformation() {
        InMemoryBackend backend = v1Backend();
        backend.values.put(LinimalConfigSchema.LINE_AI_ENABLED_KEY, false);

        LinimalConfig config = configFor(backend);

        assertAllAgentIStates(config, false);
        assertAllAgentIStoredValues(backend.values, false);
    }

    @Test
    public void v0MigratesThroughV1ToV2AndPreservesExistingValues() {
        InMemoryBackend backend = new InMemoryBackend();
        backend.values.put(LinimalConfigSchema.SCHEMA_VERSION_KEY, 0);
        backend.values.put(LinimalConfigSchema.ADS_ENABLED_KEY, false);
        backend.values.put(LinimalConfigSchema.LINE_AI_ENABLED_KEY, false);
        backend.values.put(LinimalConfigSchema.WALLET_ENABLED_KEY, true);
        backend.values.put(LinimalConfigSchema.READ_RECEIPT_MODE_KEY, "manual");

        LinimalConfig config = configFor(backend);

        assertEquals(LinimalConfigHealth.OK, config.getRuntimeHealth());
        assertFalse(config.isSmartChannelAdsSuppressionEnabled());
        assertFalse(config.isHomeTopAdSuppressionEnabled());
        assertAllAgentIStates(config, false);
        assertTrue(config.isShoppingSuppressionEnabled());
        assertTrue(config.isWalletSuppressionEnabled());
        assertEquals(ReadReceiptMode.MANUAL, config.getReadReceiptMode());
        assertEquals(2, backend.writes.size());
        assertEquals(1, backend.writes.get(0).get(LinimalConfigSchema.SCHEMA_VERSION_KEY));
        assertV2MigrationWasAtomic(backend.writes.get(1));
        assertEquals(
                LinimalConfigSchema.CURRENT_VERSION,
                backend.values.get(LinimalConfigSchema.SCHEMA_VERSION_KEY));
    }

    @Test
    public void shoppingDefaultsToEnabledWhileWalletDefaultsToDisabled() {
        LinimalConfig config = configFor(new InMemoryBackend());

        assertTrue(config.isShoppingSuppressionEnabled());
        assertFalse(config.isWalletSuppressionEnabled());
    }

    @Test
    public void homeFeedPostCardsAndPremiumSettingsRowDefaultToEnabledWithoutMigrationWrites() {
        InMemoryBackend backend = v1Backend();

        LinimalConfig config = configFor(backend);

        assertEquals(LinimalConfigHealth.OK, config.getRuntimeHealth());
        assertTrue(config.isHomeFeedPostCardsSuppressionEnabled());
        assertTrue(config.isPremiumSettingsRowSuppressionEnabled());
        assertNull(backend.values.get(LinimalConfigSchema.HOME_FEED_POST_CARDS_ENABLED_KEY));
        assertNull(backend.values.get(LinimalConfigSchema.PREMIUM_SETTINGS_ROW_ENABLED_KEY));
    }

    @Test
    public void homeFeedPostCardsAndPremiumSettingsRowAreIndependentOfTheirNeighbouringFeatures() {
        InMemoryBackend backend = new InMemoryBackend();
        LinimalConfig config = configFor(backend);

        config.setHomeFeedPostCardsSuppressionEnabled(false);
        config.setPremiumSettingsRowSuppressionEnabled(false);

        assertFalse(config.isHomeFeedPostCardsSuppressionEnabled());
        assertFalse(config.isPremiumSettingsRowSuppressionEnabled());
        assertTrue(config.isHomeRecommendationsSuppressionEnabled());
        assertTrue(config.isPremiumSuppressionEnabled());
        assertEquals(false, backend.values.get(LinimalConfigSchema.HOME_FEED_POST_CARDS_ENABLED_KEY));
        assertEquals(false, backend.values.get(LinimalConfigSchema.PREMIUM_SETTINGS_ROW_ENABLED_KEY));
        assertNull(backend.values.get(LinimalConfigSchema.HOME_RECOMMENDATIONS_ENABLED_KEY));
        assertNull(backend.values.get(LinimalConfigSchema.PREMIUM_ENABLED_KEY));

        config.setHomeRecommendationsSuppressionEnabled(false);
        config.setPremiumSuppressionEnabled(false);
        config.setSuppressionEnabled(LinimalFeature.HOME_FEED_POST_CARDS, true);
        config.setSuppressionEnabled(LinimalFeature.PREMIUM_SETTINGS_ROW, true);

        assertTrue(config.isSuppressionEnabled(LinimalFeature.HOME_FEED_POST_CARDS));
        assertTrue(config.isSuppressionEnabled(LinimalFeature.PREMIUM_SETTINGS_ROW));
        assertFalse(config.isHomeRecommendationsSuppressionEnabled());
        assertFalse(config.isPremiumSuppressionEnabled());
    }

    @Test
    public void homeFeaturedCollectionsDefaultsToEnabledAndStaysIndependentOfTheOtherHomeRows() {
        InMemoryBackend backend = v1Backend();

        LinimalConfig config = configFor(backend);

        assertEquals(LinimalConfigHealth.OK, config.getRuntimeHealth());
        assertTrue(config.isHomeFeaturedCollectionsSuppressionEnabled());
        assertNull(backend.values.get(LinimalConfigSchema.HOME_FEATURED_COLLECTIONS_ENABLED_KEY));

        config.setHomeFeaturedCollectionsSuppressionEnabled(false);

        assertFalse(config.isHomeFeaturedCollectionsSuppressionEnabled());
        assertEquals(false, backend.values.get(LinimalConfigSchema.HOME_FEATURED_COLLECTIONS_ENABLED_KEY));
        assertTrue(config.isHomeFeedPostCardsSuppressionEnabled());
        assertTrue(config.isHomeTrendingSuppressionEnabled());
        assertTrue(config.isHomeRecommendationsSuppressionEnabled());

        config.setSuppressionEnabled(LinimalFeature.HOME_FEATURED_COLLECTIONS, true);

        assertTrue(config.isSuppressionEnabled(LinimalFeature.HOME_FEATURED_COLLECTIONS));
    }

    @Test
    public void existingValidV2ValuesTakePrecedenceOverLegacyMigrationSources() {
        InMemoryBackend backend = v1Backend();
        backend.values.put(LinimalConfigSchema.ADS_ENABLED_KEY, false);
        backend.values.put(LinimalConfigSchema.LINE_AI_ENABLED_KEY, false);
        backend.values.put(LinimalConfigSchema.SMART_CHANNEL_ADS_ENABLED_KEY, true);
        backend.values.put(LinimalConfigSchema.HOME_TOP_AD_ENABLED_KEY, true);
        backend.values.put(LinimalConfigSchema.AGENT_I_HOME_HEADER_ENABLED_KEY, false);
        backend.values.put(LinimalConfigSchema.AGENT_I_CHAT_INFORMATION_ENABLED_KEY, true);
        backend.values.put(LinimalConfigSchema.LINE_AI_GALLERY_VIEWER_ENABLED_KEY, false);
        backend.values.put(LinimalConfigSchema.SHOPPING_ENABLED_KEY, false);

        LinimalConfig config = configFor(backend);

        assertTrue(config.isSmartChannelAdsSuppressionEnabled());
        assertTrue(config.isHomeTopAdSuppressionEnabled());
        assertFalse(config.isAgentIHomeHeaderSuppressionEnabled());
        assertTrue(config.isAgentIChatInformationSuppressionEnabled());
        assertFalse(config.isLineAiGalleryViewerSuppressionEnabled());
        assertFalse(config.isShoppingSuppressionEnabled());
    }

    @Test
    public void invalidLegacyMigrationValueFailsOpenWithoutAdvancingSchema() {
        InMemoryBackend backend = v1Backend();
        backend.values.put(LinimalConfigSchema.ADS_ENABLED_KEY, "true");

        LinimalConfig config = configFor(backend);

        assertEquals(LinimalConfigHealth.ERROR, config.getRuntimeHealth());
        assertFalse(config.isSmartChannelAdsSuppressionEnabled());
        assertEquals(1, backend.values.get(LinimalConfigSchema.SCHEMA_VERSION_KEY));
        assertNull(backend.values.get(LinimalConfigSchema.SMART_CHANNEL_ADS_ENABLED_KEY));
        assertNull(backend.values.get(LinimalConfigSchema.HOME_TOP_AD_ENABLED_KEY));
    }

    @Test
    public void invalidNewMigrationValueFailsOpenWithoutAdvancingSchema() {
        InMemoryBackend backend = v1Backend();
        backend.values.put(LinimalConfigSchema.SMART_CHANNEL_ADS_ENABLED_KEY, "true");

        LinimalConfig config = configFor(backend);

        assertEquals(LinimalConfigHealth.ERROR, config.getRuntimeHealth());
        assertFalse(config.isSmartChannelAdsSuppressionEnabled());
        assertEquals(1, backend.values.get(LinimalConfigSchema.SCHEMA_VERSION_KEY));
        assertNull(backend.values.get(LinimalConfigSchema.HOME_TOP_AD_ENABLED_KEY));
    }

    @Test
    public void v2MigrationWriteFailureDoesNotAdvanceSchema() {
        FailingWriteBackend backend = new FailingWriteBackend();
        backend.values.put(LinimalConfigSchema.SCHEMA_VERSION_KEY, 1);
        backend.values.put(LinimalConfigSchema.ADS_ENABLED_KEY, true);

        LinimalConfig config = configFor(backend);

        assertEquals(LinimalConfigHealth.ERROR, config.getRuntimeHealth());
        assertFalse(config.isSmartChannelAdsSuppressionEnabled());
        assertEquals(1, backend.values.get(LinimalConfigSchema.SCHEMA_VERSION_KEY));
        assertNull(backend.values.get(LinimalConfigSchema.SMART_CHANNEL_ADS_ENABLED_KEY));
    }

    @Test
    public void migrationWithoutPersistedProgressFailsOpenInsteadOfRetryingForever() {
        IgnoringWriteBackend backend = new IgnoringWriteBackend();
        backend.values.put(LinimalConfigSchema.SCHEMA_VERSION_KEY, 1);

        LinimalConfig config = configFor(backend);

        assertEquals(LinimalConfigHealth.ERROR, config.getRuntimeHealth());
        assertFalse(config.isSmartChannelAdsSuppressionEnabled());
        assertEquals(1, backend.writeCount);
        assertEquals(1, backend.values.get(LinimalConfigSchema.SCHEMA_VERSION_KEY));
    }

    @Test
    public void invalidReadReceiptModeFailsOpen() {
        InMemoryBackend backend = new InMemoryBackend();
        backend.values.put(LinimalConfigSchema.READ_RECEIPT_MODE_KEY, "automatic");

        LinimalConfig config = configFor(backend);

        assertEquals(LinimalConfigHealth.ERROR, config.getRuntimeHealth());
        assertFalse(config.isAgentIChatInformationSuppressionEnabled());
        assertEquals(ReadReceiptMode.NORMAL, config.getReadReceiptMode());
    }

    @Test
    public void invalidSchemaTypeFailsOpenWithoutOverwritingIt() {
        InMemoryBackend backend = new InMemoryBackend();
        backend.values.put(LinimalConfigSchema.SCHEMA_VERSION_KEY, "2");

        LinimalConfig config = configFor(backend);

        assertEquals(LinimalConfigHealth.ERROR, config.getRuntimeHealth());
        assertFalse(config.isSmartChannelAdsSuppressionEnabled());
        assertEquals("2", backend.values.get(LinimalConfigSchema.SCHEMA_VERSION_KEY));
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
    public void semanticAndGenericWritesPersistAndRefreshTheTypedSnapshot() {
        InMemoryBackend backend = new InMemoryBackend();
        LinimalConfig config = configFor(backend);

        config.setSmartChannelAdsSuppressionEnabled(false);
        config.setHomeTopAdSuppressionEnabled(false);
        config.setAgentIHomeHeaderSuppressionEnabled(false);
        config.setAgentIChatInformationSuppressionEnabled(false);
        config.setAgentIWalletHeaderSuppressionEnabled(false);
        config.setAgentISettingsSuppressionEnabled(false);
        config.setAgentIChatComposerSuppressionEnabled(false);
        config.setAgentIChatListSearchSuppressionEnabled(false);
        config.setLineAiMessageContextMenuSuppressionEnabled(false);
        config.setLineAiGalleryViewerSuppressionEnabled(false);
        config.setShoppingSuppressionEnabled(false);

        config.setSuppressionEnabled(LinimalFeature.SMART_CHANNEL_ADS, true);
        config.setSuppressionEnabled(LinimalFeature.HOME_TOP_AD, true);
        config.setSuppressionEnabled(LinimalFeature.AGENT_I_HOME_HEADER, true);
        config.setSuppressionEnabled(LinimalFeature.AGENT_I_CHAT_INFORMATION, true);
        config.setSuppressionEnabled(LinimalFeature.AGENT_I_WALLET_HEADER, true);
        config.setSuppressionEnabled(LinimalFeature.AGENT_I_SETTINGS, true);
        config.setSuppressionEnabled(LinimalFeature.AGENT_I_CHAT_COMPOSER, true);
        config.setSuppressionEnabled(LinimalFeature.AGENT_I_CHAT_LIST_SEARCH, true);
        config.setSuppressionEnabled(LinimalFeature.LINE_AI_MESSAGE_CONTEXT_MENU, true);
        config.setSuppressionEnabled(LinimalFeature.LINE_AI_GALLERY_VIEWER, true);
        config.setSuppressionEnabled(LinimalFeature.SHOPPING, true);

        assertTrue(config.isSmartChannelAdsSuppressionEnabled());
        assertTrue(config.isHomeTopAdSuppressionEnabled());
        assertAllAgentIStates(config, true);
        assertTrue(config.isShoppingSuppressionEnabled());
        assertTrue(config.isSuppressionEnabled(LinimalFeature.SMART_CHANNEL_ADS));
        assertTrue(config.isSuppressionEnabled(LinimalFeature.HOME_TOP_AD));
        assertTrue(config.isSuppressionEnabled(LinimalFeature.AGENT_I_HOME_HEADER));
        assertTrue(config.isSuppressionEnabled(LinimalFeature.AGENT_I_CHAT_INFORMATION));
        assertTrue(config.isSuppressionEnabled(LinimalFeature.AGENT_I_WALLET_HEADER));
        assertTrue(config.isSuppressionEnabled(LinimalFeature.AGENT_I_SETTINGS));
        assertTrue(config.isSuppressionEnabled(LinimalFeature.AGENT_I_CHAT_COMPOSER));
        assertTrue(config.isSuppressionEnabled(LinimalFeature.AGENT_I_CHAT_LIST_SEARCH));
        assertTrue(config.isSuppressionEnabled(LinimalFeature.LINE_AI_MESSAGE_CONTEXT_MENU));
        assertTrue(config.isSuppressionEnabled(LinimalFeature.LINE_AI_GALLERY_VIEWER));
        assertTrue(config.isSuppressionEnabled(LinimalFeature.SHOPPING));
        assertEquals(true, backend.values.get(LinimalConfigSchema.SMART_CHANNEL_ADS_ENABLED_KEY));
        assertEquals(true, backend.values.get(LinimalConfigSchema.HOME_TOP_AD_ENABLED_KEY));
        assertAllAgentIStoredValues(backend.values, true);
        assertEquals(true, backend.values.get(LinimalConfigSchema.SHOPPING_ENABLED_KEY));
    }

    @Test
    public void existingSemanticWritesPersistAndRefreshTheTypedSnapshot() {
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
    public void miniWritesUseTheirOwnKeyAndDoNotChangeTheOtherTabs() {
        InMemoryBackend backend = new InMemoryBackend();
        LinimalConfig config = configFor(backend);

        config.setMiniSuppressionEnabled(true);

        assertEquals(LinimalConfigHealth.OK, config.getRuntimeHealth());
        assertTrue(config.isMiniSuppressionEnabled());
        assertFalse(config.isWalletSuppressionEnabled());
        assertTrue(config.isVoomSuppressionEnabled());
        assertTrue(config.isNewsSuppressionEnabled());
        assertTrue(config.isShoppingSuppressionEnabled());
        assertEquals(true, backend.values.get(LinimalConfigSchema.MINI_ENABLED_KEY));
    }

    @Test
    public void genericSuppressionAccessUsesTheSpecifiedExistingFeature() {
        InMemoryBackend backend = new InMemoryBackend();
        LinimalConfig config = configFor(backend);

        config.setSuppressionEnabled(LinimalFeature.PREMIUM, false);
        config.setSuppressionEnabled(LinimalFeature.WALLET, true);

        assertFalse(config.isSuppressionEnabled(LinimalFeature.PREMIUM));
        assertTrue(config.isSuppressionEnabled(LinimalFeature.WALLET));
        assertEquals(false, backend.values.get(LinimalConfigSchema.PREMIUM_ENABLED_KEY));
        assertEquals(true, backend.values.get(LinimalConfigSchema.WALLET_ENABLED_KEY));
    }

    @Test
    public void legacySemanticAccessorsDelegateToReplacementFeatures() {
        InMemoryBackend backend = new InMemoryBackend();
        LinimalConfig config = configFor(backend);

        config.setSmartChannelAdsSuppressionEnabled(false);
        config.setHomeTopAdSuppressionEnabled(false);
        config.setAgentIHomeHeaderSuppressionEnabled(false);
        config.setAgentIChatInformationSuppressionEnabled(false);

        assertFalse(config.isAdsSuppressionEnabled());
        assertFalse(config.isLineAiSuppressionEnabled());

        config.setAdsSuppressionEnabled(true);
        config.setLineAiSuppressionEnabled(true);

        assertTrue(config.isSmartChannelAdsSuppressionEnabled());
        assertFalse(config.isHomeTopAdSuppressionEnabled());
        assertFalse(config.isAgentIHomeHeaderSuppressionEnabled());
        assertTrue(config.isAgentIChatInformationSuppressionEnabled());
        assertTrue(config.isAdsSuppressionEnabled());
        assertTrue(config.isLineAiSuppressionEnabled());
        assertNull(backend.values.get(LinimalConfigSchema.ADS_ENABLED_KEY));
        assertNull(backend.values.get(LinimalConfigSchema.LINE_AI_ENABLED_KEY));
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

    private static InMemoryBackend v1Backend() {
        InMemoryBackend backend = new InMemoryBackend();
        backend.values.put(LinimalConfigSchema.SCHEMA_VERSION_KEY, 1);
        return backend;
    }

    private static void assertAllAgentIStates(LinimalConfig config, boolean expected) {
        assertEquals(expected, config.isAgentIHomeHeaderSuppressionEnabled());
        assertEquals(expected, config.isAgentIChatInformationSuppressionEnabled());
        assertEquals(expected, config.isAgentIWalletHeaderSuppressionEnabled());
        assertEquals(expected, config.isAgentISettingsSuppressionEnabled());
        assertEquals(expected, config.isAgentIChatComposerSuppressionEnabled());
        assertEquals(expected, config.isAgentIChatListSearchSuppressionEnabled());
        assertEquals(expected, config.isLineAiMessageContextMenuSuppressionEnabled());
        assertEquals(expected, config.isLineAiGalleryViewerSuppressionEnabled());
    }

    private static void assertAllAgentIStoredValues(Map<String, Object> values, boolean expected) {
        assertEquals(expected, values.get(LinimalConfigSchema.AGENT_I_HOME_HEADER_ENABLED_KEY));
        assertEquals(expected, values.get(LinimalConfigSchema.AGENT_I_CHAT_INFORMATION_ENABLED_KEY));
        assertEquals(expected, values.get(LinimalConfigSchema.AGENT_I_WALLET_HEADER_ENABLED_KEY));
        assertEquals(expected, values.get(LinimalConfigSchema.AGENT_I_SETTINGS_ENABLED_KEY));
        assertEquals(expected, values.get(LinimalConfigSchema.AGENT_I_CHAT_COMPOSER_ENABLED_KEY));
        assertEquals(expected, values.get(LinimalConfigSchema.AGENT_I_CHAT_LIST_SEARCH_ENABLED_KEY));
        assertEquals(expected, values.get(LinimalConfigSchema.LINE_AI_MESSAGE_CONTEXT_MENU_ENABLED_KEY));
        assertEquals(expected, values.get(LinimalConfigSchema.LINE_AI_GALLERY_VIEWER_ENABLED_KEY));
    }

    private static void assertV2MigrationWasAtomic(Map<String, Object> updates) {
        assertEquals(12, updates.size());
        assertTrue(updates.containsKey(LinimalConfigSchema.SMART_CHANNEL_ADS_ENABLED_KEY));
        assertTrue(updates.containsKey(LinimalConfigSchema.HOME_TOP_AD_ENABLED_KEY));
        assertTrue(updates.containsKey(LinimalConfigSchema.AGENT_I_HOME_HEADER_ENABLED_KEY));
        assertTrue(updates.containsKey(LinimalConfigSchema.AGENT_I_CHAT_INFORMATION_ENABLED_KEY));
        assertTrue(updates.containsKey(LinimalConfigSchema.AGENT_I_WALLET_HEADER_ENABLED_KEY));
        assertTrue(updates.containsKey(LinimalConfigSchema.AGENT_I_SETTINGS_ENABLED_KEY));
        assertTrue(updates.containsKey(LinimalConfigSchema.AGENT_I_CHAT_COMPOSER_ENABLED_KEY));
        assertTrue(updates.containsKey(LinimalConfigSchema.AGENT_I_CHAT_LIST_SEARCH_ENABLED_KEY));
        assertTrue(updates.containsKey(LinimalConfigSchema.LINE_AI_MESSAGE_CONTEXT_MENU_ENABLED_KEY));
        assertTrue(updates.containsKey(LinimalConfigSchema.LINE_AI_GALLERY_VIEWER_ENABLED_KEY));
        assertTrue(updates.containsKey(LinimalConfigSchema.SHOPPING_ENABLED_KEY));
        assertEquals(2, updates.get(LinimalConfigSchema.SCHEMA_VERSION_KEY));
    }

    private static LinimalConfig configFor(LinimalConfigStore.PreferenceBackend backend) {
        return LinimalConfig.fromStoreForTesting(LinimalConfigStore.forTesting(backend));
    }

    private static final class InMemoryBackend implements LinimalConfigStore.PreferenceBackend {
        private final Map<String, Object> values = new HashMap<>();
        private final List<Map<String, Object>> writes = new ArrayList<>();

        @Override
        public Map<String, ?> readAll() {
            return Collections.unmodifiableMap(new HashMap<>(values));
        }

        @Override
        public boolean write(Map<String, Object> updates) {
            writes.add(new HashMap<>(updates));
            values.putAll(updates);
            return true;
        }
    }

    private static final class FailingWriteBackend implements LinimalConfigStore.PreferenceBackend {
        private final Map<String, Object> values = new HashMap<>();

        @Override
        public Map<String, ?> readAll() {
            return Collections.unmodifiableMap(new HashMap<>(values));
        }

        @Override
        public boolean write(Map<String, Object> updates) {
            return false;
        }
    }

    private static final class IgnoringWriteBackend implements LinimalConfigStore.PreferenceBackend {
        private final Map<String, Object> values = new HashMap<>();
        private int writeCount;

        @Override
        public Map<String, ?> readAll() {
            return Collections.unmodifiableMap(new HashMap<>(values));
        }

        @Override
        public boolean write(Map<String, Object> updates) {
            writeCount++;
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
