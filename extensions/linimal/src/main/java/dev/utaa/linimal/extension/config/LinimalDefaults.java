package dev.utaa.linimal.extension.config;

/** Linimal の型付き設定における現在のデフォルト値。 */
public final class LinimalDefaults {
    static final ReadReceiptMode READ_RECEIPT_MODE = ReadReceiptMode.NORMAL;

    private LinimalDefaults() {
    }

    /**
     * 既定で有効にするのは広告の非表示と、Agent i・LINE AI の入口の非表示だけです。
     * それ以外は LINE の元の挙動を初期状態とし、利用者が設定画面で選びます。
     */
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
            // v1 aliases are routed to their v2 replacement keys by LinimalConfigSchema.
            case ADS:
            case LINE_AI:
                return true;
            case PREMIUM:
            case PREMIUM_SETTINGS_ROW:
            case VOOM:
            case SHOPPING:
            case NEWS:
            case WALLET:
            case MINI:
            case HOME_RECOMMENDATIONS:
            case HOME_TRENDING:
            case HOME_FEED_POST_CARDS:
            case HOME_FEATURED_COLLECTIONS:
            case HOME_RECENT_HISTORY:
            case CHAT_CALENDAR:
            case CHAT_LINE_GIFT:
            case CHAT_LINE_PAY:
            case READ_WITHOUT_RECEIPT:
            case CHAT_LIST_HEADER_AI_FRIENDS:
            case CHAT_LIST_HEADER_CALENDAR:
            case CHAT_LIST_HEADER_OPEN_CHAT:
            case EXTERNAL_BROWSER:
                return false;
            default:
                throw new AssertionError("Unhandled feature: " + feature);
        }
    }
}
