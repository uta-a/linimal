package dev.utaa.linimal.extension.features.readwithoutreceipt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public final class ChatListMenuHooksTest {
    @After
    public void resetState() {
        ChatListMenuHooks.resetForTesting();
    }

    @Test
    public void theRowIsShownForAKnownChatWhileEnabled() {
        assertTrue(ChatListMenuHooks.shouldShowRowGivenEnabled("chat-1", true));
    }

    @Test
    public void disablingTheFeatureHidesTheRow() {
        assertFalse(ChatListMenuHooks.shouldShowRowGivenEnabled("chat-1", false));
    }

    @Test
    public void aMissingChatIdHidesTheRowEvenWhileEnabled() {
        assertFalse(ChatListMenuHooks.shouldShowRowGivenEnabled(null, true));
        assertFalse(ChatListMenuHooks.shouldShowRowGivenEnabled("", true));
    }

    @Test
    public void theRealEntryPointFailsOpenWithoutConfiguration() {
        // この単体テストでは LinimalConfig を初期化しないため、runtime の初期値は fail-open です。
        assertFalse(ChatListMenuHooks.shouldShowRow("chat-1"));
    }

    @Test
    public void theRealEntryPointNeverThrowsForAMissingChatId() {
        assertFalse(ChatListMenuHooks.shouldShowRow(null));
        assertFalse(ChatListMenuHooks.shouldShowRow(""));
    }

    @Test
    public void theLabelIsTheResolvedResourceValue() {
        String label = ChatListMenuHooks.menuLabelWith(new ChatListMenuHooks.LabelResolver() {
            @Override
            public String resolve() {
                return "resolved label";
            }
        });

        assertEquals("resolved label", label);
    }

    @Test
    public void theLabelFallsBackWhenTheResolverThrows() {
        String label = ChatListMenuHooks.menuLabelWith(new ChatListMenuHooks.LabelResolver() {
            @Override
            public String resolve() {
                throw new IllegalStateException("simulated resource lookup failure");
            }
        });

        assertEquals(ChatListMenuHooks.DEFAULT_MENU_LABEL, label);
    }

    @Test
    public void theLabelFallsBackForAnUnresolvedResource() {
        String nullLabel = ChatListMenuHooks.menuLabelWith(new ChatListMenuHooks.LabelResolver() {
            @Override
            public String resolve() {
                return null;
            }
        });
        String emptyLabel = ChatListMenuHooks.menuLabelWith(new ChatListMenuHooks.LabelResolver() {
            @Override
            public String resolve() {
                return "";
            }
        });

        assertEquals(ChatListMenuHooks.DEFAULT_MENU_LABEL, nullLabel);
        assertEquals(ChatListMenuHooks.DEFAULT_MENU_LABEL, emptyLabel);
    }

    @Test
    public void theLabelFallsBackWithoutAResolver() {
        assertEquals(ChatListMenuHooks.DEFAULT_MENU_LABEL, ChatListMenuHooks.menuLabelWith(null));
    }

    @Test
    public void theRealLabelIsNeverNullWithoutAnApplicationContext() {
        // initialize() を一度も呼んでいない状態。null を返すと Compose の Text 描画が落ちます。
        String label = ChatListMenuHooks.menuLabel();

        assertNotNull(label);
        assertEquals(ChatListMenuHooks.DEFAULT_MENU_LABEL, label);
    }
}
