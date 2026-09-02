package dev.utaa.linimal.extension.features.readwithoutreceipt;

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
}
