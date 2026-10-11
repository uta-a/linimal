package dev.utaa.linimal.extension.features.readwithoutreceipt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Test;

/** Activity は弱参照の同一性だけを扱うため、所有者には plain JVM の Object を使います。 */
public final class ReadWithoutReceiptForegroundTrackerTest {
    @After
    public void resetState() {
        ReadWithoutReceiptHooks.resetForTesting();
    }

    private static boolean blocks() {
        return ReadWithoutReceiptHooks.shouldBlockGivenEnabled("chat-1", 10_000_000L, true);
    }

    private static void create(Object activity, String chatId, long token) {
        ReadWithoutReceiptHooks.onChatCreatedObserved(activity, chatId, token, 1L,
                true, true, ReadWithoutReceiptHooks.currentSuppression());
    }

    private static Object open() {
        Object activity = new Object();
        ReadWithoutReceiptHooks.markSuppressedAt("chat-1", 0L);
        create(activity, "chat-1", 0L);
        return activity;
    }

    @Test
    public void pauseResumeAndNonFinishingStopKeepTheViewingSession() {
        Object activity = open();
        long token = ReadWithoutReceiptHooks.sessionTokenFor(activity);
        ReadWithoutReceiptForegroundTracker.Callbacks callbacks = new ReadWithoutReceiptForegroundTracker.Callbacks();
        // pause/resume は Activity の中身を読まず、所有者を変更しません。
        callbacks.onActivityPaused(null);
        ReadWithoutReceiptForegroundTracker.noteStopped(activity, false, false);
        callbacks.onActivityResumed(null);
        assertTrue(blocks());
        assertEquals(token, ReadWithoutReceiptHooks.sessionTokenFor(activity));
    }

    @Test
    public void imageViewerLifecycleDoesNotEndTheChatSession() {
        Object activity = open();
        Object viewer = new Object();
        ReadWithoutReceiptForegroundTracker.noteStopped(activity, false, false);
        ReadWithoutReceiptForegroundTracker.noteStopped(viewer, true, false);
        ReadWithoutReceiptForegroundTracker.noteDestroyed(viewer, false);
        assertTrue(blocks());
        assertTrue(ReadWithoutReceiptHooks.sessionTokenFor(activity) > 0L);
    }

    @Test
    public void matchingFinishingStopEndsTheSession() {
        Object activity = open();
        ReadWithoutReceiptForegroundTracker.noteStopped(activity, true, false);
        assertFalse(blocks());
    }

    @Test
    public void matchingDestroyEndsTheSessionEvenWithoutFinishingStop() {
        Object activity = open();
        ReadWithoutReceiptForegroundTracker.noteDestroyed(activity, false);
        assertFalse(blocks());
    }

    @Test
    public void configurationChangeTransfersOnlySavedTokenAndMatchingChat() {
        Object oldActivity = open();
        ReadWithoutReceiptForegroundTracker.noteStopped(oldActivity, false, true);
        // Android 9 以降の stop 後 save でも token を取得できます。
        long token = ReadWithoutReceiptHooks.sessionTokenFor(oldActivity);
        assertTrue(token > 0L);
        ReadWithoutReceiptForegroundTracker.noteDestroyed(oldActivity, true);
        assertTrue(blocks());
        assertEquals(0L, ReadWithoutReceiptHooks.sessionTokenFor(oldActivity));
        Object newActivity = new Object();
        create(newActivity, "chat-1", token);
        assertTrue(blocks());
        assertEquals(token, ReadWithoutReceiptHooks.sessionTokenFor(newActivity));
        ReadWithoutReceiptForegroundTracker.noteDestroyed(oldActivity, false);
        assertTrue(blocks());
        ReadWithoutReceiptForegroundTracker.noteDestroyed(newActivity, false);
        assertFalse(blocks());
    }

    @Test
    public void savedTokenCannotStealAnAttachedOwner() {
        Object activity = open();
        long token = ReadWithoutReceiptHooks.sessionTokenFor(activity);
        Object impostor = new Object();
        create(impostor, "chat-1", token);
        assertEquals(token, ReadWithoutReceiptHooks.sessionTokenFor(activity));
        assertEquals(0L, ReadWithoutReceiptHooks.sessionTokenFor(impostor));
        ReadWithoutReceiptForegroundTracker.noteDestroyed(impostor, false);
        assertTrue(blocks());
    }

