package dev.utaa.linimal.extension.settings.ui;

import android.content.Context;
import android.content.res.Configuration;

/**
 * LINE の設定画面に合わせた配色。LINE のテーマ resource は難読化や差し替えの影響を受けるため、
 * 参照せずに端末のダークモードだけで切り替えます。
 */
public final class LinimalPalette {
    /** LINE のブランドカラー。操作可能なスイッチにだけ使います。 */
    private static final int BRAND = 0xFF06C755;

    public final int background;
    public final int primaryText;
    public final int secondaryText;
    public final int disabledText;
    public final int divider;
    public final int accent;
    public final int switchTrackOff;
    public final int statusOk;
    public final int statusWarning;
    public final int statusError;
    public final boolean dark;

    private LinimalPalette(boolean dark) {
        this.dark = dark;
        if (dark) {
            background = 0xFF1B1B1B;
            primaryText = 0xFFE8E8E8;
            secondaryText = 0xFF9A9A9A;
            disabledText = 0xFF5A5A5A;
            divider = 0xFF2E2E2E;
            switchTrackOff = 0xFF4A4A4A;
        } else {
            background = 0xFFFFFFFF;
            primaryText = 0xFF111111;
            secondaryText = 0xFF8C8C8C;
            disabledText = 0xFFC0C0C0;
            divider = 0xFFEDEDED;
            switchTrackOff = 0xFFD5D5D5;
        }
        accent = BRAND;
        statusOk = BRAND;
        statusWarning = 0xFFE8912D;
        statusError = 0xFFE5484D;
    }

    public static LinimalPalette of(Context context) {
        return new LinimalPalette(isNightMode(context));
    }

    private static boolean isNightMode(Context context) {
        try {
            Configuration configuration = context.getResources().getConfiguration();
            return (configuration.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
