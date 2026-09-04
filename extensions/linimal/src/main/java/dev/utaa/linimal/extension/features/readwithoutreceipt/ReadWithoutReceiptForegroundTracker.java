package dev.utaa.linimal.extension.features.readwithoutreceipt;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * いま前面にある Activity を覚え、それが離れたことを {@link ReadWithoutReceiptHooks} へ伝えます。
 *
 * <p>「既読をつけずに読む」の抑制は、そのトークを開いている間だけ有効にする必要があります。開いて
 * いる間だけを LINE 側の boundary で判定しようとすると難読化されたトーク終了処理を fingerprint 化
 * する必要がありますが、それは安全に特定できませんでした。代わりに Android の
 * {@link Application.ActivityLifecycleCallbacks} だけを使います。LINE の内部構造には一切依存
 * しません。</p>
 *
 * <p>トーク画面がどの Activity かは、クラス名では判定しません。抑制対象のトークの既読処理が実際に
 * 走った時点で前面にある Activity が、そのトークを表示している Activity である、と定義します
 * （{@link ReadWithoutReceiptHooks} 側で捕捉します）。</p>
 *
 * <h2>観測範囲について</h2>
 * <p>{@code registerActivityLifecycleCallbacks} はアプリ全体の画面遷移を観測できる広い hook です。
 * ここで行うのは参照の同一性比較だけで、Activity の中身は読みません。保持は {@link WeakReference}
 * にして Activity をリークさせません。観測した値はプロセスの外へ出しません。</p>
 *
 * <p>Application を取得できない場合は登録せず、抑制は
 * {@link ReadWithoutReceiptHooks#SUPPRESSION_TIMEOUT_MILLIS} だけで解除されます（fail-open）。</p>
 */
final class ReadWithoutReceiptForegroundTracker {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);
    private static final AtomicReference<WeakReference<Object>> CURRENT = new AtomicReference<>();

    private ReadWithoutReceiptForegroundTracker() {
    }

    /**
     * 一度だけ callbacks を登録します。{@code context} が Application を返さない場合や登録に失敗した
     * 場合は何もしません。失敗しても呼び出し側の挙動は変えません。
     */
    static void ensureRegistered(Context context) {
        if (context == null || REGISTERED.get()) {
            return;
        }
        try {
            Context applicationContext = context.getApplicationContext();
            if (!(applicationContext instanceof Application)) {
                return;
            }
            if (!REGISTERED.compareAndSet(false, true)) {
                return;
            }
            ((Application) applicationContext).registerActivityLifecycleCallbacks(new Callbacks());
        } catch (Throwable ignored) {
            // 登録できなくても、抑制はタイムアウトで解除されます。
        }
    }

    /** 前面へ来た Activity を覚えます。Android に依存しない test 用の入口でもあります。 */
    static void noteResumed(Object activity) {
        if (activity == null) {
            return;
        }
        CURRENT.set(new WeakReference<>(activity));
    }

    /**
     * Activity が前面から外れたことを伝えます。抑制対象のトークを表示していた Activity であれば、
     * {@link ReadWithoutReceiptHooks} 側で抑制を解除します。
     */
    static void notePaused(Object activity) {
        if (activity == null) {
            return;
        }
        WeakReference<Object> observed = CURRENT.get();
        if (observed != null && observed.get() == activity) {
            // 覚えている前面 Activity と同じものが離れたので、前面は未確定に戻します。
            CURRENT.compareAndSet(observed, null);
        }
        ReadWithoutReceiptHooks.releaseIfViewing(activity);
    }

    /** いま前面にある Activity。まだ観測していない場合や回収済みの場合は null です。 */
    static Object currentActivity() {
        WeakReference<Object> observed = CURRENT.get();
        return observed == null ? null : observed.get();
    }

    /** テストでだけ状態を初期化直後へ戻します。登録済みフラグは Android 依存のため触りません。 */
    static void resetForTesting() {
        CURRENT.set(null);
    }

    /** Android 依存部分。ここ以外は plain JVM の test から直接呼べるようにしています。 */
    private static final class Callbacks implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityResumed(Activity activity) {
            noteResumed(activity);
        }

        @Override
        public void onActivityPaused(Activity activity) {
            notePaused(activity);
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
            // pause を受け取れなかった場合の保険です。
            notePaused(activity);
        }
    }
}
