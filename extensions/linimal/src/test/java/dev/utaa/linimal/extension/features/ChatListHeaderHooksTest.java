package dev.utaa.linimal.extension.features;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** トーク一覧上部のボタン絞り込みが、marker 判定と fail-open の契約を守ることを検証します。 */
public final class ChatListHeaderHooksTest {
    /** LINE の sealed class を再現します。data class の `toString()` marker だけで判定されます。 */
    private static final class Button {
        private final String marker;

        Button(String marker) {
            this.marker = marker;
        }

        @Override
        public String toString() {
            return marker;
        }
    }

    /** `toString()` が失敗する要素は判定できないため、必ず残さなければなりません。 */
    private static final class BrokenButton {
        @Override
        public String toString() {
            throw new IllegalStateException("simulated toString failure");
        }
    }

    private static final Button AI_FRIENDS = new Button("AiFriendsButtonStatus(isVisible=true)");
    private static final Button CALENDAR = new Button("CalendarButtonStatus(isVisible=true)");
    private static final Button OPEN_CHAT = new Button("OpenChatButtonStatus(isVisible=true)");
    private static final Button ALL_ALBUMS = new Button("AllAlbumsButtonStatus(isVisible=true)");
    private static final Button CREATE_CHAT = new Button("CreateChatButtonStatus(isVisible=true)");
    private static final Button MORE = new Button("MoreButtonStatus(isVisible=true)");

    @Test
    public void buttonsReturnTheSameListWhenEverySuppressionIsOff() {
        List<Button> buttons = Arrays.asList(AI_FRIENDS, CALENDAR, OPEN_CHAT, CREATE_CHAT);

        assertSame(buttons, ChatListHeaderHooks.filterButtonsForEnabledStates(buttons, false, false, false));
    }

    @Test
    public void eachSuppressionOnlyRemovesItsOwnButton() {
        List<Button> buttons = Arrays.asList(AI_FRIENDS, CALENDAR, OPEN_CHAT, CREATE_CHAT);

        assertTrue(ChatListHeaderHooks.filterButtonsForEnabledStates(buttons, true, false, false)
                .equals(Arrays.asList(CALENDAR, OPEN_CHAT, CREATE_CHAT)));
        assertTrue(ChatListHeaderHooks.filterButtonsForEnabledStates(buttons, false, true, false)
                .equals(Arrays.asList(AI_FRIENDS, OPEN_CHAT, CREATE_CHAT)));
        assertTrue(ChatListHeaderHooks.filterButtonsForEnabledStates(buttons, false, false, true)
                .equals(Arrays.asList(AI_FRIENDS, CALENDAR, CREATE_CHAT)));
    }

    @Test
    public void everyOtherButtonRemainsEvenWhenAllSuppressionsAreOn() {
        List<Button> buttons = Arrays.asList(
                ALL_ALBUMS, AI_FRIENDS, CALENDAR, OPEN_CHAT, CREATE_CHAT, MORE);

        assertTrue(ChatListHeaderHooks.filterButtonsForEnabledStates(buttons, true, true, true)
                .equals(Arrays.asList(ALL_ALBUMS, CREATE_CHAT, MORE)));
    }

    @Test
    public void aButtonWhoseToStringFailsIsKept() {
        BrokenButton broken = new BrokenButton();
        List<Object> buttons = Arrays.asList(AI_FRIENDS, broken, CALENDAR);

        List<?> result = ChatListHeaderHooks.filterButtonsForEnabledStates(buttons, true, true, true);

        assertTrue(result.equals(Arrays.asList(broken)));
        assertSame(broken, result.get(0));
    }

    @Test
    public void filteringDoesNotMutateTheCallerList() {
        List<Button> mutable = new ArrayList<>(Arrays.asList(AI_FRIENDS, CREATE_CHAT));

        ChatListHeaderHooks.filterButtonsForEnabledStates(mutable, true, false, false);

        assertTrue(mutable.equals(Arrays.asList(AI_FRIENDS, CREATE_CHAT)));
    }

    @Test
    public void unavailableConfigurationPreservesTheOriginalList() {
        // この単体テストでは LinimalConfig を初期化しないため、runtime の初期値は fail-open です。
        List<Button> buttons = Arrays.asList(AI_FRIENDS, CALENDAR, OPEN_CHAT);

        assertSame(buttons, ChatListHeaderHooks.filterButtons(buttons));
    }
}
