package dev.utaa.linimal.extension.features.agenti;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** トーク一覧の検索欄にある Agent i boolean だけを変えることを検証します。 */
public final class AgentIChatListSearchHooksTest {
    @Test
    public void offPreservesOriginalVisibleValue() {
        assertTrue(AgentIChatListSearchHooks.adjustVisibilityForSuppression(true, false));
        assertFalse(AgentIChatListSearchHooks.adjustVisibilityForSuppression(false, false));
    }

    @Test
    public void onSuppressesOnlyTheSuppliedAgentIVisibility() {
        assertFalse(AgentIChatListSearchHooks.adjustVisibilityForSuppression(true, true));
        assertFalse(AgentIChatListSearchHooks.adjustVisibilityForSuppression(false, true));
    }
}
