package dev.utaa.linimal.extension.features;

import dev.utaa.linimal.extension.config.LinimalConfig;

/** Premium 誘導 hook が参照する唯一の判定入口。 */
public final class PremiumHooks {
    private PremiumHooks() {
    }

    /**
     * 注入点。送信取消の Premium 案内ダイアログの表示だけを抑制するかどうかを返します。
     * 課金資格の判定や購読 API には一切触れません。設定が読めない場合は元の動作を維持します。
     */
    public static boolean shouldSuppressUnsendPromotion() {
        try {
            return LinimalConfig.get().isPremiumSuppressionEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
