package dev.utaa.linimal.extension.features;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** ホームの読み込み表示 gate の fail-open 契約を検証します。 */
public final class HomeFeedLoadingIndicatorHooksTest {
    @Test
    public void enabledSuppressionHidesTheLoadingIndicator() {
        assertTrue(HomeFeedLoadingIndicatorHooks.shouldSuppressWith(
                new HomeFeedLoadingIndicatorHooks.SuppressionState() {
                    @Override
                    public boolean isSuppressionEnabled() {
                        return true;
                    }
                }));
    }

    @Test
    public void disabledSuppressionPreservesLineRendering() {
        assertFalse(HomeFeedLoadingIndicatorHooks.shouldSuppressWith(
                new HomeFeedLoadingIndicatorHooks.SuppressionState() {
                    @Override
                    public boolean isSuppressionEnabled() {
                        return false;
                    }
                }));
    }

    @Test
    public void missingStateFailsOpen() {
        assertFalse(HomeFeedLoadingIndicatorHooks.shouldSuppressWith(null));
    }

    @Test
    public void stateReadFailureFailsOpen() {
        assertFalse(HomeFeedLoadingIndicatorHooks.shouldSuppressWith(
                new HomeFeedLoadingIndicatorHooks.SuppressionState() {
                    @Override
                    public boolean isSuppressionEnabled() {
                        throw new AssertionError("simulated config failure");
                    }
                }));
    }

    @Test
    public void unavailableConfigurationPreservesLineRendering() {
        // この単体テストでは LinimalConfig を初期化しないため、runtime の初期値は fail-open です。
        assertFalse(HomeFeedLoadingIndicatorHooks.shouldSuppress());
    }
}
