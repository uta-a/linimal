package dev.utaa.linimal.extension.features;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Premium 設定行の predicate 調整が元の可視性を fail-open で保持することを検証します。 */
public final class PremiumSettingsRowHooksTest {
    @Test
    public void offPreservesOriginalVisibility() {
        assertTrue(PremiumSettingsRowHooks.adjustVisibilityForSuppression(true, false));
        assertFalse(PremiumSettingsRowHooks.adjustVisibilityForSuppression(false, false));
    }

    @Test
    public void onExcludesTheEntireRow() {
        assertFalse(PremiumSettingsRowHooks.adjustVisibilityForSuppression(true, true));
        assertFalse(PremiumSettingsRowHooks.adjustVisibilityForSuppression(false, true));
    }

    @Test
    public void unavailableConfigurationPreservesOriginalVisibility() {
        // この単体テストでは LinimalConfig を初期化しないため、runtime の初期値は fail-open です。
        assertTrue(PremiumSettingsRowHooks.adjustVisibility(true));
        assertFalse(PremiumSettingsRowHooks.adjustVisibility(false));
    }
}
