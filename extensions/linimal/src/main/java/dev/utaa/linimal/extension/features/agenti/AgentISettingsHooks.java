package dev.utaa.linimal.extension.features.agenti;

import dev.utaa.linimal.extension.config.LinimalConfig;

/** Main Settings の Agent i / LINE AI Services visible predicate を fail-open で調整します。 */
public final class AgentISettingsHooks {
    private AgentISettingsHooks() {
    }

    /** 設定が OFF、未初期化、または読み取り失敗時には元の predicate 値を返します。 */
    public static boolean adjustVisibility(boolean originalVisibility) {
        try {
            return adjustVisibilityForSuppression(
                    originalVisibility,
                    LinimalConfig.get().isAgentISettingsSuppressionEnabled());
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
