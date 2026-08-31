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

    public boolean isAdsSuppressionEnabled() {
        return isEnabled(LinimalFeature.ADS);
    }

    public void setAdsSuppressionEnabled(boolean enabled) {
        setEnabled(LinimalFeature.ADS, enabled);
    }

    public boolean isLineAiSuppressionEnabled() {
        return isEnabled(LinimalFeature.LINE_AI);
    }

    public void setLineAiSuppressionEnabled(boolean enabled) {
        setEnabled(LinimalFeature.LINE_AI, enabled);
    }

    public boolean isPremiumSuppressionEnabled() {
        return isEnabled(LinimalFeature.PREMIUM);
    }

    public void setPremiumSuppressionEnabled(boolean enabled) {
        setEnabled(LinimalFeature.PREMIUM, enabled);
    }

    public boolean isVoomSuppressionEnabled() {
        return isEnabled(LinimalFeature.VOOM);
    }

    public void setVoomSuppressionEnabled(boolean enabled) {
        setEnabled(LinimalFeature.VOOM, enabled);
    }

    public boolean isNewsSuppressionEnabled() {
        return isEnabled(LinimalFeature.NEWS);
    }

    public void setNewsSuppressionEnabled(boolean enabled) {
        setEnabled(LinimalFeature.NEWS, enabled);
    }

    public boolean isWalletSuppressionEnabled() {
        return isEnabled(LinimalFeature.WALLET);
    }

    public void setWalletSuppressionEnabled(boolean enabled) {
        setEnabled(LinimalFeature.WALLET, enabled);
    }

    public boolean isHomeRecommendationsSuppressionEnabled() {
        return isEnabled(LinimalFeature.HOME_RECOMMENDATIONS);
    }

    public void setHomeRecommendationsSuppressionEnabled(boolean enabled) {
        setEnabled(LinimalFeature.HOME_RECOMMENDATIONS, enabled);
    }

    public boolean isHomeTrendingSuppressionEnabled() {
        return isEnabled(LinimalFeature.HOME_TRENDING);
    }

    public void setHomeTrendingSuppressionEnabled(boolean enabled) {
        setEnabled(LinimalFeature.HOME_TRENDING, enabled);
    }

    public boolean isChatCalendarSuppressionEnabled() {
        return isEnabled(LinimalFeature.CHAT_CALENDAR);
    }

    public void setChatCalendarSuppressionEnabled(boolean enabled) {
        setEnabled(LinimalFeature.CHAT_CALENDAR, enabled);
    }

    public boolean isChatLineGiftSuppressionEnabled() {
        return isEnabled(LinimalFeature.CHAT_LINE_GIFT);
    }

    public void setChatLineGiftSuppressionEnabled(boolean enabled) {
        setEnabled(LinimalFeature.CHAT_LINE_GIFT, enabled);
    }

    public boolean isChatLinePaySuppressionEnabled() {
        return isEnabled(LinimalFeature.CHAT_LINE_PAY);
    }

    public void setChatLinePaySuppressionEnabled(boolean enabled) {
        setEnabled(LinimalFeature.CHAT_LINE_PAY, enabled);
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

    public void setExternalBrowserOverrideEnabled(boolean enabled) {
        setEnabled(LinimalFeature.EXTERNAL_BROWSER, enabled);
    }

    public boolean isDebugLoggingEnabled() {
        return isEnabled(LinimalFeature.DEBUG_LOGGING);
    }

    public void setDebugLoggingEnabled(boolean enabled) {
        setEnabled(LinimalFeature.DEBUG_LOGGING, enabled);
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
