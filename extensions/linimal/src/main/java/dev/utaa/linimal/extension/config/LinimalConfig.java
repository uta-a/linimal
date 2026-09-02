package dev.utaa.linimal.extension.config;

/**
 * Linimal hook 用の型付き設定の入口。
 *
 * <p>各 boolean は、対応する Linimal の動作が有効かどうかを示します。このクラスが初期化されて
 * いない場合、storage を読み取れない場合、または保存済みデータが無効な場合、すべての hook は
 * LINE の元の動作にフォールバックします。</p>
 */
public final class LinimalConfig {
    private static volatile LinimalConfig shared = unavailable();

    private final LinimalConfigStore store;
    private volatile ConfigSnapshot snapshot;
    private volatile LinimalConfigHealth health;

    private LinimalConfig(LinimalConfigStore store) {
        this.store = store;
        this.snapshot = ConfigSnapshot.originalBehavior();
        this.health = LinimalConfigHealth.ERROR;
    }

    /** internal bootstrap boundary からプロセスローカルな config instance を初期化します。 */
    static synchronized void initialize(android.content.Context context) {
        try {
            LinimalConfig config = new LinimalConfig(LinimalConfigStore.open(context));
            config.reload();
            shared = config;
        } catch (RuntimeException exception) {
            shared = unavailable();
        }
    }

    /** プロセスローカルな設定を返します。初期化前は fail-open です。 */
    public static LinimalConfig get() {
        return shared;
    }

    /** 保存済みの値を再読み込みします。読み込みに失敗すると元の動作を復元します。 */
    synchronized void reload() {
        if (store == null) {
            failOpen();
            return;
        }
        try {
            snapshot = store.readSnapshot();
            health = LinimalConfigHealth.OK;
        } catch (RuntimeException exception) {
            failOpen();
        }
    }

    /** 検証済みの保存設定が hook から利用可能かどうかを返します。 */
    public LinimalConfigHealth getRuntimeHealth() {
        return health;
    }

    public boolean isSmartChannelAdsSuppressionEnabled() {
        return isEnabled(LinimalFeature.SMART_CHANNEL_ADS);
    }

    public boolean isHomeTopAdSuppressionEnabled() {
        return isEnabled(LinimalFeature.HOME_TOP_AD);
    }

    public boolean isAgentIHomeHeaderSuppressionEnabled() {
        return isEnabled(LinimalFeature.AGENT_I_HOME_HEADER);
    }

    public boolean isAgentIChatInformationSuppressionEnabled() {
        return isEnabled(LinimalFeature.AGENT_I_CHAT_INFORMATION);
    }

    public boolean isAgentIWalletHeaderSuppressionEnabled() {
        return isEnabled(LinimalFeature.AGENT_I_WALLET_HEADER);
    }

    public boolean isAgentISettingsSuppressionEnabled() {
        return isEnabled(LinimalFeature.AGENT_I_SETTINGS);
    }

    public boolean isAgentIChatComposerSuppressionEnabled() {
        return isEnabled(LinimalFeature.AGENT_I_CHAT_COMPOSER);
    }

    public boolean isAgentIChatListSearchSuppressionEnabled() {
        return isEnabled(LinimalFeature.AGENT_I_CHAT_LIST_SEARCH);
    }

    public boolean isLineAiMessageContextMenuSuppressionEnabled() {
        return isEnabled(LinimalFeature.LINE_AI_MESSAGE_CONTEXT_MENU);
    }

    public boolean isLineAiGalleryViewerSuppressionEnabled() {
        return isEnabled(LinimalFeature.LINE_AI_GALLERY_VIEWER);
    }

    public boolean isShoppingSuppressionEnabled() {
        return isEnabled(LinimalFeature.SHOPPING);
    }

    public boolean isPremiumSuppressionEnabled() {
        return isEnabled(LinimalFeature.PREMIUM);
    }

    public boolean isPremiumSettingsRowSuppressionEnabled() {
        return isEnabled(LinimalFeature.PREMIUM_SETTINGS_ROW);
    }

