package dev.utaa.linimal.extension.features;

import java.util.ArrayList;
import java.util.List;

import dev.utaa.linimal.extension.config.LinimalConfig;

/** トーク一覧の上部に並べるボタン列を、表示設定に応じて安全に絞り込みます。 */
public final class ChatListHeaderHooks {
    /**
     * ボタンは Kotlin の data class なので、難読化されても `toString()` に class 名が残ります。
     * LINE の sealed class 型を extension 側へリンクせず、この marker だけで対象を判定します。
     */
    private static final String AI_FRIENDS_MARKER = "AiFriendsButtonStatus(";
    private static final String CALENDAR_MARKER = "CalendarButtonStatus(";
    private static final String OPEN_CHAT_MARKER = "OpenChatButtonStatus(";

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
            String marker = markerOf(button);
            if ((suppressAiFriends && startsWith(marker, AI_FRIENDS_MARKER))
                    || (suppressCalendar && startsWith(marker, CALENDAR_MARKER))
                    || (suppressOpenChat && startsWith(marker, OPEN_CHAT_MARKER))) {
                continue;
            }
            filtered.add(button);
        }
        return filtered;
    }

    /** `toString()` が例外を投げる要素は判定できないため、marker なしとして必ず残します。 */
    private static String markerOf(Object button) {
        try {
            return String.valueOf(button);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean startsWith(String marker, String expected) {
        return marker != null && marker.startsWith(expected);
    }
}
