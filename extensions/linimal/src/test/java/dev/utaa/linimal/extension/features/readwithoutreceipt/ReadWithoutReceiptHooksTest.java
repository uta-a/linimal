package dev.utaa.linimal.extension.features.readwithoutreceipt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public final class ReadWithoutReceiptHooksTest {
    @After
    public void resetState() {
        ReadWithoutReceiptHooks.resetForTesting();
    }

    @Test
    public void withoutAnyMarkedChatEverythingFailsOpen() {
        assertFalse(ReadWithoutReceiptHooks.shouldBlockGivenEnabled("chat-1", 0L, true));
    }

    @Test
    public void aMarkedChatIsBlockedWhileEnabled() {
        ReadWithoutReceiptHooks.markSuppressed("chat-1");

        assertTrue(ReadWithoutReceiptHooks.shouldBlockGivenEnabled("chat-1", 0L, true));
    }

    @Test
    public void aDifferentChatIsNeverBlocked() {
        ReadWithoutReceiptHooks.markSuppressed("chat-1");

        assertFalse(ReadWithoutReceiptHooks.shouldBlockGivenEnabled("chat-2", 0L, true));
    }

    @Test
    public void disablingTheFeatureFailsOpenEvenForTheMarkedChat() {
        ReadWithoutReceiptHooks.markSuppressed("chat-1");

        assertFalse(ReadWithoutReceiptHooks.shouldBlockGivenEnabled("chat-1", 0L, false));
    }

    @Test
    public void aNullChatIdIsNeverBlocked() {
        ReadWithoutReceiptHooks.markSuppressed("chat-1");

        assertFalse(ReadWithoutReceiptHooks.shouldBlockGivenEnabled(null, 0L, true));
    }

    @Test
    public void openingANewChatReplacesTheOldSuppression() {
        ReadWithoutReceiptHooks.markSuppressed("chat-1");
        ReadWithoutReceiptHooks.markSuppressed("chat-2");

        assertFalse(ReadWithoutReceiptHooks.shouldBlockGivenEnabled("chat-1", 0L, true));
        assertTrue(ReadWithoutReceiptHooks.shouldBlockGivenEnabled("chat-2", 0L, true));
    }

    @Test
    public void suppressionExpiresAfterTheTimeout() {
        long markedAt = 10_000L;
        ReadWithoutReceiptHooks.markSuppressedAt("chat-1", markedAt);

        assertTrue(ReadWithoutReceiptHooks.shouldBlockGivenEnabled(
                "chat-1", markedAt + ReadWithoutReceiptHooks.SUPPRESSION_TIMEOUT_MILLIS, true));
        assertFalse(ReadWithoutReceiptHooks.shouldBlockGivenEnabled(
                "chat-1", markedAt + ReadWithoutReceiptHooks.SUPPRESSION_TIMEOUT_MILLIS + 1, true));
    }

    @Test
    public void anExpiredSuppressionDoesNotReviveOnASecondCheck() {
        long markedAt = 10_000L;
        ReadWithoutReceiptHooks.markSuppressedAt("chat-1", markedAt);
        long expiredAt = markedAt + ReadWithoutReceiptHooks.SUPPRESSION_TIMEOUT_MILLIS + 1;

        assertFalse(ReadWithoutReceiptHooks.shouldBlockGivenEnabled("chat-1", expiredAt, true));
        // 期限切れの抑制は消えているため、時刻を巻き戻しても復活しません。
        assertFalse(ReadWithoutReceiptHooks.shouldBlockGivenEnabled("chat-1", 0L, true));
    }

    @Test
    public void markingANullChatIdLeavesTheExistingSuppressionUntouched() {
        ReadWithoutReceiptHooks.markSuppressed("chat-1");
        ReadWithoutReceiptHooks.markSuppressed(null);

        assertTrue(ReadWithoutReceiptHooks.shouldBlockGivenEnabled("chat-1", 0L, true));
    }

    @Test
    public void theRealEntryPointFailsOpenWithoutConfiguration() {
        // この単体テストでは LinimalConfig を初期化しないため、runtime の初期値は fail-open です。
        ReadWithoutReceiptHooks.markSuppressed("chat-1");

        assertFalse(ReadWithoutReceiptHooks.shouldBlockMarkAsRead("chat-1"));
    }

    @Test
    public void theRealEntryPointNeverThrowsForANullChatId() {
        assertFalse(ReadWithoutReceiptHooks.shouldBlockMarkAsRead(null));
    }

    @Test
    public void aNewSuppressionIsNotExpiredByTheAgeOfThePreviousOne() {
        long staleAt = 10_000L;
        ReadWithoutReceiptHooks.markSuppressedAt("chat-1", staleAt);
        // 前回の登録から timeout を大きく超えた時点で、別のトークを開き直します。
        long freshAt = staleAt + ReadWithoutReceiptHooks.SUPPRESSION_TIMEOUT_MILLIS * 3;
        ReadWithoutReceiptHooks.markSuppressedAt("chat-2", freshAt);

        assertTrue(ReadWithoutReceiptHooks.shouldBlockGivenEnabled("chat-2", freshAt, true));
        // トーク ID と時刻は 1 instance に閉じており、「新しいトーク ID + 古い時刻」は観測できません。
        ReadWithoutReceiptHooks.Suppression observed = ReadWithoutReceiptHooks.currentSuppression();
        assertEquals("chat-2", observed.chatId);
        assertEquals(freshAt, observed.atMillis);
    }

    @Test
    public void anObservedSuppressionIsJudgedWithItsOwnTimestamp() {
        long staleAt = 10_000L;
        ReadWithoutReceiptHooks.markSuppressedAt("chat-1", staleAt);
        ReadWithoutReceiptHooks.Suppression stale = ReadWithoutReceiptHooks.currentSuppression();

        long freshAt = staleAt + ReadWithoutReceiptHooks.SUPPRESSION_TIMEOUT_MILLIS * 3;
        ReadWithoutReceiptHooks.markSuppressedAt("chat-1", freshAt);
        ReadWithoutReceiptHooks.Suppression fresh = ReadWithoutReceiptHooks.currentSuppression();

        // 同じトーク・同じ現在時刻でも、判定はそれぞれが観測した instance の時刻だけを見ます。
        assertFalse(ReadWithoutReceiptHooks.shouldBlockGivenObserved("chat-1", freshAt, stale));
        assertTrue(ReadWithoutReceiptHooks.shouldBlockGivenObserved("chat-1", freshAt, fresh));
    }

    @Test
    public void expiringAnObservedSuppressionKeepsALaterSuppressionForAnotherChat() {
        long markedAt = 10_000L;
        ReadWithoutReceiptHooks.markSuppressedAt("chat-1", markedAt);
        ReadWithoutReceiptHooks.Suppression observed = ReadWithoutReceiptHooks.currentSuppression();

        // 観測から期限切れの消去までの間に、別スレッドが新しい抑制を登録した状況です。
        long expiredAt = markedAt + ReadWithoutReceiptHooks.SUPPRESSION_TIMEOUT_MILLIS + 1;
        ReadWithoutReceiptHooks.markSuppressedAt("chat-2", expiredAt);

        assertFalse(ReadWithoutReceiptHooks.shouldBlockGivenObserved("chat-1", expiredAt, observed));
        // 消してよいのは観測した instance だけで、後から登録された抑制は残ります。
        assertTrue(ReadWithoutReceiptHooks.shouldBlockGivenEnabled("chat-2", expiredAt, true));
    }

    @Test
    public void expiringAnObservedSuppressionKeepsALaterSuppressionForTheSameChat() {
        long markedAt = 10_000L;
        ReadWithoutReceiptHooks.markSuppressedAt("chat-1", markedAt);
        ReadWithoutReceiptHooks.Suppression observed = ReadWithoutReceiptHooks.currentSuppression();

        // 同じトークを開き直した場合も、古い観測に基づく消去は新しい抑制へ影響しません。
        long expiredAt = markedAt + ReadWithoutReceiptHooks.SUPPRESSION_TIMEOUT_MILLIS + 1;
        ReadWithoutReceiptHooks.markSuppressedAt("chat-1", expiredAt);

        assertFalse(ReadWithoutReceiptHooks.shouldBlockGivenObserved("chat-1", expiredAt, observed));
        assertTrue(ReadWithoutReceiptHooks.shouldBlockGivenEnabled("chat-1", expiredAt, true));
    }

    @Test
    public void anAbsentObservationIsNeverBlocked() {
        ReadWithoutReceiptHooks.markSuppressed("chat-1");

        assertFalse(ReadWithoutReceiptHooks.shouldBlockGivenObserved("chat-1", 0L, null));
    }
}
