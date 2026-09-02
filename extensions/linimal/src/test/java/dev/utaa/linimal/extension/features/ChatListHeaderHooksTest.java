package dev.utaa.linimal.extension.features;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** トーク一覧上部のボタン絞り込みが、`Enum.name()` 判定と fail-open の契約を守ることを検証します。 */
public final class ChatListHeaderHooksTest {
    /**
     * LINE のボタン enum を再現します。難読化されても `name()` が返す文字列は平文で残るため、
     * 判定はこの名前だけで行われます。
     */
    private enum Button {
        AI_FRIEND,
        ALBUM,
        CALENDAR,
        OPEN_CHAT,
        PLUS_MENU,
    }

    /**
     * 名前が抑制対象の prefix になっている別の定数です。前方一致で判定すると巻き添えで消えるため、
     * 完全一致であることをここで固定します。
     */
    private enum SimilarlyNamedButton {
        AI_FRIENDS_RECOMMENDATION,
        CALENDAR_EVENT,
        OPEN_CHAT_SEARCH,
    }

    /** enum ではない要素は判定できないため、必ず残さなければなりません。 */
    private static final class UnknownButton {
        @Override
        public String toString() {
            throw new IllegalStateException("simulated toString failure");
        }
    }

    @Test
    public void buttonsReturnTheSameListWhenEverySuppressionIsOff() {
        List<Button> buttons = Arrays.asList(Button.AI_FRIEND, Button.CALENDAR, Button.OPEN_CHAT, Button.PLUS_MENU);

        assertSame(buttons, ChatListHeaderHooks.filterButtonsForEnabledStates(buttons, false, false, false));
    }

    @Test
    public void nullIsPassedThrough() {
        assertNull(ChatListHeaderHooks.filterButtons(null));
        assertNull(ChatListHeaderHooks.filterButtonsForEnabledStates(null, true, true, true));
    }

    @Test
    public void eachSuppressionOnlyRemovesItsOwnButton() {
        List<Button> buttons = Arrays.asList(Button.AI_FRIEND, Button.CALENDAR, Button.OPEN_CHAT, Button.PLUS_MENU);

        assertTrue(ChatListHeaderHooks.filterButtonsForEnabledStates(buttons, true, false, false)
                .equals(Arrays.asList(Button.CALENDAR, Button.OPEN_CHAT, Button.PLUS_MENU)));
        assertTrue(ChatListHeaderHooks.filterButtonsForEnabledStates(buttons, false, true, false)
                .equals(Arrays.asList(Button.AI_FRIEND, Button.OPEN_CHAT, Button.PLUS_MENU)));
        assertTrue(ChatListHeaderHooks.filterButtonsForEnabledStates(buttons, false, false, true)
                .equals(Arrays.asList(Button.AI_FRIEND, Button.CALENDAR, Button.PLUS_MENU)));
    }

    @Test
    public void twoSuppressionsRemoveExactlyBothButtons() {
        List<Button> buttons = Arrays.asList(Button.AI_FRIEND, Button.CALENDAR, Button.OPEN_CHAT, Button.PLUS_MENU);

        assertTrue(ChatListHeaderHooks.filterButtonsForEnabledStates(buttons, true, true, false)
                .equals(Arrays.asList(Button.OPEN_CHAT, Button.PLUS_MENU)));
        assertTrue(ChatListHeaderHooks.filterButtonsForEnabledStates(buttons, true, false, true)
                .equals(Arrays.asList(Button.CALENDAR, Button.PLUS_MENU)));
        assertTrue(ChatListHeaderHooks.filterButtonsForEnabledStates(buttons, false, true, true)
                .equals(Arrays.asList(Button.AI_FRIEND, Button.PLUS_MENU)));
    }

    /**
     * 追加ボタンとアルバムは設定を持たないため、3 つとも ON でも必ず残さなければなりません。
     * 追加ボタンを消すとトーク作成の導線そのものが失われます。
     */
    @Test
    public void thePlusMenuAndAlbumAreNeverRemoved() {
        List<Button> buttons = Arrays.asList(
                Button.AI_FRIEND, Button.ALBUM, Button.CALENDAR, Button.OPEN_CHAT, Button.PLUS_MENU);

        assertTrue(ChatListHeaderHooks.filterButtonsForEnabledStates(buttons, true, true, true)
                .equals(Arrays.asList(Button.ALBUM, Button.PLUS_MENU)));
    }

    /**
     * 名前の判定は完全一致です。前方一致にすると、抑制対象の名前で始まる別の定数まで消えます。
     */
    @Test
    public void namesAreMatchedExactlyAndNotByPrefix() {
        List<SimilarlyNamedButton> buttons = Arrays.asList(
                SimilarlyNamedButton.AI_FRIENDS_RECOMMENDATION,
                SimilarlyNamedButton.CALENDAR_EVENT,
                SimilarlyNamedButton.OPEN_CHAT_SEARCH);

        assertTrue(ChatListHeaderHooks.filterButtonsForEnabledStates(buttons, true, true, true)
                .equals(buttons));
    }

    @Test
    public void anElementThatIsNotAnEnumIsKept() {
        UnknownButton unknown = new UnknownButton();
        List<Object> buttons = Arrays.asList(Button.AI_FRIEND, unknown, Button.CALENDAR);

        List<?> result = ChatListHeaderHooks.filterButtonsForEnabledStates(buttons, true, true, true);

        assertTrue(result.equals(Arrays.asList(unknown)));
        assertSame(unknown, result.get(0));
    }

    @Test
    public void filteringDoesNotMutateTheCallerList() {
        List<Button> mutable = new ArrayList<>(Arrays.asList(Button.AI_FRIEND, Button.PLUS_MENU));

        ChatListHeaderHooks.filterButtonsForEnabledStates(mutable, true, false, false);

        assertTrue(mutable.equals(Arrays.asList(Button.AI_FRIEND, Button.PLUS_MENU)));
    }

    @Test
    public void unavailableConfigurationPreservesTheOriginalList() {
        // この単体テストでは LinimalConfig を初期化しないため、runtime の初期値は fail-open です。
        List<Button> buttons = Arrays.asList(Button.AI_FRIEND, Button.CALENDAR, Button.OPEN_CHAT);

        assertSame(buttons, ChatListHeaderHooks.filterButtons(buttons));
    }
}
