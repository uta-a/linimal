package dev.utaa.linimal.extension.features.lineai;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Gallery viewer header の LINE AI image-edit visibility hook を検証します。 */
public final class LineAiGalleryViewerHooksTest {
    @Test
    public void offPreservesTheOriginalBinderBoolean() {
        assertTrue(LineAiGalleryViewerHooks.adjustVisibilityForSuppression(true, false));
        assertFalse(LineAiGalleryViewerHooks.adjustVisibilityForSuppression(false, false));
    }

    @Test
    public void onOnlyHidesAnOtherwiseVisibleHeaderButton() {
        assertFalse(LineAiGalleryViewerHooks.adjustVisibilityForSuppression(true, true));
        assertFalse(LineAiGalleryViewerHooks.adjustVisibilityForSuppression(false, true));
    }
}
