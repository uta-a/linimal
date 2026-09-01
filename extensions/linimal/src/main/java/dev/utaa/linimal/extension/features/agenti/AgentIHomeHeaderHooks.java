package dev.utaa.linimal.extension.features.agenti;

import dev.utaa.linimal.extension.config.LinimalConfig;

/** Home 上部ナビゲーションの Agent i 表示値だけを fail-open で調整します。 */
public final class AgentIHomeHeaderHooks {
    private AgentIHomeHeaderHooks() {
    }

    /** 設定が OFF、未初期化、または読み取り失敗時には元の visibility を返します。 */
    public static boolean adjustVisibility(boolean originalVisibility) {
        try {
            return adjustVisibilityForSuppression(
                    originalVisibility,
                    LinimalConfig.get().isAgentIHomeHeaderSuppressionEnabled());
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
