package dev.utaa.linimal.extension.features;

import java.util.ArrayList;
import java.util.List;

import dev.utaa.linimal.extension.config.LinimalConfig;

/** トーク一覧の上部に並べるボタン列を、表示設定に応じて安全に絞り込みます。 */
public final class ChatListHeaderHooks {
    /**
     * ボタンは enum で表され、型と field の名前は難読化されますが、`Enum.name()` が返す文字列は
     * 平文のまま残ります。LINE の enum 型を extension 側へリンクせず、この名前だけで対象を判定します。
     *
     * <p>判定は必ず完全一致で行います。前方一致にすると、たとえば `AI_FRIEND` の判定が
     * `AI_FRIEND_SOMETHING` のような別の定数まで巻き込みます。</p>
     */
    private static final String AI_FRIENDS_NAME = "AI_FRIEND";
    private static final String CALENDAR_NAME = "CALENDAR";
    private static final String OPEN_CHAT_NAME = "OPEN_CHAT";

    private ChatListHeaderHooks() {
    }

    /**
     * すべて OFF の場合は必ず元の List instance を返します。
     * 設定を読めない場合も元の一覧をそのまま返します。
     */
    public static List<?> filterButtons(List<?> original) {
        if (original == null) {
            return null;
        }
        try {
            LinimalConfig config = LinimalConfig.get();
            return filterButtonsForEnabledStates(
                    original,
                    config.isChatListHeaderAiFriendsSuppressionEnabled(),
                    config.isChatListHeaderCalendarSuppressionEnabled(),
                    config.isChatListHeaderOpenChatSuppressionEnabled());
        } catch (Throwable ignored) {
            return original;
        }
    }

    static List<?> filterButtonsForEnabledStates(
            List<?> original,
            boolean suppressAiFriends,
            boolean suppressCalendar,
            boolean suppressOpenChat) {
        if (original == null || (!suppressAiFriends && !suppressCalendar && !suppressOpenChat)) {
            return original;
        }

        ArrayList<Object> filtered = new ArrayList<>(original.size());
        for (Object button : original) {
            String name = nameOf(button);
            if ((suppressAiFriends && AI_FRIENDS_NAME.equals(name))
                    || (suppressCalendar && CALENDAR_NAME.equals(name))
                    || (suppressOpenChat && OPEN_CHAT_NAME.equals(name))) {
                continue;
            }
            filtered.add(button);
        }
        return filtered;
    }

    /**
     * enum でない要素や `name()` を読めない要素は判定できないため、名前なしとして必ず残します。
     * 追加ボタン（`PLUS_MENU`）とアルバム（`ALBUM`）はここで名前が一致しないため常に残ります。
     */
    private static String nameOf(Object button) {
        try {
            if (button instanceof Enum) {
                return ((Enum<?>) button).name();
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
