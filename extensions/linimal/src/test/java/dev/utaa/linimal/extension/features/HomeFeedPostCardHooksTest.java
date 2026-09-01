package dev.utaa.linimal.extension.features;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Home Feed post card gate の fail-open 契約を検証します。 */
public final class HomeFeedPostCardHooksTest {
    @Test
    public void enabledPostCardSuppressionHidesPostCards() {
        assertTrue(HomeFeedPostCardHooks.shouldSuppressWith(new HomeFeedPostCardHooks.SuppressionState() {
            @Override
            public boolean isSuppressionEnabled() {
                return true;
            }
        }));
    }

    @Test
    public void disabledPostCardSuppressionPreservesLineRendering() {
        assertFalse(HomeFeedPostCardHooks.shouldSuppressWith(new HomeFeedPostCardHooks.SuppressionState() {
            @Override
            public boolean isSuppressionEnabled() {
                return false;
            }
        }));
    }

    @Test
    public void missingStateFailsOpen() {
        assertFalse(HomeFeedPostCardHooks.shouldSuppressWith(null));
    }

    @Test
    public void stateReadFailureFailsOpen() {
        assertFalse(HomeFeedPostCardHooks.shouldSuppressWith(new HomeFeedPostCardHooks.SuppressionState() {
            @Override
            public boolean isSuppressionEnabled() {
                throw new AssertionError("simulated config failure");
            }
        }));
    }

    @Test
    public void unavailableConfigurationPreservesLineRendering() {
        // この単体テストでは LinimalConfig を初期化しないため、runtime の初期値は fail-open です。
        assertFalse(HomeFeedPostCardHooks.shouldSuppress());
    }
}
