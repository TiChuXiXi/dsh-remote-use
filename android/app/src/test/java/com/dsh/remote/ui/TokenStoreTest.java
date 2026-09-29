package com.dsh.remote.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;

/**
 * The Keystore-encrypted session token store (Task A).
 *
 * <p>What is asserted here, in order of value:
 * <ol>
 *   <li>a token written through {@link App} round-trips and the plaintext is <b>not</b> in the
 *       SharedPreferences XML on disk;</li>
 *   <li>a token left by an older (plaintext) build is migrated on read and still works — the
 *       user is not logged out by the upgrade;</li>
 *   <li>every Keystore failure mode falls back to plaintext without throwing and without
 *       losing the session, and says why ({@link TokenCipher#lastFallbackReason()});</li>
 *   <li>the <em>real</em>, unsubstituted AndroidKeyStore backend is exercised too — it is
 *       genuinely unavailable here, which is exactly the "broken device" case.</li>
 * </ol>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
// conscrypt-openjdk-uber ships no linux-aarch_64 native library, and Robolectric installs it
// as a JCE provider during setup; nothing here talks TLS to a real peer.
@ConscryptMode(ConscryptMode.Mode.OFF)
public class TokenStoreTest extends UiTestBase {

    /** The two preference keys the store uses (pinned on purpose: they are the on-disk format). */
    private static final String KEY_TOKEN = "token";
    private static final String KEY_TOKEN_ENC = "token_enc";

    private static final String TOKEN = "SECRET-GATEWAY-TOKEN-0123456789";

    @Before
    public void setUp() {
        TokenCipher.resetForTests();
        Notify.resetForTests();
        prefs().edit().clear().commit();
    }

    @After
    public void tearDown() {
        TokenCipher.resetForTests();
        Notify.resetForTests();
        prefs().edit().clear().commit();
    }

    // ------------------------------------------------------------------ happy path

    @Test
    public void tokenRoundTripsThroughTheEncryptedStore() {
        TokenCipher.setBackendForTests(JceTokenBackend.working());

        App.saveLogin(appContext(), "http://gw.example:3081", "admin", TOKEN, 1234L);

        assertEquals("the token must survive the encrypted store", TOKEN, App.getToken(appContext()));
        assertEquals("keystore", App.tokenStorageMode(appContext()));
        assertEquals("", prefs().getString(KEY_TOKEN, ""));
        assertFalse("no plaintext copy may be left behind", prefs().contains(KEY_TOKEN));

        String blob = prefs().getString(KEY_TOKEN_ENC, null);
        assertNotNull("nothing was written to the encrypted key", blob);
        assertTrue("blob must carry the version prefix, was: " + blob, blob.startsWith("v1:"));
        assertFalse("the blob must not contain the token", blob.contains(TOKEN));
    }

    /**
     * The claim that matters for the whole task: after a write, reading the actual XML file the
     * platform persists shows the ciphertext and no trace of the bearer token.
     */
    @Test
    public void plaintextIsAbsentFromTheSharedPreferencesXmlAfterAWrite() throws Exception {
        TokenCipher.setBackendForTests(JceTokenBackend.working());

        App.saveLogin(appContext(), "http://gw.example:3081", "admin", TOKEN, 1234L);

        assertTrue("the prefs XML never reached the disk: " + prefsXmlPath(),
                waitFor(() -> prefsXml().contains(KEY_TOKEN_ENC), 20_000L, "encrypted prefs on disk"));

        String xml = prefsXml();
        assertTrue("expected the encrypted blob in the XML, got:\n" + xml,
                xml.contains(KEY_TOKEN_ENC) && xml.contains("v1:"));
        assertFalse("the plaintext token is on disk:\n" + xml, xml.contains(TOKEN));
        assertFalse("the plaintext token key is on disk:\n" + xml,
                xml.contains("name=\"" + KEY_TOKEN + "\""));
    }

    @Test
    public void clearingTheLoginRemovesBothCopies() {
        TokenCipher.setBackendForTests(JceTokenBackend.working());
        App.saveLogin(appContext(), "http://gw.example:3081", "admin", TOKEN, 1234L);

        App.clearLogin(appContext());

        assertEquals("", App.getToken(appContext()));
        assertEquals("empty", App.tokenStorageMode(appContext()));
        assertNull(prefs().getString(KEY_TOKEN_ENC, null));
        assertNull(prefs().getString(KEY_TOKEN, null));
    }

    // ------------------------------------------------------------------ migration

    @Test
    public void preExistingPlaintextTokenIsMigratedAndStillReadable() {
        // Exactly what the previous build left behind.
        prefs().edit().putString(KEY_TOKEN, "LEGACY-PLAINTEXT-TOKEN").commit();
        assertEquals("plaintext", App.tokenStorageMode(appContext()));
        TokenCipher.setBackendForTests(JceTokenBackend.working());

        assertEquals("the user must not be logged out by the upgrade",
                "LEGACY-PLAINTEXT-TOKEN", App.getToken(appContext()));

        // ... and the next read finds it encrypted, with the plaintext gone.
        assertEquals("keystore", App.tokenStorageMode(appContext()));
        assertFalse(prefs().contains(KEY_TOKEN));
        assertEquals("LEGACY-PLAINTEXT-TOKEN", App.getToken(appContext()));
        assertFalse("the migrated token is still plaintext on disk:\n" + prefsXml(),
                prefsXml().contains("LEGACY-PLAINTEXT-TOKEN"));
    }

    @Test
    public void migrationIsIdempotentAcrossReads() {
        prefs().edit().putString(KEY_TOKEN, "LEGACY-2").commit();
        TokenCipher.setBackendForTests(JceTokenBackend.working());

        assertEquals("LEGACY-2", App.getToken(appContext()));
        assertEquals("the first read must have migrated it", "keystore",
                App.tokenStorageMode(appContext()));
        String first = prefs().getString(KEY_TOKEN_ENC, null);
        assertEquals("LEGACY-2", App.getToken(appContext()));
        assertEquals("a second read must not re-encrypt", first, prefs().getString(KEY_TOKEN_ENC, null));
    }

    // ------------------------------------------------------------------ fallback

    /**
     * A Keystore that throws on write (the documented, expected outcome on a broken ROM) must
     * degrade to the old plaintext behaviour: no exception, and the session still there.
     */
    @Test
    public void keystoreFailureOnWriteFallsBackWithoutLosingTheSession() {
        TokenCipher.setBackendForTests(JceTokenBackend.alwaysFailing("keystore exploded"));

        App.saveLogin(appContext(), "http://gw.example:3081", "admin", TOKEN, 1234L);

        assertEquals("the session must survive a broken Keystore", TOKEN, App.getToken(appContext()));
        assertEquals("plaintext", App.tokenStorageMode(appContext()));
        assertEquals(TOKEN, prefs().getString(KEY_TOKEN, ""));
        assertFalse("a failed write must not leave a half-written blob", prefs().contains(KEY_TOKEN_ENC));
        assertNotNull("the fallback must be reported, not silent", TokenCipher.lastFallbackReason());
        assertTrue("the reason should name the failing operation, was: " + TokenCipher.lastFallbackReason(),
                TokenCipher.lastFallbackReason().contains("write"));
        assertFalse("TokenCipher must admit it is unusable", TokenCipher.available());
    }

    /** A plaintext token already on disk stays readable when the Keystore cannot be used. */
    @Test
    public void keystoreFailureDuringMigrationKeepsThePlaintextCopy() {
        prefs().edit().putString(KEY_TOKEN, "LEGACY-KEEP-ME").commit();
        TokenCipher.setBackendForTests(JceTokenBackend.alwaysFailing("no keystore"));

        assertEquals("LEGACY-KEEP-ME", App.getToken(appContext()));
        assertEquals("plaintext", App.tokenStorageMode(appContext()));
        assertEquals("the plaintext copy must be left in place, not deleted",
                "LEGACY-KEEP-ME", prefs().getString(KEY_TOKEN, ""));
        assertTrue(TokenCipher.lastFallbackReason().contains("migrate"));
    }

    /** A blob that cannot be decrypted must not throw, and must not lose a plaintext copy. */
    @Test
    public void undecryptableBlobFallsBackToThePlaintextCopy() {
        prefs().edit()
                .putString(KEY_TOKEN_ENC, "v1:AAAAAAAAAAAAAAAAAAAA")
                .putString(KEY_TOKEN, "STILL-HERE")
                .commit();
        TokenCipher.setBackendForTests(JceTokenBackend.alwaysFailing("key invalidated"));

        assertEquals("STILL-HERE", App.getToken(appContext()));
        assertTrue("the read failure must be reported, was: " + TokenCipher.lastFallbackReason(),
                TokenCipher.lastFallbackReason() != null
                        && TokenCipher.lastFallbackReason().startsWith("read"));
    }

    /** With no recoverable copy the store returns "" (the app then shows the login form). */
    @Test
    public void undecryptableBlobWithNoPlaintextCopyReturnsEmptyInsteadOfThrowing() {
        TokenCipher.setBackendForTests(JceTokenBackend.working());
        App.saveLogin(appContext(), "http://gw.example:3081", "admin", TOKEN, 1234L);
        assertTrue(prefs().contains(KEY_TOKEN_ENC));

        // The key is gone (restore to a new device, ROM update, ...).
        TokenCipher.setBackendForTests(JceTokenBackend.alwaysFailing("key gone"));

        assertEquals("", App.getToken(appContext()));
        assertNotNull(TokenCipher.lastFallbackReason());
    }

    /**
     * A Keystore that encrypts but cannot decrypt its own output must never be used for
     * storage: the write is verified by decrypting it back before the plaintext is dropped.
     */
    @Test
    public void aBackendThatCannotDecryptItsOwnBlobIsNotUsedForStorage() {
        TokenCipher.setBackendForTests(JceTokenBackend.decryptFailing("no decrypt on this ROM"));

        App.saveLogin(appContext(), "http://gw.example:3081", "admin", TOKEN, 1234L);

        assertEquals(TOKEN, App.getToken(appContext()));
        assertEquals("plaintext", App.tokenStorageMode(appContext()));
        assertFalse(prefs().contains(KEY_TOKEN_ENC));
        assertTrue("the verification failure must be reported, was: " + TokenCipher.lastFallbackReason(),
                TokenCipher.lastFallbackReason().contains("verify"));
    }

    /**
     * The real, unsubstituted production backend. Robolectric genuinely has no AndroidKeyStore
     * provider, which is the same failure a broken ROM produces — so this test asserts the
     * documented graceful degradation against the real code path, with no test seam at all.
     */
    @Test
    public void realAndroidKeyStoreIsUnavailableHereSoTheRealFallbackRuns() {
        TokenCipher.clearBackendForTests();
        assertFalse("this test is only meaningful where the Keystore is missing",
                TokenCipher.available());

        App.saveLogin(appContext(), "http://gw.example:3081", "admin", TOKEN, 1234L);

        assertEquals(TOKEN, App.getToken(appContext()));
        assertEquals("plaintext", App.tokenStorageMode(appContext()));
        assertNotNull(TokenCipher.lastFallbackReason());
        assertTrue("the reason must name the real cause, was: " + TokenCipher.lastFallbackReason(),
                TokenCipher.lastFallbackReason().contains("AndroidKeyStore"));
    }

    // ------------------------------------------------------------------ helpers

    private String prefsXmlPath() {
        return new File(new File(appContext().getDataDir(), "shared_prefs"),
                App.PREFS_NAME + ".xml").getAbsolutePath();
    }

    private String prefsXml() {
        File xml = new File(new File(appContext().getDataDir(), "shared_prefs"),
                App.PREFS_NAME + ".xml");
        if (!xml.exists()) {
            return "";
        }
        try {
            return new String(Files.readAllBytes(xml.toPath()), Charset.forName("UTF-8"));
        } catch (Exception e) {
            return "";
        }
    }

}
