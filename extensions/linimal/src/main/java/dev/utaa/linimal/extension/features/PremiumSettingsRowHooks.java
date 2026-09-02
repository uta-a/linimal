package dev.utaa.linimal.extension.features;

import dev.utaa.linimal.extension.config.LinimalConfig;

/** LINE 設定の Premium 行の可視性 predicate を fail-open で調整します。 */
public final class PremiumSettingsRowHooks {
    private PremiumSettingsRowHooks() {
    }

    /**
     * 元の可視性判定が完了した後にだけ適用します。設定が OFF、未初期化、または読み取り失敗時は
     * 元の判定値を返すため、LINE の行生成とレイアウトを変更しません。
     */
    public static boolean adjustVisibility(boolean originalVisibility) {
        try {
            return adjustVisibilityForSuppression(
                    originalVisibility,
                    LinimalConfig.get().isPremiumSettingsRowSuppressionEnabled());
        } catch (Throwable ignored) {
            return originalVisibility;
        }
    }

    static boolean adjustVisibilityForSuppression(
            boolean originalVisibility,
            boolean suppressionEnabled) {
        return suppressionEnabled ? false : originalVisibility;
    }
}
