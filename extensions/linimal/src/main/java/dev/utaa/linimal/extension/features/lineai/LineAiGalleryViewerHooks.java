package dev.utaa.linimal.extension.features.lineai;

import dev.utaa.linimal.extension.config.LinimalConfig;

/** Chat gallery viewer header の LINE AI image-edit button を fail-open で調整します。 */
public final class LineAiGalleryViewerHooks {
    private LineAiGalleryViewerHooks() {
    }

    /** 設定取得に失敗した場合を含め、binder が渡した元の visibility を維持します。 */
    public static boolean adjustVisibility(boolean originalVisibility) {
        try {
            return adjustVisibilityForSuppression(
                    originalVisibility,
                    LinimalConfig.get().isLineAiGalleryViewerSuppressionEnabled());
        } catch (Throwable ignored) {
            return originalVisibility;
        }
    }

    static boolean adjustVisibilityForSuppression(
            boolean originalVisibility,
            boolean suppressLineAiGalleryViewer) {
        return suppressLineAiGalleryViewer ? false : originalVisibility;
    }
}
