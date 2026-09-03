package dev.utaa.linimal.extension.features;

import static org.junit.Assert.assertFalse;

import org.junit.Test;

/** ホーム上部の「最近の履歴」card gate の fail-open 契約を検証します。 */
public final class HomeRecentHistoryHooksTest {
    @Test
    public void unavailableConfigurationPreservesLineRendering() {
        // この単体テストでは LinimalConfig を初期化しないため、runtime の初期値は fail-open です。
        assertFalse(HomeRecentHistoryHooks.shouldSuppress());
    }
}
