package dev.utaa.linimal.extension.features.readwithoutreceipt;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

/**
 * 前面 Activity の観測と、それに連動した抑制解除の検証です。Activity は参照の同一性しか見ないため、
 * Android のクラスを使わずただの Object を token として渡します。
 */
public final class ReadWithoutReceiptForegroundTrackerTest {
    @After
    public void resetState() {
        ReadWithoutReceiptHooks.resetForTesting();
    }

    @Test
    public void withoutAnyResumedActivityThereIsNoCurrentActivity() {
        assertNull(ReadWithoutReceiptForegroundTracker.currentActivity());
    }

    @Test
    public void theLastResumedActivityBecomesTheCurrentOne() {
        Object chatList = new Object();
        Object chatRoom = new Object();

        ReadWithoutReceiptForegroundTracker.noteResumed(chatList);
        ReadWithoutReceiptForegroundTracker.noteResumed(chatRoom);

        assertSame(chatRoom, ReadWithoutReceiptForegroundTracker.currentActivity());
    }

    @Test
    public void leavingTheChatRoomReleasesTheSuppression() {
        Object chatRoom = new Object();
        ReadWithoutReceiptHooks.markSuppressed("chat-1");
        ReadWithoutReceiptForegroundTracker.noteResumed(chatRoom);
        // 既読処理が最初に止められた時点で、前面の Activity がトーク画面として記録されます。
        ReadWithoutReceiptHooks.captureViewingActivity();
        assertTrue(ReadWithoutReceiptHooks.shouldBlockGivenEnabled("chat-1", 0L, true));

        ReadWithoutReceiptForegroundTracker.notePaused(chatRoom);

        assertFalse(ReadWithoutReceiptHooks.shouldBlockGivenEnabled("chat-1", 0L, true));
    }

    @Test
    public void leavingAnUnrelatedActivityKeepsTheSuppression() {
        Object chatRoom = new Object();
        Object other = new Object();
        ReadWithoutReceiptHooks.markSuppressed("chat-1");
        ReadWithoutReceiptForegroundTracker.noteResumed(chatRoom);
        ReadWithoutReceiptHooks.captureViewingActivity();

        ReadWithoutReceiptForegroundTracker.notePaused(other);

        assertTrue(ReadWithoutReceiptHooks.shouldBlockGivenEnabled("chat-1", 0L, true));
    }

    @Test
    public void theChatListLeavingBeforeTheChatRoomAppearsKeepsTheSuppression() {
        // トークを開く直前にトーク一覧が pause します。まだトーク画面を記録していないので解除しません。
        Object chatList = new Object();
        ReadWithoutReceiptForegroundTracker.noteResumed(chatList);
        ReadWithoutReceiptHooks.markSuppressed("chat-1");

        ReadWithoutReceiptForegroundTracker.notePaused(chatList);

        assertTrue(ReadWithoutReceiptHooks.shouldBlockGivenEnabled("chat-1", 0L, true));
    }

    @Test
    public void anIntermediateActivityIsNotMistakenForTheChatRoom() {
        // scheme を処理する透過 Activity が一瞬前面に出ても、既読処理が走るのはトーク画面が
        // 前面に来てからです。記録されるのはそのときの Activity だけになります。
        Object scheme = new Object();
        Object chatRoom = new Object();
        ReadWithoutReceiptHooks.markSuppressed("chat-1");
        ReadWithoutReceiptForegroundTracker.noteResumed(scheme);
        ReadWithoutReceiptForegroundTracker.notePaused(scheme);
        ReadWithoutReceiptForegroundTracker.noteResumed(chatRoom);
        ReadWithoutReceiptHooks.captureViewingActivity();

        ReadWithoutReceiptForegroundTracker.notePaused(scheme);
        assertTrue(ReadWithoutReceiptHooks.shouldBlockGivenEnabled("chat-1", 0L, true));

        ReadWithoutReceiptForegroundTracker.notePaused(chatRoom);
        assertFalse(ReadWithoutReceiptHooks.shouldBlockGivenEnabled("chat-1", 0L, true));
    }

    @Test
    public void withoutAnObservedActivityTheSuppressionSurvivesUntilTheTimeout() {
        // Application を取得できない端末では前面 Activity を観測できません。その場合でも
        // 抑制は成立し、タイムアウトだけが解除条件になります。
        ReadWithoutReceiptHooks.markSuppressedAt("chat-1", 0L);
        ReadWithoutReceiptHooks.captureViewingActivity();

        ReadWithoutReceiptForegroundTracker.notePaused(new Object());

        assertTrue(ReadWithoutReceiptHooks.shouldBlockGivenEnabled("chat-1", 1L, true));
        assertFalse(ReadWithoutReceiptHooks.shouldBlockGivenEnabled(
                "chat-1", ReadWithoutReceiptHooks.SUPPRESSION_TIMEOUT_MILLIS + 1L, true));
    }

    @Test
    public void openingAnotherChatRetargetsTheViewingActivity() {
        Object firstRoom = new Object();
        Object secondRoom = new Object();
        ReadWithoutReceiptHooks.markSuppressed("chat-1");
        ReadWithoutReceiptForegroundTracker.noteResumed(firstRoom);
        ReadWithoutReceiptHooks.captureViewingActivity();

        ReadWithoutReceiptHooks.markSuppressed("chat-2");
        ReadWithoutReceiptForegroundTracker.noteResumed(secondRoom);
        ReadWithoutReceiptHooks.captureViewingActivity();

        // 古いトーク画面が離れても、新しい抑制は解除されません。
        ReadWithoutReceiptForegroundTracker.notePaused(firstRoom);
        assertTrue(ReadWithoutReceiptHooks.shouldBlockGivenEnabled("chat-2", 0L, true));

        ReadWithoutReceiptForegroundTracker.notePaused(secondRoom);
        assertFalse(ReadWithoutReceiptHooks.shouldBlockGivenEnabled("chat-2", 0L, true));
    }
}
