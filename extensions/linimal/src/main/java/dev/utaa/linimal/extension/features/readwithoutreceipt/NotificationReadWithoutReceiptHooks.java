package dev.utaa.linimal.extension.features.readwithoutreceipt;

import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import dev.utaa.linimal.extension.config.LinimalConfig;

/** 通知の PendingIntent 生成ではなく、実際のトーク起動 dispatch で予約します。 */
public final class NotificationReadWithoutReceiptHooks {
    private static volatile Field notificationField;

    private NotificationReadWithoutReceiptHooks() {
    }

    public static void onChatLaunch(Intent intent) {
        ReadWithoutReceiptHooks.Suppression observed = ReadWithoutReceiptHooks.currentSuppression();
        try {
            if (!LinimalConfig.get().isNotificationReadWithoutReceiptEnabled()) {
                return;
            }
            Bundle extras = intent.getExtras();
            Object request = extras == null ? null : extras.getParcelable("chatHistoryRequest");
            onChatLaunchRequest(request, SystemClock.elapsedRealtime(), true);
        } catch (Throwable ignored) {
            // 無関係な手動予約や、並行して開始したセッションは消しません。
            ReadWithoutReceiptHooks.clearNotificationObserved(observed);
        }
    }

    static void onChatLaunchRequest(Object request, long nowMillis, boolean enabled)
            throws ReflectiveOperationException {
        if (enabled && isFromNotification(request)) {
            ReadWithoutReceiptHooks.markNotificationSuppressedAt(
                    ReadWithoutReceiptHooks.chatIdFromRequest(request), nowMillis);
        }
    }

    /** getter と、serializer が isFromNotification として保存する唯一の公開 boolean を検証済みです。 */
    static boolean isFromNotification(Object request) throws ReflectiveOperationException {
        if (request == null) {
            return false;
        }
        Object navigation = request.getClass().getMethod("getBackNavigation").invoke(request);
        if (navigation == null) {
            return false;
        }
        Field field = notificationField;
        if (field == null || field.getDeclaringClass() != navigation.getClass()) {
            field = null;
            for (Field candidate : navigation.getClass().getFields()) {
                if (candidate.getType() == boolean.class && !Modifier.isStatic(candidate.getModifiers())) {
                    if (field != null) {
                        throw new NoSuchFieldException("Notification marker is not unique");
                    }
                    field = candidate;
                }
            }
            if (field == null) {
                throw new NoSuchFieldException("Notification marker not found");
            }
            notificationField = field;
        }
        return field.getBoolean(navigation);
    }
}
