package dev.utaa.linimal.extension.features.push;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** FIS の証明書ヘッダー置き換えの fail-open 契約を検証します。 */
public final class FirebaseInstallationsHooksTest {
    private static final String ORIGINAL = "0123456789ABCDEF0123456789ABCDEF01234567";
    private static final String ACTUAL = "FEDCBA9876543210FEDCBA9876543210FEDCBA98";

    @Test
    public void unavailableConfigurationKeepsTheActualCertificate() {
        // この単体テストでは LinimalConfig を初期化せず、patch も本体を注入しないため fail-open です。
        assertSame(ACTUAL, FirebaseInstallationsHooks.certificateHeader(ACTUAL));
        assertNull(FirebaseInstallationsHooks.certificateHeader(null));
    }

    @Test
    public void enabledSettingSendsTheOriginalCertificate() {
        assertEquals(ORIGINAL, FirebaseInstallationsHooks.certificateHeaderWith(true, ORIGINAL, ACTUAL));
        assertEquals(ORIGINAL, FirebaseInstallationsHooks.certificateHeaderWith(true, ORIGINAL, null));
    }

    @Test
    public void disabledSettingKeepsTheActualCertificate() {
        assertSame(ACTUAL, FirebaseInstallationsHooks.certificateHeaderWith(false, ORIGINAL, ACTUAL));
    }

    /** patch が値を注入していない build では、設定 ON でも LINE の元の値を送ります。 */
    @Test
    public void missingOrMalformedOriginalKeepsTheActualCertificate() {
        assertSame(ACTUAL, FirebaseInstallationsHooks.certificateHeaderWith(true, null, ACTUAL));
        assertSame(ACTUAL, FirebaseInstallationsHooks.certificateHeaderWith(true, "", ACTUAL));
        assertSame(ACTUAL, FirebaseInstallationsHooks.certificateHeaderWith(true, ORIGINAL.toLowerCase(), ACTUAL));
        assertSame(ACTUAL, FirebaseInstallationsHooks.certificateHeaderWith(true, ORIGINAL + "0", ACTUAL));
        assertSame(ACTUAL, FirebaseInstallationsHooks.certificateHeaderWith(
                true, "01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67", ACTUAL));
    }

    @Test
    public void certificateFormatMatchesWhatFirebaseSends() {
        assertTrue(FirebaseInstallationsHooks.isCertificateSha1(ORIGINAL));
        assertFalse(FirebaseInstallationsHooks.isCertificateSha1("0123456789abcdef0123456789abcdef01234567"));
        assertFalse(FirebaseInstallationsHooks.isCertificateSha1("G123456789ABCDEF0123456789ABCDEF01234567"));
    }

    @Test
    public void sourceLeavesTheOriginalCertificateForThePatch() {
        assertNull(FirebaseInstallationsHooks.originalCertificateSha1());
    }
}
