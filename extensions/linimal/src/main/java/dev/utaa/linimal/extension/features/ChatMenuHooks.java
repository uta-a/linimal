package dev.utaa.linimal.extension.features;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import dev.utaa.linimal.extension.config.LinimalConfig;

/** チャットの + メニュー項目を、項目種別 enum の構造から判定します。 */
public final class ChatMenuHooks {
    private ChatMenuHooks() {
    }

    /**
     * 対象アプリの難読化済みクラスには静的に依存しません。CALENDAR / GIFT / PAY を全て持つ enum field
     * だけを項目種別として受け入れます。reflection または設定の失敗時は元の predicate を通します。
     */
    public static boolean shouldHide(Object item) {
        try {
            String itemType = findMenuItemType(item);
            if (itemType == null) {
                return false;
            }
            LinimalConfig config = LinimalConfig.get();
            return shouldHideForEnabledStates(
                    itemType,
                    config.isChatCalendarSuppressionEnabled(),
                    config.isChatLineGiftSuppressionEnabled(),
                    config.isChatLinePaySuppressionEnabled());
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean shouldHideForEnabledStates(
            String itemType,
            boolean suppressCalendar,
            boolean suppressGift,
            boolean suppressPay) {
        return (suppressCalendar && "CALENDAR".equals(itemType))
                || (suppressGift && "GIFT".equals(itemType))
                || (suppressPay && "PAY".equals(itemType));
    }

    /**
     * static field は item ごとの種別を表さないため除外します。{@code getDeclaredFields()} の順序は
     * 保証されないので、同じ class 内では候補を全部読み、値が 1 つに定まったときだけ採用します。
     * 定まらない場合は null を返し、元の LINE predicate をそのまま通します。
     */
    static String findMenuItemType(Object item) {
        if (item == null) {
            return null;
        }
        for (Class<?> type = item.getClass(); type != null; type = type.getSuperclass()) {
            Field[] fields;
            try {
                fields = type.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }
            String resolved = null;
            for (Field field : fields) {
                try {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    if (!isChatMenuEnum(field.getType())) {
                        continue;
                    }
                    if (!field.isAccessible()) {
                        field.setAccessible(true);
                    }
                    Object value = field.get(item);
                    if (!(value instanceof Enum<?>)) {
                        continue;
                    }
                    String name = ((Enum<?>) value).name();
                    if (resolved == null) {
                        resolved = name;
                    } else if (!resolved.equals(name)) {
                        return null;
                    }
                } catch (Throwable ignored) {
                    // 次の候補を調べます。reflection の失敗で元の LINE predicate を変えません。
                }
            }
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private static boolean isChatMenuEnum(Class<?> type) {
        if (type == null || !type.isEnum()) {
            return false;
        }
        boolean calendar = false;
        boolean gift = false;
        boolean pay = false;
        Object[] constants = type.getEnumConstants();
        if (constants == null) {
            return false;
        }
        for (Object constant : constants) {
            String name = ((Enum<?>) constant).name();
            calendar |= "CALENDAR".equals(name);
            gift |= "GIFT".equals(name);
            pay |= "PAY".equals(name);
        }
        return calendar && gift && pay;
    }
}
