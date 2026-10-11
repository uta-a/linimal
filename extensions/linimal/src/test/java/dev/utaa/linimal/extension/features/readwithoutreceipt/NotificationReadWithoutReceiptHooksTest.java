package dev.utaa.linimal.extension.features.readwithoutreceipt;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

public class NotificationReadWithoutReceiptHooksTest {
    public static final class Navigation {
        public final boolean marker;
        public Navigation(boolean marker) { this.marker = marker; }
    }
    public static final class Request {
        private final String chatId;
        private final Navigation navigation;
        public Request(String chatId, boolean notification) {
            this.chatId = chatId;
            navigation = new Navigation(notification);
        }
        public String getChatId() { return chatId; }
        public Navigation getBackNavigation() { return navigation; }
    }
    public static final class AmbiguousNavigation {
        public boolean first = true;
        public boolean second = true;
    }
    public static final class AmbiguousRequest {
        public AmbiguousNavigation getBackNavigation() { return new AmbiguousNavigation(); }
        public String getChatId() { return "room"; }
    }
    @After public void reset() { ReadWithoutReceiptHooks.resetForTesting(); }

    @Test public void notificationOnlySettingBlocksUntilRoomCloses() throws Exception {
        NotificationReadWithoutReceiptHooks.onChatLaunchRequest(new Request("room", true), 100, true);
        Object activity = new Object();
        ReadWithoutReceiptHooks.onChatCreatedObserved(activity, "room", 0, 200, true, true,
                ReadWithoutReceiptHooks.currentSuppression());
        assertTrue(blocks("room", 2000000, false, true));
        assertFalse(blocks("another", 2000000, false, true));
        ReadWithoutReceiptHooks.endActivity(activity, false, true);
        assertFalse(blocks("room", 2000001, false, true));
    }
    @Test public void ordinaryEntryAndDisabledNotificationDoNotReserve() throws Exception {
        NotificationReadWithoutReceiptHooks.onChatLaunchRequest(new Request("room", false), 100, true);
        assertNull(ReadWithoutReceiptHooks.currentSuppression());
        NotificationReadWithoutReceiptHooks.onChatLaunchRequest(new Request("room", true), 100, false);
        assertNull(ReadWithoutReceiptHooks.currentSuppression());
    }
    @Test public void configurationRestoreKeepsNotificationSource() throws Exception {
        NotificationReadWithoutReceiptHooks.onChatLaunchRequest(new Request("room", true), 100, true);
        Object old = new Object();
        ReadWithoutReceiptHooks.onChatCreatedObserved(old, "room", 0, 200, true, true,
                ReadWithoutReceiptHooks.currentSuppression());
        long token = ReadWithoutReceiptHooks.sessionTokenFor(old);
        ReadWithoutReceiptHooks.endActivity(old, true, false);
        Object next = new Object();
        ReadWithoutReceiptHooks.onChatCreatedObserved(next, "room", token, 2000000, true, true,
                ReadWithoutReceiptHooks.currentSuppression());
        assertTrue(blocks("room", 2000001, false, true));
        ReadWithoutReceiptHooks.clearDisabledSuppression(true, false);
        assertFalse(blocks("room", 2000002, true, true));
    }
    @Test public void normalReentryDoesNotReviveNotificationSession() throws Exception {
        NotificationReadWithoutReceiptHooks.onChatLaunchRequest(new Request("room", true), 100, true);
        ReadWithoutReceiptHooks.onChatCreatedObserved(new Object(), "room", 0, 200, true, true,
                ReadWithoutReceiptHooks.currentSuppression());
        ReadWithoutReceiptHooks.onChatCreatedObserved(new Object(), "room", 0, 300, true, true,
                ReadWithoutReceiptHooks.currentSuppression());
        assertFalse(blocks("room", 301, false, true));
    }
    @Test public void sourceGatesDoNotEnableEachOther() throws Exception {
        NotificationReadWithoutReceiptHooks.onChatLaunchRequest(new Request("room", true), 100, true);
        assertFalse(blocks("room", 101, true, false));
        assertFalse(blocks("room", 102, true, true));
        ReadWithoutReceiptHooks.markSuppressedAt("room", 100);
        assertFalse(blocks("room", 101, false, true));
        assertFalse(blocks("room", 102, true, true));
    }
    @Test public void staleSourceCheckCannotBlockDisabledReplacement() throws Exception {
        ReadWithoutReceiptHooks.markSuppressedAt("room", 100);
        ReadWithoutReceiptHooks.Suppression old = ReadWithoutReceiptHooks.currentSuppression();
        NotificationReadWithoutReceiptHooks.onChatLaunchRequest(new Request("room", true), 100, true);
        assertFalse(ReadWithoutReceiptHooks.shouldBlockGivenSources("room", 101, old, true, false));
    }
    @Test public void pendingNotificationExpiresWithoutCreation() throws Exception {
        NotificationReadWithoutReceiptHooks.onChatLaunchRequest(new Request("room", true), 100, true);
        assertFalse(blocks("room", 1800101, false, true));
    }
    @Test public void malformedMarkerNeverCreatesSuppression() throws Exception {
        assertFalse(NotificationReadWithoutReceiptHooks.isFromNotification(null));
        try {
            NotificationReadWithoutReceiptHooks.onChatLaunchRequest(new AmbiguousRequest(), 100, true);
            fail("ambiguous marker must be rejected");
        } catch (NoSuchFieldException expected) {
            assertNull(ReadWithoutReceiptHooks.currentSuppression());
        }
    }
    @Test public void ordinaryLaunchPreservesManualReservation() throws Exception {
        ReadWithoutReceiptHooks.markSuppressedAt("room", 100);
        NotificationReadWithoutReceiptHooks.onChatLaunchRequest(new Request("room", false), 101, true);
        assertTrue(blocks("room", 102, true, false));
    }
    private static boolean blocks(String chat, long now, boolean manual, boolean notification) {
        return ReadWithoutReceiptHooks.shouldBlockGivenSources(chat, now,
                ReadWithoutReceiptHooks.currentSuppression(), manual, notification);
    }
}
