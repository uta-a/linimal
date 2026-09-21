package dev.utaa.linimal.extension.features.googleauth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** MicroG-RE の導入案内が、1 回のバックアップで何度も出ないことを検証します。 */
public final class MicrogInstallNotifierTest {
    private static final long INTERVAL = MicrogInstallNotifier.MIN_INTERVAL_MILLIS;

    @Test
    public void firstRequestNotifies() {
        assertTrue(MicrogInstallNotifier.shouldNotify(Long.MIN_VALUE, 1_000L));
    }

    @Test
    public void repeatedRequestsWithinTheIntervalAreSuppressed() {
        assertFalse(MicrogInstallNotifier.shouldNotify(1_000L, 1_000L + INTERVAL - 1));
        assertTrue(MicrogInstallNotifier.shouldNotify(1_000L, 1_000L + INTERVAL));
    }

    @Test
    public void aClockThatWentBackwardsNotifiesAgain() {
        assertTrue(MicrogInstallNotifier.shouldNotify(10_000L, 5_000L));
    }

    @Test
    public void notificationOpensTheLatestRelease() {
        assertEquals("https://github.com/MorpheApp/MicroG-RE/releases/latest", MicrogInstallNotifier.RELEASES_URL);
    }
}