    public boolean isVoomSuppressionEnabled() {
        return isEnabled(LinimalFeature.VOOM);
    }

    public boolean isNewsSuppressionEnabled() {
        return isEnabled(LinimalFeature.NEWS);
    }

    public boolean isWalletSuppressionEnabled() {
        return isEnabled(LinimalFeature.WALLET);
    }

    public boolean isMiniSuppressionEnabled() {
        return isEnabled(LinimalFeature.MINI);
    }

    public boolean isHomeRecommendationsSuppressionEnabled() {
        return isEnabled(LinimalFeature.HOME_RECOMMENDATIONS);
    }

    public boolean isHomeTrendingSuppressionEnabled() {
        return isEnabled(LinimalFeature.HOME_TRENDING);
    }

    public boolean isHomeFeedPostCardsSuppressionEnabled() {
        return isEnabled(LinimalFeature.HOME_FEED_POST_CARDS);
    }

    public boolean isHomeFeaturedCollectionsSuppressionEnabled() {
        return isEnabled(LinimalFeature.HOME_FEATURED_COLLECTIONS);
    }

    public boolean isChatCalendarSuppressionEnabled() {
        return isEnabled(LinimalFeature.CHAT_CALENDAR);
    }

    public boolean isChatLineGiftSuppressionEnabled() {
        return isEnabled(LinimalFeature.CHAT_LINE_GIFT);
    }

    public boolean isChatLinePaySuppressionEnabled() {
        return isEnabled(LinimalFeature.CHAT_LINE_PAY);
    }

    public boolean isReadWithoutReceiptEnabled() {
        return isEnabled(LinimalFeature.READ_WITHOUT_RECEIPT);
    }

    public boolean isChatListHeaderAiFriendsSuppressionEnabled() {
        return isEnabled(LinimalFeature.CHAT_LIST_HEADER_AI_FRIENDS);
    }

    public boolean isChatListHeaderCalendarSuppressionEnabled() {
        return isEnabled(LinimalFeature.CHAT_LIST_HEADER_CALENDAR);
    }

    public boolean isChatListHeaderOpenChatSuppressionEnabled() {
        return isEnabled(LinimalFeature.CHAT_LIST_HEADER_OPEN_CHAT);
    }

    public ReadReceiptMode getReadReceiptMode() {
        return snapshot.readReceiptMode();
    }

    public void setReadReceiptMode(ReadReceiptMode mode) {
        if (mode == null || store == null) {
            failOpen();
            return;
        }
        try {
            store.writeReadReceiptMode(mode);
            reload();
        } catch (RuntimeException exception) {
            failOpen();
        }
    }

    public boolean isExternalBrowserOverrideEnabled() {
        return isEnabled(LinimalFeature.EXTERNAL_BROWSER);
    }

    /**
     * 設定画面のための一括アクセス。機能 hook からは意味論的なメソッドを使い、
     * 任意の feature を扱うのは設定画面だけに限ります。
     */
    public boolean isSuppressionEnabled(LinimalFeature feature) {
        return feature != null && isEnabled(feature);
    }

    public void setSuppressionEnabled(LinimalFeature feature, boolean enabled) {
        if (feature == null) {
            failOpen();
            return;
        }
        setEnabled(feature, enabled);
    }

    static LinimalConfig fromStoreForTesting(LinimalConfigStore store) {
        LinimalConfig config = new LinimalConfig(store);
        config.reload();
        return config;
    }

    private static LinimalConfig unavailable() {
        return new LinimalConfig(null);
    }

    private boolean isEnabled(LinimalFeature feature) {
        return snapshot.isEnabled(feature);
    }

    private synchronized void setEnabled(LinimalFeature feature, boolean enabled) {
        if (store == null) {
            failOpen();
            return;
        }
        try {
            store.writeFeature(feature, enabled);
            reload();
        } catch (RuntimeException exception) {
            failOpen();
        }
    }

    private void failOpen() {
        snapshot = ConfigSnapshot.originalBehavior();
        health = LinimalConfigHealth.ERROR;
    }
}
