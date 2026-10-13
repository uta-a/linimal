package dev.utaa.linimal.extension.features.readwithoutreceipt;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

/** 前面の推測はせず、確定済みセッションの案内・終了・構成変更だけを観測します。 */
final class ReadWithoutReceiptForegroundTracker {
    private static final Registration REGISTRATION = new Registration();

    private ReadWithoutReceiptForegroundTracker() {
    }

    /** 登録に成功したあとだけ成功を記録します。失敗時は次回の作成で再試行できます。 */
    static boolean ensureRegistered(Context context) {
        try {
            if (REGISTRATION.isRegistered()) {
                return true;
            }
            if (context == null) {
                return false;
            }
            Context applicationContext = context.getApplicationContext();
            if (!(applicationContext instanceof Application)) {
                return false;
            }
            Application application = (Application) applicationContext;
            return REGISTRATION.ensure(() -> application.registerActivityLifecycleCallbacks(new Callbacks()));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 登録失敗と同時呼び出しを plain JVM で検証する境界です。 */
    static final class Registration {
        private boolean registered;

        synchronized boolean isRegistered() {
            return registered;
        }

        synchronized boolean ensure(Runnable register) {
            if (registered) {
                return true;
            }
            try {
                register.run();
                registered = true;
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    static void noteStopped(Object activity, boolean finishing, boolean changingConfigurations) {
        // Android 9 以降では save が stop 後にも来るため、構成変更時の detach は destroy まで待ちます。
        if (!changingConfigurations) {
            ReadWithoutReceiptHooks.endActivity(activity, false, finishing);
        }
    }

    static void noteDestroyed(Object activity, boolean changingConfigurations) {
        ReadWithoutReceiptHooks.endActivity(activity, changingConfigurations, true);
    }

    static final class Callbacks implements Application.ActivityLifecycleCallbacks {
        private final ReadWithoutReceiptNotice notice = new ReadWithoutReceiptNotice();

        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            // onCreate の注入入口でのみ request と saved token を検証します。
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityResumed(Activity activity) {
            // pause/resume は閲覧セッションを終了しません。
            notice.onResumed(activity);
        }

        @Override
        public void onActivityPaused(Activity activity) {
            notice.onHidden(activity);
        }

        @Override
        public void onActivityStopped(Activity activity) {
            try {
                noteStopped(activity, activity.isFinishing(), activity.isChangingConfigurations());
            } catch (Throwable ignored) {
                ReadWithoutReceiptHooks.endActivity(activity, false, true);
            }
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
            try {
                long token = ReadWithoutReceiptHooks.sessionTokenFor(activity);
                if (token != 0L) {
                    outState.putLong(ReadWithoutReceiptHooks.SESSION_TOKEN_KEY, token);
                }
            } catch (Throwable ignored) {
                // 復元印を保存できなければ、そのセッションだけを公式挙動へ戻します。
                ReadWithoutReceiptHooks.endActivity(activity, false, true);
            }
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
            notice.onHidden(activity);
            try {
                noteDestroyed(activity, activity.isChangingConfigurations());
            } catch (Throwable ignored) {
                ReadWithoutReceiptHooks.endActivity(activity, false, true);
            }
        }
    }
}
