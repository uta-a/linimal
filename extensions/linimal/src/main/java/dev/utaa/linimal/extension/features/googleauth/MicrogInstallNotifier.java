package dev.utaa.linimal.extension.features.googleauth;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

/**
 * 「MicroG-RE でトークをバックアップする」が ON なのに MicroG-RE がないとき、導入を案内する通知を出します。
 *
 * <p>1 回のバックアップ・復元で token の要求は何度か走るため、通知は {@link #MIN_INTERVAL_MILLIS} に
 * 1 回までにします。通知をタップすると MicroG-RE のリリースページをブラウザで開きます。
 * 通知が許可されていない端末では何も表示されません。</p>
 */
final class MicrogInstallNotifier {
    static final long MIN_INTERVAL_MILLIS = 10 * 60 * 1000L;
    static final String RELEASES_URL = "https://github.com/MorpheApp/MicroG-RE/releases/latest";
    private static final String CHANNEL_ID = "linimal_microg_install";
    private static final int NOTIFICATION_ID = 0x4c4d4731;

    private static final Object LOCK = new Object();
    private static long lastNotifiedAt = Long.MIN_VALUE;

    private MicrogInstallNotifier() {
    }

    static void notifyMissing(Context context) {
        if (context == null) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            if (!shouldNotify(lastNotifiedAt, now)) {
                return;
            }
            lastNotifiedAt = now;
        }
        post(context.getApplicationContext() != null ? context.getApplicationContext() : context);
    }

    /** 前回から {@link #MIN_INTERVAL_MILLIS} 以上経っていれば通知します。時計が戻った場合も通知します。 */
    static boolean shouldNotify(long lastNotifiedAt, long now) {
        if (lastNotifiedAt == Long.MIN_VALUE || now < lastNotifiedAt) {
            return true;
        }
        return now - lastNotifiedAt >= MIN_INTERVAL_MILLIS;
    }

    private static void post(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Linimal", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Linimal の機能に必要なアプリの案内");
            manager.createNotificationChannel(channel);
            builder = new Notification.Builder(context, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(context);
        }

        Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pending = PendingIntent.getActivity(
                context, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String text = "トークのバックアップと復元には MicroG-RE が必要です。"
                + "タップして MicroG-RE を入手し、LINE と同じ Google アカウントを追加してください。";
        builder.setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("MicroG-RE が見つかりません")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pending)
                .setAutoCancel(true);
        manager.notify(NOTIFICATION_ID, builder.build());
    }
}
