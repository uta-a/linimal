package dev.utaa.linimal.extension.features.readwithoutreceipt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.Test;

public final class ReadWithoutReceiptManualHooksTest {
    @After
    public void reset() {
        ReadWithoutReceiptHooks.resetForTesting();
    }

    @Test
    public void selectedReadIsCumulativeButDoesNotConsumeLaterMessages() {
        ReadWithoutReceiptManualHooks.State state = new ReadWithoutReceiptManualHooks.State();
        state.observeBaseline(null);
        assertFalse(state.begin(0L, false));
        assertFalse(state.begin(-1L, false));
        assertTrue(state.begin(42L, false));
        assertFalse(state.begin(43L, false));
        assertFalse(state.begin(0L, true));
        state.finish(42L);
        assertTrue(state.isRead(41L));
        assertTrue(state.isRead(42L));
        assertFalse(state.isRead(43L));
        assertFalse(state.begin(41L, false));
        assertTrue(state.begin(43L, false));
    }

    @Test
    public void failureReleasesTheActionWithoutAdvancingItsReadPoint() {
        ReadWithoutReceiptManualHooks.State state = new ReadWithoutReceiptManualHooks.State();
        state.observeBaseline("");
        assertTrue(state.begin(42L, false));
        state.finish(0L);
        assertFalse(state.isRead(42L));
        assertFalse(state.isPending());
        assertTrue(state.begin(42L, false));
        state.finish(42L);
        assertTrue(state.begin(0L, true));
        state.finish(0L);
        assertTrue(state.isRead(42L));
        assertFalse(state.isRead(43L));
        assertTrue(state.begin(0L, true));
        state.finish(50L);
        assertTrue(state.isRead(50L));
        assertFalse(state.isRead(51L));
    }

    @Test
    public void rotationRetainsManualProgressAndSuppressionButReentryDoesNot() {
        Object first = new Object();
        ReadWithoutReceiptHooks.markSuppressedAt("chat", 0L);
        create(first, 0L);
        ReadWithoutReceiptHooks.Suppression session = ReadWithoutReceiptHooks.currentSuppression();
        session.manualState.observeBaseline("40");
        assertTrue(session.manualState.begin(42L, false));
        session.manualState.finish(42L);
        ReadWithoutReceiptHooks.endActivity(first, true, true);
        Object restored = new Object();
        create(restored, session.token);
        ReadWithoutReceiptHooks.Suppression transferred = ReadWithoutReceiptHooks.currentSuppression();
        assertSame(session.manualState, transferred.manualState);
        assertTrue(transferred.manualState.isRead(42L));
        assertEquals(0, transferred.manualState.presentation(40L, false));
        assertEquals(4, transferred.manualState.presentation(42L, true));
        transferred.manualState.observeBaseline("999");
        assertEquals(1, transferred.manualState.presentation(43L, true));
        assertTrue(ReadWithoutReceiptHooks.shouldBlockGivenObserved("chat", 1L, transferred));
        ReadWithoutReceiptHooks.endActivity(restored, false, true);
        ReadWithoutReceiptHooks.markSuppressedAt("chat", 2L);
        create(new Object(), 0L);
        assertFalse(ReadWithoutReceiptHooks.currentSuppression().manualState.isRead(42L));
        assertEquals(-1, ReadWithoutReceiptHooks.currentSuppression().manualState.presentation(43L, true));
    }

    @Test
    public void manualPermissionIsLimitedToOneChatAndWorkerAndNeverEndsTheSession() throws Exception {
        ReadWithoutReceiptHooks.markSuppressedAt("chat", 0L);
        create(new Object(), 0L);
        ReadWithoutReceiptHooks.Suppression session = ReadWithoutReceiptHooks.currentSuppression();
        ReadWithoutReceiptManualHooks.invokeRead(session, new Executor(() -> {
            assertFalse(ReadWithoutReceiptHooks.shouldBlockMarkAsRead("chat"));
            assertTrue(ReadWithoutReceiptHooks.shouldBlockMarkAsRead("other"));
            AtomicBoolean escaped = new AtomicBoolean(true);
            Thread other = new Thread(() -> escaped.set(ReadWithoutReceiptManualHooks.isManualRead("chat")));
            other.start();
            try {
                other.join();
            } catch (InterruptedException error) {
                throw new AssertionError(error);
            }
            assertFalse(escaped.get());
        }), 42L, false);
        assertFalse(ReadWithoutReceiptManualHooks.hasManualInvocation());
        assertSame(session, ReadWithoutReceiptHooks.currentSuppression());
        assertTrue(ReadWithoutReceiptHooks.shouldBlockGivenObserved("chat", 1L, session));
    }

