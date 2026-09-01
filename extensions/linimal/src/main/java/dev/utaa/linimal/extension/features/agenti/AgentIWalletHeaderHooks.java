package dev.utaa.linimal.extension.features.agenti;

import dev.utaa.linimal.extension.config.LinimalConfig;

/** Wallet mini-tab header の Agent i button state だけを fail-open で調整します。 */
public final class AgentIWalletHeaderHooks {
    private AgentIWalletHeaderHooks() {
    }

    /** 設定が OFF、未初期化、または読み取り失敗時には同じ state instance を返します。 */
    public static Object adjustButtonState(Object originalState) {
        try {
            return adjustButtonStateForSuppression(
                    originalState,
                    LinimalConfig.get().isAgentIWalletHeaderSuppressionEnabled());
        } catch (Throwable ignored) {
            return originalState;
        }
    }

    static Object adjustButtonStateForSuppression(Object originalState, boolean suppressionEnabled) {
        return suppressionEnabled ? null : originalState;
    }
}
