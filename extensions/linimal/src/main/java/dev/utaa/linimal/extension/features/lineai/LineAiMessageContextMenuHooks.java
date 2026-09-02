package dev.utaa.linimal.extension.features.lineai;

import dev.utaa.linimal.extension.config.LinimalConfig;

/** Message long-press context menu の LINE AI item supplier を fail-open で調整します。 */
public final class LineAiMessageContextMenuHooks {
    private LineAiMessageContextMenuHooks() {
    }

    /** 設定取得に失敗した場合を含め、元の availability を維持します。 */
    public static boolean adjustAvailability(boolean originalAvailability) {
        try {
            return adjustAvailabilityForSuppression(
                    originalAvailability,
                    LinimalConfig.get().isLineAiMessageContextMenuSuppressionEnabled());
        } catch (Throwable ignored) {
            return originalAvailability;
        }
    }

    static boolean adjustAvailabilityForSuppression(
            boolean originalAvailability,
            boolean suppressLineAiMessageContextMenu) {
        return suppressLineAiMessageContextMenu ? false : originalAvailability;
    }
}