    @Test
    public void permissionRevokesOnSessionReplacementAndIsClearedEvenWhenTheReaderThrows() throws Exception {
        ReadWithoutReceiptHooks.markSuppressedAt("chat", 0L);
        create(new Object(), 0L);
        ReadWithoutReceiptHooks.Suppression old = ReadWithoutReceiptHooks.currentSuppression();
        try {
            ReadWithoutReceiptManualHooks.invokeRead(old, new Executor(() -> {
                ReadWithoutReceiptHooks.markSuppressedAt("chat", 0L);
                create(new Object(), 0L);
                assertTrue(ReadWithoutReceiptHooks.shouldBlockMarkAsRead("chat"));
                throw new IllegalStateException("reader failed");
            }), 42L, false);
            fail("Reader failure must propagate to the worker's failure handler");
        } catch (InvocationTargetException expected) {
            assertTrue(expected.getCause() instanceof IllegalStateException);
        }
        assertFalse(ReadWithoutReceiptManualHooks.hasManualInvocation());
        assertEquals(0L, ReadWithoutReceiptManualHooks.invokeRead(old, new Executor(() -> {
            throw new AssertionError("stale action executed");
        }), 42L, false));
    }

    @Test
    public void nativeReadPositionSeparatesHistoryFromUnreadAndMovesOnlyAfterManualCompletion() {
        ReadWithoutReceiptManualHooks.State state = new ReadWithoutReceiptManualHooks.State();
        assertEquals(-1, state.presentation(101L, true));
        assertFalse(state.begin(101L, false));
        state.observeBaseline("100");
        assertEquals(0, state.presentation(90L, false));
        assertEquals(0, state.presentation(100L, false));
        assertEquals(3, state.presentation(101L, true));
        assertEquals(1, state.presentation(102L, false));
        assertTrue(state.isRead(100L));
        assertFalse(state.isRead(101L));
        assertFalse(state.begin(100L, false));
        assertTrue(state.begin(101L, false));
        state.finish(101L);
        assertEquals(4, state.presentation(101L, true));
        assertEquals(1, state.presentation(102L, true));
        state.observeBaseline("200");
        assertEquals(1, state.presentation(102L, false));
        state.finish(99L);
        assertEquals(4, state.presentation(101L, false));
    }

    @Test
    public void missingReadUpMeansKnownNoReadButMalformedReadUpRemainsUnknown() {
        ReadWithoutReceiptManualHooks.State missing = new ReadWithoutReceiptManualHooks.State();
        missing.observeBaseline(null);
        assertEquals(3, missing.presentation(1L, true));
        ReadWithoutReceiptManualHooks.State empty = new ReadWithoutReceiptManualHooks.State();
        empty.observeBaseline("");
        assertEquals(1, empty.presentation(1L, false));
        ReadWithoutReceiptManualHooks.State malformed = new ReadWithoutReceiptManualHooks.State();
        malformed.observeBaseline("invalid");
        assertEquals(-1, malformed.presentation(1L, true));
        malformed.observeBaseline("-1");
        assertEquals(-1, malformed.presentation(1L, true));
        malformed.observeBaseline("100");
        assertEquals(0, malformed.presentation(1L, true));
    }

    public static final class Executor {
        private final Runnable action;

        Executor(Runnable action) {
            this.action = action;
        }

        public long linimalMarkReadManually(long point, String chatId, boolean all) {
            action.run();
            return point;
        }
    }

    private static void create(Object activity, long token) {
        ReadWithoutReceiptHooks.onChatCreatedObserved(activity, "chat", token, 3L,
                true, true, ReadWithoutReceiptHooks.currentSuppression());
    }
}
