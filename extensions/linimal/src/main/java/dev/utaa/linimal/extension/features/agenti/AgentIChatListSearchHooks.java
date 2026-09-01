package dev.utaa.linimal.extension.features.agenti;

import dev.utaa.linimal.extension.config.LinimalConfig;

/** トーク一覧上部の検索欄にある Agent i の表示値だけを fail-open で調整します。 */
public final class AgentIChatListSearchHooks {
    private AgentIChatListSearchHooks() {
    }

    /** 設定が OFF、未初期化、または読み取り失敗時には元の visibility を返します。 */
    public static boolean adjustVisibility(boolean originalVisibility) {
        try {
            return adjustVisibilityForSuppression(
                    originalVisibility,
                    LinimalConfig.get().isAgentIChatListSearchSuppressionEnabled());
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