    @Test
    public void matchingTokenWithWrongChatDoesNotRestoreSuppression() {
        Object activity = open();
        long token = ReadWithoutReceiptHooks.sessionTokenFor(activity);
        ReadWithoutReceiptForegroundTracker.noteDestroyed(activity, true);
        create(new Object(), "chat-2", token);
        assertFalse(blocks());
    }

    @Test
    public void missingSavedTokenIsNormalReentryEvenAfterConfigurationDetach() {
        Object activity = open();
        ReadWithoutReceiptForegroundTracker.noteDestroyed(activity, true);
        create(new Object(), "chat-1", 0L);
        assertFalse(blocks());
    }

    @Test
    public void restoreRegistrationFailureDiscardsDetachedSession() {
        Object activity = open();
        long token = ReadWithoutReceiptHooks.sessionTokenFor(activity);
        ReadWithoutReceiptForegroundTracker.noteDestroyed(activity, true);
        ReadWithoutReceiptHooks.onChatCreatedObserved(new Object(), "chat-1", token, 1L,
                true, false, ReadWithoutReceiptHooks.currentSuppression());
        assertFalse(blocks());
    }

    @Test
    public void oldRestoreAndOldActivityEndCannotClearNewSession() {
        Object oldActivity = open();
        long oldToken = ReadWithoutReceiptHooks.sessionTokenFor(oldActivity);
        ReadWithoutReceiptForegroundTracker.noteDestroyed(oldActivity, true);
        Object newActivity = open();
        create(new Object(), "chat-1", oldToken);
        ReadWithoutReceiptForegroundTracker.noteStopped(oldActivity, true, false);
        ReadWithoutReceiptForegroundTracker.noteDestroyed(oldActivity, false);
        assertTrue(blocks());
        assertTrue(ReadWithoutReceiptHooks.sessionTokenFor(newActivity) > oldToken);
    }

    @Test
    public void oldEndSnapshotCannotClearTransferredSession() {
        Object oldActivity = open();
        long token = ReadWithoutReceiptHooks.sessionTokenFor(oldActivity);
        ReadWithoutReceiptHooks.Suppression observed = ReadWithoutReceiptHooks.currentSuppression();
        ReadWithoutReceiptForegroundTracker.noteDestroyed(oldActivity, true);
        Object newActivity = new Object();
        create(newActivity, "chat-1", token);
        ReadWithoutReceiptHooks.endActivityObserved(oldActivity, false, true, observed);
        assertTrue(blocks());
        assertEquals(token, ReadWithoutReceiptHooks.sessionTokenFor(newActivity));
    }

    @Test
    public void oldRestoreSnapshotCannotReattachAfterAReplacement() {
        Object oldActivity = open();
        long token = ReadWithoutReceiptHooks.sessionTokenFor(oldActivity);
        ReadWithoutReceiptForegroundTracker.noteDestroyed(oldActivity, true);
        ReadWithoutReceiptHooks.Suppression detached = ReadWithoutReceiptHooks.currentSuppression();
        Object newActivity = open();
        ReadWithoutReceiptHooks.onChatCreatedObserved(new Object(), "chat-1", token, 1L,
                true, true, detached);
        assertTrue(blocks());
        assertTrue(ReadWithoutReceiptHooks.sessionTokenFor(newActivity) > token);
    }

    @Test
    public void disablingDetachedSessionPreventsRestorationAfterReenable() {
        Object activity = open();
        long token = ReadWithoutReceiptHooks.sessionTokenFor(activity);
        ReadWithoutReceiptForegroundTracker.noteDestroyed(activity, true);
        assertFalse(ReadWithoutReceiptHooks.shouldBlockGivenEnabled("chat-1", 1L, false));
        create(new Object(), "chat-1", token);
        assertFalse(blocks());
    }

    @Test
    public void failedRegistrationCanRetryAndSuccessRegistersOnlyOnce() {
        ReadWithoutReceiptForegroundTracker.Registration registration = new ReadWithoutReceiptForegroundTracker.Registration();
        AtomicInteger attempts = new AtomicInteger();
        assertFalse(registration.ensure(() -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("callback 登録失敗");
        }));
        assertTrue(registration.ensure(attempts::incrementAndGet));
        assertTrue(registration.ensure(attempts::incrementAndGet));
        assertEquals(2, attempts.get());
    }
}
