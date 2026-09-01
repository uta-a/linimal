package dev.utaa.linimal.extension.features;

import java.lang.reflect.Field;

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
            for (Field field : fields) {
                try {
                    Class<?> fieldType = field.getType();
                    if (!isChatMenuEnum(fieldType)) {
                        continue;
                    }
                    if (!field.isAccessible()) {
                        field.setAccessible(true);
                    }
                    Object value = field.get(item);
                    if (value instanceof Enum<?>) {
                        return ((Enum<?>) value).name();
                    }
                } catch (Throwable ignored) {
                    // 次の候補を調べます。reflection の失敗で元の LINE predicate を変えません。
                }
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
