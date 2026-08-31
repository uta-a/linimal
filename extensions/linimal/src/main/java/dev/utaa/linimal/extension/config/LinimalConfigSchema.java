package dev.utaa.linimal.extension.config;

/** 内部で保存する schema の定義。キーは意図的に config package の外へ出しません。 */
final class LinimalConfigSchema {
    static final int CURRENT_VERSION = 1;

    static final String SCHEMA_VERSION_KEY = "linimal.schema.version";
    static final String ADS_ENABLED_KEY = "linimal.ads.enabled";
    static final String LINE_AI_ENABLED_KEY = "linimal.feature.line_ai";
    static final String PREMIUM_ENABLED_KEY = "linimal.feature.premium";
    static final String VOOM_ENABLED_KEY = "linimal.feature.voom";
    static final String NEWS_ENABLED_KEY = "linimal.feature.news";
    static final String WALLET_ENABLED_KEY = "linimal.feature.wallet";
    static final String HOME_RECOMMENDATIONS_ENABLED_KEY = "linimal.ui.home.recommendations";
    static final String HOME_TRENDING_ENABLED_KEY = "linimal.ui.home.trending";
    static final String CHAT_CALENDAR_ENABLED_KEY = "linimal.chat.tools.calendar";
    static final String CHAT_LINE_GIFT_ENABLED_KEY = "linimal.chat.tools.line_gift";
    static final String CHAT_LINE_PAY_ENABLED_KEY = "linimal.chat.tools.line_pay";
    static final String READ_RECEIPT_MODE_KEY = "linimal.privacy.read_receipts.mode";
    static final String EXTERNAL_BROWSER_ENABLED_KEY = "linimal.browser.external";
    static final String DEBUG_LOGGING_ENABLED_KEY = "linimal.debug.logging";

    private LinimalConfigSchema() {
    }

    static String keyFor(LinimalFeature feature) {
        switch (feature) {
            case ADS:
                return ADS_ENABLED_KEY;
            case LINE_AI:
                return LINE_AI_ENABLED_KEY;
            case PREMIUM:
                return PREMIUM_ENABLED_KEY;
            case VOOM:
                return VOOM_ENABLED_KEY;
            case NEWS:
                return NEWS_ENABLED_KEY;
            case WALLET:
                return WALLET_ENABLED_KEY;
            case HOME_RECOMMENDATIONS:
                return HOME_RECOMMENDATIONS_ENABLED_KEY;
            case HOME_TRENDING:
                return HOME_TRENDING_ENABLED_KEY;
            case CHAT_CALENDAR:
                return CHAT_CALENDAR_ENABLED_KEY;
            case CHAT_LINE_GIFT:
                return CHAT_LINE_GIFT_ENABLED_KEY;
            case CHAT_LINE_PAY:
                return CHAT_LINE_PAY_ENABLED_KEY;
            case EXTERNAL_BROWSER:
                return EXTERNAL_BROWSER_ENABLED_KEY;
            case DEBUG_LOGGING:
                return DEBUG_LOGGING_ENABLED_KEY;
            default:
                throw new AssertionError("Unhandled feature: " + feature);
        }
    }
}
