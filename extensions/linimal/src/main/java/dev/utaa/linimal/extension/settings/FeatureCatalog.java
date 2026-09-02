package dev.utaa.linimal.extension.settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import dev.utaa.linimal.extension.config.LinimalFeature;

/**
 * 設定画面に並べる機能の定義です。
 *
 * <p>各項目は {@link LinimalFeature} が持つ build-time の feature ID と対応づけます。
 * patch-status に ID がない機能は、設定画面に表示しません。このクラスは Android API に
 * 依存しないため、表示対象の判断を local JVM test で検証できます。</p>
 */
public final class FeatureCatalog {
    public static final class Entry {
        private final LinimalFeature feature;
        private final SettingsPage page;
        private final String title;
        private final String summary;

        Entry(
                LinimalFeature feature,
                SettingsPage page,
                String title,
                String summary) {
            this.feature = feature;
            this.page = page;
            this.title = title;
            this.summary = summary;
        }

        public LinimalFeature getFeature() {
            return feature;
        }

        /** patch-status の feature ID。存在しない機能は設定画面に出しません。 */
        public String getFeatureId() {
            return feature.getFeatureId();
        }

        /** この機能を表示する設定ページです。 */
        public SettingsPage getPage() {
            return page;
        }

        public String getTitle() {
            return title;
        }

        public String getSummary() {
            return summary;
        }
    }

