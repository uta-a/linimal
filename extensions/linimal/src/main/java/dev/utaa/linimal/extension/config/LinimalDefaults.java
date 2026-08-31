package dev.utaa.linimal.extension.config;

/** Linimal の型付き設定における v1 のデフォルト値。 */
public final class LinimalDefaults {
    static final ReadReceiptMode READ_RECEIPT_MODE = ReadReceiptMode.NORMAL;

    private LinimalDefaults() {
    }

    static boolean isEnabled(LinimalFeature feature) {
        switch (feature) {
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
