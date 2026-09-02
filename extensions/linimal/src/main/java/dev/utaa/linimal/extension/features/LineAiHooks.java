package dev.utaa.linimal.extension.features;

import dev.utaa.linimal.extension.config.LinimalConfig;

/** LINE AI の入口モデルへ渡す visibility を fail-open で調整します。 */
public final class LineAiHooks {
    private LineAiHooks() {
    }

    /** 設定が OFF、未初期化、または読めない場合は必ず元の引数を返します。 */
    public static boolean adjustVisibility(boolean originalVisibility) {
        try {
            return adjustVisibilityForSuppression(
                    originalVisibility,
                    LinimalConfig.get().isAgentIChatInformationSuppressionEnabled());
        } catch (Throwable ignored) {
            return originalVisibility;
        }
    }

    static boolean adjustVisibilityForSuppression(boolean originalVisibility, boolean suppressLineAi) {
        return suppressLineAi ? false : originalVisibility;
    }
}
