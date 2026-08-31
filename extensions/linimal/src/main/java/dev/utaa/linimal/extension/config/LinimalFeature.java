package dev.utaa.linimal.extension.config;

/**
 * Linimal が適用できる boolean の runtime 変更。
 *
 * <p>enabled の値は常に、対応する Linimal の変更が有効であることを示します。元の LINE 機能が
 * 有効であるかどうかを示すものではありません。</p>
 */
public enum LinimalFeature {
    ADS,
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
    DEBUG_LOGGING
}
