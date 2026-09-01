package dev.utaa.linimal.extension.features.agenti;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Home header の Agent i boolean だけを変えることを検証します。 */
public final class AgentIHomeHeaderHooksTest {
    @Test
    public void offPreservesOriginalVisibleValue() {
        assertTrue(AgentIHomeHeaderHooks.adjustVisibilityForSuppression(true, false));
        assertFalse(AgentIHomeHeaderHooks.adjustVisibilityForSuppression(false, false));
    }

    @Test
    public void onSuppressesOnlyTheSuppliedAgentIVisibility() {
        assertFalse(AgentIHomeHeaderHooks.adjustVisibilityForSuppression(true, true));
        assertFalse(AgentIHomeHeaderHooks.adjustVisibilityForSuppression(false, true));
    }
}
