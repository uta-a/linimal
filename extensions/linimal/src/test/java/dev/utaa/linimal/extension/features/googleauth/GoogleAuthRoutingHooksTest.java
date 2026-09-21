package dev.utaa.linimal.extension.features.googleauth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Google 認証の MicroG-RE への向け替えが GetToken だけに限られ、fail-open であることを検証します。 */
public final class GoogleAuthRoutingHooksTest {
    private static final String GMS = GoogleAuthRoutingHooks.GMS_PACKAGE;
    private static final String MICROG = GoogleAuthRoutingHooks.MICROG_PACKAGE;
    private static final String GET_TOKEN = GoogleAuthRoutingHooks.GET_TOKEN_CLASS;

    @Test
    public void routesGetTokenToMicrogWhenActive() {
        assertEquals(MICROG, GoogleAuthRoutingHooks.routedPackage(true, GMS, GET_TOKEN));
    }

    @Test
    public void keepsGmsWhenInactive() {
        assertEquals(GMS, GoogleAuthRoutingHooks.routedPackage(false, GMS, GET_TOKEN));
    }

    @Test
    public void keepsOtherComponentsUntouched() {
        assertEquals(GMS, GoogleAuthRoutingHooks.routedPackage(true, GMS, "com.google.android.gms.auth.Other"));
        assertEquals("other.pkg", GoogleAuthRoutingHooks.routedPackage(true, "other.pkg", GET_TOKEN));
        assertNull(GoogleAuthRoutingHooks.routedPackage(true, null, GET_TOKEN));
    }

    @Test
    public void routingRequiresMicrogInstalled() {
        assertTrue(GoogleAuthRoutingHooks.isRoutingActive(MICROG::equals));
        assertFalse(GoogleAuthRoutingHooks.isRoutingActive(packageName -> false));
        assertFalse(GoogleAuthRoutingHooks.isRoutingActive(null));
    }

    @Test
    public void missingContextFailsOpen() {
        assertFalse(GoogleAuthRoutingHooks.shouldSkipAuthServiceClient(null));
        assertNull(GoogleAuthRoutingHooks.authServiceComponent(null, null));
    }
}
