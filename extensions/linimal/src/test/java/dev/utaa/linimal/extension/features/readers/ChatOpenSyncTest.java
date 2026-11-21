package dev.utaa.linimal.extension.features.readers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public final class ChatOpenSyncTest {
    @After
    public void reset() {
        ChatOpenSync.resetForTesting();
    }

    @Test
    public void syncsOnlyWhenTheSettingIsOnTheBridgeExistsAndTheChatHasMembers() {
        assertTrue(ChatOpenSync.shouldSync(true, true, "c1"));
        assertTrue(ChatOpenSync.shouldSync(true, true, "r1"));
        // 設定 OFF のときは通信しません。
        assertFalse(ChatOpenSync.shouldSync(false, true, "c1"));
        // 同期用の生成メソッドがない build では何もしません。
        assertFalse(ChatOpenSync.shouldSync(true, false, "c1"));
        assertFalse(ChatOpenSync.shouldSync(true, true, "u1"));
        assertFalse(ChatOpenSync.shouldSync(true, true, null));
    }

    @Test
    public void eachChatIsSyncedOnceWhileTheSameScreenStaysOpen() {
        Object screen = new Object();
        assertTrue(ChatOpenSync.markFirstOpen(screen, "c1"));
        // 同じ画面での以降の bind（スクロールや新着）では同期しません。
        assertFalse(ChatOpenSync.markFirstOpen(screen, "c1"));
        assertFalse(ChatOpenSync.markFirstOpen(screen, "c1"));
        // 同じ画面で別のトークに切り替わったときは同期します。
        assertTrue(ChatOpenSync.markFirstOpen(screen, "c2"));
    }

    @Test
    public void reopeningTheChatOnANewScreenSyncsAgain() {
        assertTrue(ChatOpenSync.markFirstOpen(new Object(), "c1"));
        assertTrue(ChatOpenSync.markFirstOpen(new Object(), "c1"));
    }

    @Test
    public void missingScreenOrChatIsNeverMarked() {
        assertFalse(ChatOpenSync.markFirstOpen(null, "c1"));
        assertFalse(ChatOpenSync.markFirstOpen(new Object(), null));
    }
}
