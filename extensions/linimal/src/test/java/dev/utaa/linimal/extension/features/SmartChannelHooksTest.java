package dev.utaa.linimal.extension.features;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

/** Android framework を起動せず、枠抑制 hook の fail-open な判定だけを検証します。 */
public final class SmartChannelHooksTest {
    @Test
    public void placementIsNotSuppressedWhileTheSettingIsOff() {
        AtomicInteger hideCalls = new AtomicInteger();

        assertFalse(SmartChannelHooks.shouldSuppressPlacementWith(false, new Object(), frame -> {
            hideCalls.incrementAndGet();
            return true;
        }));
        assertEquals(0, hideCalls.get());
    }

    @Test
    public void placementIsSuppressedOnlyWhenTheFrameCouldBeHidden() {
        Object frame = new Object();

        assertTrue(SmartChannelHooks.shouldSuppressPlacementWith(true, frame, ignored -> true));
        assertFalse(SmartChannelHooks.shouldSuppressPlacementWith(true, frame, ignored -> false));
    }

    @Test
    public void placementFallsBackToTheOriginalHandlingWhenTheFrameIsMissing() {
        assertFalse(SmartChannelHooks.shouldSuppressPlacementWith(true, null, ignored -> true));
        assertFalse(SmartChannelHooks.shouldSuppressPlacementWith(true, new Object(), null));
    }

    @Test
    public void placementFallsBackToTheOriginalHandlingWhenHidingThrows() {
        assertFalse(SmartChannelHooks.shouldSuppressPlacementWith(true, new Object(), ignored -> {
            throw new IllegalStateException("not on the ui thread");
        }));
    }

    @Test
    public void unavailableConfigurationPreservesTheOriginalHandling() {
        // 単体テストでは LinimalConfig を初期化しないため、production 経路も seam 経由で fail-open です。
        Object renderer = new Object();

        assertFalse(SmartChannelHooks.shouldSuppressPlacement(new Object()));
        assertFalse(SmartChannelHooks.shouldSuppressRenderer(renderer));
        assertSame(renderer, SmartChannelHooks.rendererForBinding(renderer));
    }

    @Test
    public void rendererCleanupDoesNotDependOnTheStopCallback() {
        // 停止 callback を呼べなくても、取り外しに成功すれば抑制は成立します。
        assertTrue(SmartChannelHooks.shouldSuppressWith(true, new Object(), ignored -> true));
        assertFalse(SmartChannelHooks.shouldSuppressWith(true, new Object(), ignored -> false));
    }
}
