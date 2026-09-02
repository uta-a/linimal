package dev.utaa.linimal.extension.features;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Home の各 module が共有する抑制 gate の fail-open 契約を検証します。 */
public final class HomeSuppressionGateTest {
    @Test
    public void enabledSuppressionStopsTheRenderer() {
        assertTrue(HomeSuppressionGate.shouldSuppress(new HomeSuppressionGate.SuppressionState() {
            @Override
            public boolean isSuppressionEnabled() {
                return true;
            }
        }));
    }

    @Test
    public void disabledSuppressionPreservesLineRendering() {
        assertFalse(HomeSuppressionGate.shouldSuppress(new HomeSuppressionGate.SuppressionState() {
            @Override
            public boolean isSuppressionEnabled() {
                return false;
            }
        }));
    }

    @Test
    public void missingStateFailsOpen() {
        assertFalse(HomeSuppressionGate.shouldSuppress(null));
    }

    @Test
    public void stateReadFailureFailsOpen() {
        assertFalse(HomeSuppressionGate.shouldSuppress(new HomeSuppressionGate.SuppressionState() {
            @Override
            public boolean isSuppressionEnabled() {
                throw new AssertionError("simulated config failure");
            }
        }));
    }
}
