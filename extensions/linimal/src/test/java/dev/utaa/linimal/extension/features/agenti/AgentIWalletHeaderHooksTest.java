package dev.utaa.linimal.extension.features.agenti;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

/** Wallet header の Agent i state value boundary を検証します。 */
public final class AgentIWalletHeaderHooksTest {
    @Test
    public void offPreservesTheOriginalStateIdentity() {
        Object originalState = new Object();

        Object result = AgentIWalletHeaderHooks.adjustButtonStateForSuppression(originalState, false);

        assertSame(originalState, result);
    }

    @Test
    public void onPassesNullOnlyForTheSuppliedAgentIState() {
        assertNull(AgentIWalletHeaderHooks.adjustButtonStateForSuppression(new Object(), true));
        assertNull(AgentIWalletHeaderHooks.adjustButtonStateForSuppression(null, true));
    }
}
