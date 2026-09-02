package dev.utaa.linimal.extension.features;

import static org.junit.Assert.assertFalse;

import org.junit.Test;

/** ホームの特集枠 gate の fail-open 契約を検証します。 */
public final class HomeFeaturedCollectionsHooksTest {
    @Test
    public void unavailableConfigurationPreservesLineRendering() {
        // この単体テストでは LinimalConfig を初期化しないため、runtime の初期値は fail-open です。
        assertFalse(HomeFeaturedCollectionsHooks.shouldSuppress());
    }
}
