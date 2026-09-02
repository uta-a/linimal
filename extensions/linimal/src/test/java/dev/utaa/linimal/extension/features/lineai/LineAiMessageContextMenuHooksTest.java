package dev.utaa.linimal.extension.features.lineai;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Message long-press menu の LINE AI item supplier 用 hook を検証します。 */
public final class LineAiMessageContextMenuHooksTest {
    @Test
    public void offPreservesTheOriginalAvailability() {
        assertTrue(LineAiMessageContextMenuHooks.adjustAvailabilityForSuppression(true, false));
        assertFalse(LineAiMessageContextMenuHooks.adjustAvailabilityForSuppression(false, false));
    }

    @Test
    public void onOnlyChangesAnAvailableLineAiEntryToUnavailable() {
        assertFalse(LineAiMessageContextMenuHooks.adjustAvailabilityForSuppression(true, true));
        assertFalse(LineAiMessageContextMenuHooks.adjustAvailabilityForSuppression(false, true));
    }
}
