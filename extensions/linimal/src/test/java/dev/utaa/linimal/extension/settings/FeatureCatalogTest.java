package dev.utaa.linimal.extension.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class FeatureCatalogTest {
    @Test
    public void installedEntriesFiltersUnknownIdsAndKeepsCatalogOrder() {
        List<FeatureCatalog.Entry> entries = FeatureCatalog.installedEntries(Arrays.asList(
                "linimal.wallet",
                "unknown.feature",
                "linimal.premium",
                "linimal.wallet"));

        assertEquals(2, entries.size());
        assertEquals("linimal.premium", entries.get(0).getFeatureId());
        assertEquals(SettingsPage.GENERAL, entries.get(0).getPage());
        assertEquals("linimal.wallet", entries.get(1).getFeatureId());
        assertEquals(SettingsPage.TABS, entries.get(1).getPage());
    }

    @Test
    public void installedEntriesReturnsNoSettingsForMissingFeatureIds() {
        assertTrue(FeatureCatalog.installedEntries(null).isEmpty());
        assertTrue(FeatureCatalog.installedEntries(Collections.singletonList("unknown.feature")).isEmpty());
    }

    @Test
    public void installedEntriesForPageFiltersByPageAndKeepsCatalogOrder() {
        List<FeatureCatalog.Entry> entries = FeatureCatalog.installedEntriesForPage(
                SettingsPage.AGENT_I,
                Arrays.asList(
                        "linimal.line-ai-gallery-viewer",
                        "linimal.agent-i-home-header",
                        "linimal.premium",
                        "linimal.agent-i-chat-information"));

        assertEquals(3, entries.size());
        assertEquals("linimal.agent-i-home-header", entries.get(0).getFeatureId());
        assertEquals("linimal.agent-i-chat-information", entries.get(1).getFeatureId());
        assertEquals("linimal.line-ai-gallery-viewer", entries.get(2).getFeatureId());
        assertEquals(SettingsPage.AGENT_I, entries.get(0).getPage());
    }

    @Test
    public void catalogPlacesEveryConfigurableFeatureOnItsOwnSettingsPage() {
        assertEntryPage("linimal.premium", SettingsPage.GENERAL);
        assertEntryPage("linimal.premium-settings-row", SettingsPage.GENERAL);
        assertEntryPage("linimal.external-browser", SettingsPage.GENERAL);
        assertEntryPage("linimal.shopping", SettingsPage.TABS);
        assertEntryPage("linimal.wallet", SettingsPage.TABS);
        assertEntryPage("linimal.mini", SettingsPage.TABS);
        assertEntryPage("linimal.home-top-ad", SettingsPage.HOME);
        assertEntryPage("linimal.home-feed-post-cards", SettingsPage.HOME);
        assertEntryPage("linimal.home-featured-collections", SettingsPage.HOME);
        assertEntryPage("linimal.smart-channel-ads", SettingsPage.CHAT);
        assertEntryPage("linimal.chat-list-header-ai-friends", SettingsPage.CHAT);
        assertEntryPage("linimal.chat-list-header-calendar", SettingsPage.CHAT);
        assertEntryPage("linimal.chat-list-header-open-chat", SettingsPage.CHAT);
        assertEntryPage("linimal.agent-i-chat-list-search", SettingsPage.AGENT_I);
    }

    @Test
    public void agentIEntriesAreEightIndependentLocationSpecificRows() {
        List<FeatureCatalog.Entry> entries = FeatureCatalog.installedEntriesForPage(
                SettingsPage.AGENT_I,
                Arrays.asList(
                        "linimal.line-ai-gallery-viewer",
                        "linimal.line-ai-message-context-menu",
                        "linimal.agent-i-chat-composer",
                        "linimal.agent-i-settings",
                        "linimal.agent-i-wallet-header",
                        "linimal.agent-i-chat-information",
                        "linimal.agent-i-chat-list-search",
                        "linimal.agent-i-home-header"));

        assertEquals(8, entries.size());
        assertFeatureIds(entries,
                "linimal.agent-i-home-header",
                "linimal.agent-i-chat-list-search",
                "linimal.agent-i-chat-information",
                "linimal.agent-i-wallet-header",
                "linimal.agent-i-settings",
                "linimal.agent-i-chat-composer",
                "linimal.line-ai-message-context-menu",
                "linimal.line-ai-gallery-viewer");
        assertEquals("トーク一覧の検索欄の Agent i を表示しない", entries.get(1).getTitle());
        assertEquals("メッセージ長押しメニューの LINE AI を表示しない", entries.get(6).getTitle());
        assertEquals("写真・動画表示画面の LINE AI を表示しない", entries.get(7).getTitle());
    }

    @Test
    public void homeEntriesKeepCatalogOrderAndIncludeTheFeedPostCardAndFeaturedRows() {
        List<FeatureCatalog.Entry> entries = FeatureCatalog.installedEntriesForPage(
                SettingsPage.HOME,
                Arrays.asList(
                        "linimal.home-featured-collections",
                        "linimal.home-feed-post-cards",
                        "linimal.home-trending",
                        "linimal.home-recommendations",
                        "linimal.home-top-ad"));

        assertFeatureIds(entries,
                "linimal.home-top-ad",
                "linimal.home-recommendations",
                "linimal.home-trending",
                "linimal.home-feed-post-cards",
                "linimal.home-featured-collections");
        assertEquals("ホームの投稿カードを表示しない", entries.get(3).getTitle());
        assertEquals("特集枠を表示しない", entries.get(4).getTitle());
    }

    @Test
    public void tabEntriesKeepCatalogOrderAndIncludeTheMiniRow() {
        List<FeatureCatalog.Entry> entries = FeatureCatalog.installedEntriesForPage(
                SettingsPage.TABS,
                Arrays.asList(
                        "linimal.mini",
                        "linimal.wallet",
                        "linimal.news",
                        "linimal.shopping",
                        "linimal.voom"));

        assertFeatureIds(entries,
                "linimal.voom",
                "linimal.shopping",
                "linimal.news",
                "linimal.wallet",
                "linimal.mini");
        assertEquals("アプリを表示しない", entries.get(4).getTitle());
        assertEquals("下部タブからアプリを取り除きます。", entries.get(4).getSummary());
    }

    @Test
    public void chatEntriesKeepCatalogOrderAndDistinguishTheChatListHeaderCalendar() {
        List<FeatureCatalog.Entry> entries = FeatureCatalog.installedEntriesForPage(
                SettingsPage.CHAT,
                Arrays.asList(
                        "linimal.chat-list-header-open-chat",
                        "linimal.chat-list-header-calendar",
                        "linimal.chat-list-header-ai-friends",
                        "linimal.chat-calendar",
                        "linimal.smart-channel-ads"));

        assertFeatureIds(entries,
                "linimal.smart-channel-ads",
                "linimal.chat-calendar",
                "linimal.chat-list-header-ai-friends",
                "linimal.chat-list-header-calendar",
                "linimal.chat-list-header-open-chat");
        // チャットの + メニューのカレンダーと紛らわしいため、頭に「トーク一覧の」を付けて区別します。
        assertEquals("カレンダーを表示しない", entries.get(1).getTitle());
        assertEquals("トーク一覧の AI Friends を表示しない", entries.get(2).getTitle());
        assertEquals("トーク一覧のカレンダーを表示しない", entries.get(3).getTitle());
        assertEquals("トーク一覧のオープンチャットを表示しない", entries.get(4).getTitle());
        assertEquals("トーク一覧の上部にあるオープンチャットのアイコンを表示しません。", entries.get(4).getSummary());
    }

    @Test
    public void generalEntriesSeparateThePremiumPromotionFromTheSettingsRow() {
        List<FeatureCatalog.Entry> entries = FeatureCatalog.installedEntriesForPage(
                SettingsPage.GENERAL,
                Arrays.asList(
                        "linimal.premium-settings-row",
                        "linimal.external-browser",
                        "linimal.premium"));

        assertFeatureIds(entries,
                "linimal.premium",
                "linimal.premium-settings-row",
                "linimal.external-browser");
        assertEquals("設定のプレミアムを表示しない", entries.get(1).getTitle());
    }

    @Test
    public void installedEntriesForPageRejectsRootAndUnknownIds() {
        assertTrue(FeatureCatalog.installedEntriesForPage(
                SettingsPage.ROOT, Collections.singletonList("linimal.premium")).isEmpty());
        assertTrue(FeatureCatalog.installedEntriesForPage(
                null, Collections.singletonList("linimal.premium")).isEmpty());
    }

    private static void assertFeatureIds(List<FeatureCatalog.Entry> entries, String... expectedIds) {
        assertEquals(expectedIds.length, entries.size());
        for (int index = 0; index < expectedIds.length; index++) {
            assertEquals(expectedIds[index], entries.get(index).getFeatureId());
        }
    }

    private static void assertEntryPage(String featureId, SettingsPage expectedPage) {
        for (FeatureCatalog.Entry entry : FeatureCatalog.entries()) {
            if (featureId.equals(entry.getFeatureId())) {
                assertEquals(expectedPage, entry.getPage());
                return;
            }
        }
        throw new AssertionError("Catalog entry was not found: " + featureId);
    }
}
