package dev.utaa.linimal.extension.features.googleauth;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import dev.utaa.linimal.extension.features.googleauth.GoogleAuthRoutingHooks.Routing;
import org.junit.Test;

/** Google 認証の MicroG-RE への向け替えが GetToken だけに限られ、fail-open であることを検証します。 */
public final class GoogleAuthRoutingHooksTest {
    private static final String GMS = GoogleAuthRoutingHooks.GMS_PACKAGE;
    private static final String MICROG = GoogleAuthRoutingHooks.MICROG_PACKAGE;
    private static final String GET_TOKEN = GoogleAuthRoutingHooks.GET_TOKEN_CLASS;

    @Test
    public void enabledWithMicrogRoutesToMicrog() {
        assertEquals(Routing.MICROG, GoogleAuthRoutingHooks.routing(true, MICROG::equals));
    }

    /** 設定 ON でも MicroG-RE がなければ元の経路を使い、導入を案内します。 */
    @Test
    public void enabledWithoutMicrogKeepsTheOriginalPathAndAsksForInstall() {
        assertEquals(Routing.MICROG_MISSING, GoogleAuthRoutingHooks.routing(true, packageName -> false));
    }

    @Test
    public void disabledSettingKeepsTheOriginalPathEvenWithMicrog() {
        assertEquals(Routing.ORIGINAL, GoogleAuthRoutingHooks.routing(false, MICROG::equals));
        assertEquals(Routing.ORIGINAL, GoogleAuthRoutingHooks.routing(true, null));
    }

    @Test
    public void onlyTheGmsGetTokenComponentIsRouted() {
        assertEquals(MICROG, GoogleAuthRoutingHooks.routedPackage(true, GMS, GET_TOKEN));
        assertEquals(GMS, GoogleAuthRoutingHooks.routedPackage(false, GMS, GET_TOKEN));
        assertEquals(GMS, GoogleAuthRoutingHooks.routedPackage(true, GMS, "com.google.android.gms.auth.Other"));
        assertEquals("other.pkg", GoogleAuthRoutingHooks.routedPackage(true, "other.pkg", GET_TOKEN));
        assertNull(GoogleAuthRoutingHooks.routedPackage(true, null, GET_TOKEN));
    }

    @Test
    public void pinnedCertificateIsASha256Digest() {
        byte[] certificate = GoogleAuthRoutingHooks.hexToBytes(GoogleAuthRoutingHooks.MICROG_CERTIFICATE_SHA256);

        assertEquals(32, certificate.length);
        assertArrayEquals(new byte[] {0x0b, 0x6c, (byte) 0x95}, java.util.Arrays.copyOf(certificate, 3));
    }

    /** この単体テストでは LinimalConfig を初期化しないため、hook は元の経路を返します。 */
    @Test
    public void uninitializedConfigurationFailsOpen() {
        assertFalse(GoogleAuthRoutingHooks.shouldSkipAuthServiceClient(null));
        assertNull(GoogleAuthRoutingHooks.authServiceComponent(null, null));
    }
}
