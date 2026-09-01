package dev.utaa.linimal.extension.features.agenti;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Settings の両 Agent i / LINE AI Services variant predicate を検証します。 */
public final class AgentISettingsHooksTest {
    @Test
    public void offPreservesEachVariantPredicate() {
        assertTrue(AgentISettingsHooks.adjustVisibilityForSuppression(true, false));
        assertFalse(AgentISettingsHooks.adjustVisibilityForSuppression(false, false));
    }

    @Test
    public void onForcesEitherVariantInvisible() {
        assertFalse(AgentISettingsHooks.adjustVisibilityForSuppression(true, true));
        assertFalse(AgentISettingsHooks.adjustVisibilityForSuppression(false, true));
    }
}
