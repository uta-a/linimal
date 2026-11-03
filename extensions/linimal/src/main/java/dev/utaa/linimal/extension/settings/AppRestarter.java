package dev.utaa.linimal.extension.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/** 表示系の設定を変えたあと、反映に必要な LINE の再起動を確認して行います。 */
final class AppRestarter {
    static final String TITLE = "再起動が必要です";
    static final String MESSAGE = "この設定の変更にはアプリの再起動が必要です。今すぐ再起動しますか？\n"
            + "送信中のメッセージなど、実行中の処理は中断されます。";
    static final String MANUAL_RESTART = "再起動できませんでした。LINE を閉じて開き直してください。";
    /** 起動要求が system に届いてから終了するまでの待ち時間。 */
    static final long EXIT_DELAY_MILLIS = 500L;

    private AppRestarter() {
    }

    static void confirm(Activity activity) {
        try {
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            new AlertDialog.Builder(activity)
                    .setTitle(TITLE)
                    .setMessage(MESSAGE)
                    .setPositiveButton("再起動", (dialog, which) -> restart(activity))
                    .setNegativeButton("あとで", null)
                    .show();
        } catch (Throwable ignored) {
            // 確認を出せなくても、保存した設定は次回の起動で反映されます。
        }
    }

    /**
     * LINE の起動画面を新しい task で開き直してから、このプロセスを終了します。起動要求は system に記録されるため、
     * プロセスの終了後に Android が新しいプロセスで起動画面を開きます。
     */
    static void restart(Activity activity) {
        try {
            Intent launch = activity.getPackageManager().getLaunchIntentForPackage(activity.getPackageName());
            ComponentName component = launch == null ? null : launch.getComponent();
            if (component == null) {
                showManualRestart(activity);
                return;
            }
            activity.startActivity(Intent.makeRestartActivityTask(component));
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> Runtime.getRuntime().exit(0), EXIT_DELAY_MILLIS);
        } catch (Throwable ignored) {
            // 再起動できなければ、利用者が手動で再起動するまで今の値を使い続けます。
            showManualRestart(activity);
        }
    }

    private static void showManualRestart(Activity activity) {
        try {
            Toast.makeText(activity, MANUAL_RESTART, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
            // 案内を出せなくても、保存した設定は次回の起動で反映されます。
        }
    }
}
