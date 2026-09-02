package dev.utaa.linimal.extension.config;

/** Linimal の型付き設定における現在のデフォルト値。 */
public final class LinimalDefaults {
    static final ReadReceiptMode READ_RECEIPT_MODE = ReadReceiptMode.NORMAL;

    private LinimalDefaults() {
    }

    static boolean isEnabled(LinimalFeature feature) {
        switch (feature) {
            case SMART_CHANNEL_ADS:
            case HOME_TOP_AD:
            case AGENT_I_HOME_HEADER:
            case AGENT_I_CHAT_INFORMATION:
            case AGENT_I_WALLET_HEADER:
            case AGENT_I_SETTINGS:
            case AGENT_I_CHAT_COMPOSER:
            case AGENT_I_CHAT_LIST_SEARCH:
            case LINE_AI_MESSAGE_CONTEXT_MENU:
            case LINE_AI_GALLERY_VIEWER:
            case SHOPPING:
            case HOME_FEED_POST_CARDS:
            case HOME_FEATURED_COLLECTIONS:
            case PREMIUM_SETTINGS_ROW:
            // v1 aliases are routed to their v2 replacement keys by LinimalConfigSchema.
            case ADS:
            case LINE_AI:
            case PREMIUM:
            case VOOM:
            case NEWS:
            case HOME_RECOMMENDATIONS:
            case HOME_TRENDING:
            case CHAT_CALENDAR:
            case CHAT_LINE_GIFT:
            case CHAT_LINE_PAY:
            case READ_WITHOUT_RECEIPT:
                return true;
            case WALLET:
            case EXTERNAL_BROWSER:
            case DEBUG_LOGGING:
                return false;
            default:
                throw new AssertionError("Unhandled feature: " + feature);
        }
    }
}
