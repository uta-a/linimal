package dev.utaa.linimal.extension.features;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** ホームの特集枠 gate の fail-open 契約を検証します。 */
public final class HomeFeaturedCollectionsHooksTest {
    @Test
    public void enabledFeaturedCollectionsSuppressionHidesTheFeaturedGrid() {
        assertTrue(HomeFeaturedCollectionsHooks.shouldSuppressWith(
                new HomeFeaturedCollectionsHooks.SuppressionState() {
                    @Override
                    public boolean isSuppressionEnabled() {
                        return true;
                    }
                }));
    }

    @Test
    public void disabledFeaturedCollectionsSuppressionPreservesLineRendering() {
        assertFalse(HomeFeaturedCollectionsHooks.shouldSuppressWith(
                new HomeFeaturedCollectionsHooks.SuppressionState() {
                    @Override
                    public boolean isSuppressionEnabled() {
                        return false;
                    }
                }));
    }

    @Test
    public void missingStateFailsOpen() {
        assertFalse(HomeFeaturedCollectionsHooks.shouldSuppressWith(null));
    }

    @Test
    public void stateReadFailureFailsOpen() {
        assertFalse(HomeFeaturedCollectionsHooks.shouldSuppressWith(
                new HomeFeaturedCollectionsHooks.SuppressionState() {
                    @Override
                    public boolean isSuppressionEnabled() {
                        throw new AssertionError("simulated config failure");
                    }
                }));
    }

    @Test
    public void unavailableConfigurationPreservesLineRendering() {
        // この単体テストでは LinimalConfig を初期化しないため、runtime の初期値は fail-open です。
        assertFalse(HomeFeaturedCollectionsHooks.shouldSuppress());
    }
}
