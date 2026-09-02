package dev.utaa.linimal.extension.config;

/**
 * Linimal が適用できる boolean の runtime 変更。
 *
 * <p>enabled の値は常に、対応する Linimal の変更が有効であることを示します。元の LINE 機能が
 * 有効であるかどうかを示すものではありません。</p>
 */
public enum LinimalFeature {
    /**
     * v1 の広告設定との source/binary compatibility のための alias です。
     * 新しい hook は {@link #SMART_CHANNEL_ADS} または {@link #HOME_TOP_AD} を使用します。
     */
    @Deprecated
    ADS,

    /**
     * v1 の LINE AI 設定との source/binary compatibility のための alias です。
     * 新しい hook は {@link #AGENT_I_CHAT_INFORMATION} を使用します。
     */
    @Deprecated
    LINE_AI,

    PREMIUM,
    VOOM,
    NEWS,
    WALLET,
    HOME_RECOMMENDATIONS,
    HOME_TRENDING,
    CHAT_CALENDAR,
    CHAT_LINE_GIFT,
    CHAT_LINE_PAY,
    EXTERNAL_BROWSER,
    DEBUG_LOGGING,

    SMART_CHANNEL_ADS,
    HOME_TOP_AD,
    AGENT_I_HOME_HEADER,
    AGENT_I_CHAT_INFORMATION,
    AGENT_I_WALLET_HEADER,
    AGENT_I_SETTINGS,
    AGENT_I_CHAT_COMPOSER,
    AGENT_I_CHAT_LIST_SEARCH,
    LINE_AI_MESSAGE_CONTEXT_MENU,
    LINE_AI_GALLERY_VIEWER,
    SHOPPING,
    HOME_FEED_POST_CARDS,
    HOME_FEATURED_COLLECTIONS,
    PREMIUM_SETTINGS_ROW,
    READ_WITHOUT_RECEIPT
}
