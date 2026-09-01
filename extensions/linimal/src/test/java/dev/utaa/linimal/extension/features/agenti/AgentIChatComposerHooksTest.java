package dev.utaa.linimal.extension.features.agenti;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Agent i composer button と chip bar の runtime gate を Android framework なしで検証します。 */
public final class AgentIChatComposerHooksTest {
    @Test
    public void offPreservesVisibleComposerButton() {
        assertTrue(AgentIChatComposerHooks.adjustComposerButtonVisibilityForSuppression(true, false));
    }

    @Test
    public void offPreservesHiddenComposerButton() {
        assertFalse(AgentIChatComposerHooks.adjustComposerButtonVisibilityForSuppression(false, false));
    }

    @Test
    public void onHidesVisibleComposerButton() {
        assertFalse(AgentIChatComposerHooks.adjustComposerButtonVisibilityForSuppression(true, true));
    }

    @Test
    public void onKeepsHiddenComposerButtonHidden() {
        assertFalse(AgentIChatComposerHooks.adjustComposerButtonVisibilityForSuppression(false, true));
    }

    @Test
    public void offKeepsChipBarView() {
        Object chipBar = new Object();

        assertSame(chipBar, AgentIChatComposerHooks.adjustAiTalkSuggestionChipBarForSuppression(chipBar, false));
    }

    @Test
    public void offKeepsAbsentChipBarView() {
        assertNull(AgentIChatComposerHooks.adjustAiTalkSuggestionChipBarForSuppression(null, false));
    }

    @Test
    public void onRemovesChipBarView() {
        assertNull(AgentIChatComposerHooks.adjustAiTalkSuggestionChipBarForSuppression(new Object(), true));
    }

    @Test
    public void onKeepsAbsentChipBarViewAbsent() {
        assertNull(AgentIChatComposerHooks.adjustAiTalkSuggestionChipBarForSuppression(null, true));
    }
}