    private static final List<Entry> ENTRIES = Collections.unmodifiableList(Arrays.asList(
            new Entry(LinimalFeature.PREMIUM, SettingsPage.GENERAL,
                    "Premium の案内を表示しない",
                    "送信取消時に出る LINE Premium の案内を表示しません。"),
            new Entry(LinimalFeature.PREMIUM_SETTINGS_ROW, SettingsPage.GENERAL,
                    "設定のプレミアムを表示しない",
                    "LINE の設定画面にある LINE Premium の行を表示しません。"),
            new Entry(LinimalFeature.EXTERNAL_BROWSER, SettingsPage.GENERAL,
                    "リンクを外部ブラウザで開く",
                    "通常の http/https のみ外部ブラウザへ渡します。ログインや決済は元のまま開きます。"),

            new Entry(LinimalFeature.AGENT_I_HOME_HEADER, SettingsPage.AGENT_I,
                    "ホーム上部の Agent i を表示しない",
                    "ホーム画面上部にある Agent i の入口を表示しません。"),
            new Entry(LinimalFeature.AGENT_I_CHAT_LIST_SEARCH, SettingsPage.AGENT_I,
                    "トーク一覧の検索欄の Agent i を表示しない",
                    "トーク一覧上部の検索欄にある Agent i の入口を表示しません。"),
            new Entry(LinimalFeature.AGENT_I_CHAT_INFORMATION, SettingsPage.AGENT_I,
                    "チャット情報の Agent i を表示しない",
                    "チャット情報画面にある Agent i の入口を表示しません。"),
            new Entry(LinimalFeature.AGENT_I_WALLET_HEADER, SettingsPage.AGENT_I,
                    "ウォレット上部の Agent i を表示しない",
                    "ウォレット画面上部にある Agent i の入口を表示しません。"),
            new Entry(LinimalFeature.AGENT_I_SETTINGS, SettingsPage.AGENT_I,
                    "設定画面の Agent i を表示しない",
                    "LINE の設定画面にある Agent i の入口を表示しません。"),
            new Entry(LinimalFeature.AGENT_I_CHAT_COMPOSER, SettingsPage.AGENT_I,
                    "チャット入力欄の Agent i を表示しない",
                    "チャット入力欄にある Agent i の入口を表示しません。"),
            new Entry(LinimalFeature.LINE_AI_MESSAGE_CONTEXT_MENU, SettingsPage.AGENT_I,
                    "メッセージ長押しメニューの LINE AI を表示しない",
                    "メッセージの長押しメニューにある画像編集用の LINE AI を表示しません。"),
            new Entry(LinimalFeature.LINE_AI_GALLERY_VIEWER, SettingsPage.AGENT_I,
                    "写真・動画表示画面の LINE AI を表示しない",
                    "チャットの写真・動画表示画面にある LINE AI の入口を表示しません。"),

            new Entry(LinimalFeature.VOOM, SettingsPage.TABS,
                    "VOOM を表示しない",
                    "下部タブから VOOM を取り除きます。"),
            new Entry(LinimalFeature.SHOPPING, SettingsPage.TABS,
                    "ショッピングを表示しない",
                    "下部タブからショッピングを取り除きます。"),
            new Entry(LinimalFeature.NEWS, SettingsPage.TABS,
                    "ニュースを表示しない",
                    "下部タブからニュースを取り除きます。"),
            new Entry(LinimalFeature.WALLET, SettingsPage.TABS,
                    "ウォレットを表示しない",
                    "下部タブからウォレットを取り除きます。"),
            new Entry(LinimalFeature.MINI, SettingsPage.TABS,
                    "アプリを表示しない",
                    "下部タブからアプリを取り除きます。"),

            new Entry(LinimalFeature.HOME_TOP_AD, SettingsPage.HOME,
                    "ホーム内の広告を表示しない",
                    "ホーム画面内にある Performance Ad とフィード広告を表示しません。"),
            new Entry(LinimalFeature.HOME_RECOMMENDATIONS, SettingsPage.HOME,
                    "おすすめを表示しない",
                    "ホームのおすすめ枠を表示しません。"),
            new Entry(LinimalFeature.HOME_TRENDING, SettingsPage.HOME,
                    "話題を表示しない",
                    "ホームの話題・トレンド枠を表示しません。"),
            new Entry(LinimalFeature.HOME_FEED_POST_CARDS, SettingsPage.HOME,
                    "ホームの投稿カードを表示しない",
                    "ホーム下部にある投稿カードを表示しません。"),
            new Entry(LinimalFeature.HOME_FEATURED_COLLECTIONS, SettingsPage.HOME,
                    "特集枠を表示しない",
                    "ホームの特集枠にある動画のグリッドを表示しません。"),

            new Entry(LinimalFeature.SMART_CHANNEL_ADS, SettingsPage.CHAT,
                    "Smart Channel の広告を表示しない",
                    "チャット一覧の Smart Channel にある広告を表示しません。"),
            new Entry(LinimalFeature.CHAT_CALENDAR, SettingsPage.CHAT,
                    "カレンダーを表示しない",
                    "チャットの + メニューからカレンダーを取り除きます。"),
            new Entry(LinimalFeature.CHAT_LINE_GIFT, SettingsPage.CHAT,
                    "LINE ギフトを表示しない",
                    "チャットの + メニューから LINE ギフトを取り除きます。"),
            new Entry(LinimalFeature.CHAT_LINE_PAY, SettingsPage.CHAT,
                    "LINE Pay を表示しない",
                    "チャットの + メニューから LINE Pay を取り除きます。"),
            new Entry(LinimalFeature.READ_WITHOUT_RECEIPT, SettingsPage.CHAT,
                    "既読をつけずに読むをメニューに追加",
                    "トーク一覧の長押しメニューに追加します。そこから開いたトークは、開いている間だけ既読の送信を止め、"
                            + "トーク一覧の未読表示もそのまま残します。"),
            new Entry(LinimalFeature.CHAT_LIST_HEADER_AI_FRIENDS, SettingsPage.CHAT,
                    "トーク一覧の AI Friends を表示しない",
                    "トーク一覧の上部にある AI Friends のアイコンを表示しません。"),
            new Entry(LinimalFeature.CHAT_LIST_HEADER_CALENDAR, SettingsPage.CHAT,
                    "トーク一覧のカレンダーを表示しない",
                    "トーク一覧の上部にあるカレンダーのアイコンを表示しません。"),
            new Entry(LinimalFeature.CHAT_LIST_HEADER_OPEN_CHAT, SettingsPage.CHAT,
                    "トーク一覧のオープンチャットを表示しない",
                    "トーク一覧の上部にあるオープンチャットのアイコンを表示しません。")));

    private FeatureCatalog() {
    }

    public static List<Entry> entries() {
        return ENTRIES;
    }

    /**
     * 指定ページに属し、かつ patch-status に記録された feature ID を持つ項目だけを返します。
     * ROOT と Patch Status は設定項目を直接持たないため空です。
     */
    public static List<Entry> installedEntriesForPage(
            SettingsPage page, List<String> installedFeatureIds) {
        if (page == null || page == SettingsPage.ROOT || page == SettingsPage.PATCH_STATUS) {
            return Collections.emptyList();
        }
        List<Entry> installed = new ArrayList<>();
        if (installedFeatureIds == null) {
            return installed;
        }
        for (Entry entry : ENTRIES) {
            if (entry.getPage() == page && installedFeatureIds.contains(entry.getFeatureId())) {
                installed.add(entry);
            }
        }
        return installed;
    }
}
