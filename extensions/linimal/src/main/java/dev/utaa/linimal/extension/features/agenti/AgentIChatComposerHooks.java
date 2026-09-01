package dev.utaa.linimal.extension.features.agenti;

import dev.utaa.linimal.extension.config.LinimalConfig;

/** Agent i in chat の composer button と入力欄下 chip bar を fail-open で調整します。 */
public final class AgentIChatComposerHooks {
    private AgentIChatComposerHooks() {
    }

    /**
     * AI Talk Suggestions の state observer が供給する visibility を局所的に抑制します。
     * 設定が OFF、未初期化、または読取時の例外では必ず元の値を返します。
     */
    public static boolean adjustComposerButtonVisibility(boolean originalVisibility) {
        try {
            return adjustComposerButtonVisibilityForSuppression(
                    originalVisibility,
                    LinimalConfig.get().isAgentIChatComposerSuppressionEnabled());
        } catch (Throwable ignored) {
            return originalVisibility;
        }
    }

    static boolean adjustComposerButtonVisibilityForSuppression(
            boolean originalVisibility,
            boolean suppressionEnabled) {
        return suppressionEnabled ? false : originalVisibility;
    }

    /**
     * 入力欄の下に並ぶ AI Talk Suggestions の chip bar view を、controller へ渡される直前で取り除きます。
     * 呼び出し元には元から null 分岐があり、LINE 自身も chip bar 無効構成では同じ分岐を通ります。
     * 設定が OFF、未初期化、または読取時の例外では必ず元の view を返します。
     */
    public static Object adjustAiTalkSuggestionChipBar(Object originalChipBar) {
        try {
            return adjustAiTalkSuggestionChipBarForSuppression(
                    originalChipBar,
                    LinimalConfig.get().isAgentIChatComposerSuppressionEnabled());
        } catch (Throwable ignored) {
            return originalChipBar;
        }
    }

    static Object adjustAiTalkSuggestionChipBarForSuppression(
            Object originalChipBar,
            boolean suppressionEnabled) {
        return suppressionEnabled ? null : originalChipBar;
    }
}
