package dev.utaa.linimal.extension.features.readwithoutreceipt;

import android.app.Activity;
import android.os.AsyncTask;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

import dev.utaa.linimal.extension.features.readreceipts.ReadReceiptHooks;

/** 明示した既読操作だけを worker に許可し、後続メッセージの自動既読は抑制し続けます。 */
public final class ReadWithoutReceiptManualHooks {
    private static final String BRIDGE = "linimalMarkReadManually";
    private static final ThreadLocal<ReadWithoutReceiptHooks.Suppression> INVOCATION = new ThreadLocal<>();

    /** 構成変更では引き継ぎ、再入室では破棄する操作状態です。 */
    static final class State {
        private long completedPoint;
        private long baseline = Long.MIN_VALUE;
        private boolean pending;

        synchronized void observeBaseline(String value) {
            if (baseline != Long.MIN_VALUE) {
                return;
            }
            try {
                long parsed = value == null || value.isEmpty() ? 0L : Long.parseLong(value);
                if (parsed >= 0L) {
                    baseline = parsed;
                }
            } catch (NumberFormatException ignored) {
                // 壊れた read_up を「全履歴が未読」と解釈しません。
            }
        }

        synchronized int presentation(long point, boolean firstUnread) {
            if (baseline == Long.MIN_VALUE || point <= 0L) {
                return -1;
            }
            if (completedPoint > baseline && point == completedPoint) {
                return 4;
            }
            if (point <= Math.max(baseline, completedPoint)) {
                return 0;
            }
            return 1 | (completedPoint <= baseline && firstUnread ? 2 : 0);
        }

        synchronized boolean begin(long point, boolean all) {
            if (pending || (!all && (baseline == Long.MIN_VALUE || point <= 0L
                    || point <= Math.max(baseline, completedPoint)))) {
                return false;
            }
            pending = true;
            return true;
        }

        synchronized void finish(long point) {
            completedPoint = Math.max(completedPoint, point);
            pending = false;
        }

        synchronized boolean isPending() {
            return pending;
        }

        synchronized boolean isRead(long point) {
            return baseline != Long.MIN_VALUE && point > 0L && point <= Math.max(baseline, completedPoint);
        }

        synchronized long atLeastCompleted(long point) {
            return Math.max(Math.max(0L, baseline), Math.max(completedPoint, point));
        }
    }

    private ReadWithoutReceiptManualHooks() {
    }

    static boolean isReady(Activity activity) {
        ReadWithoutReceiptHooks.Suppression session = sessionFor(activity);
        return session != null && session.sendState.hasReadAttempt() && !session.manualState.isPending();
    }

    static void markAll(Activity activity) {
        submit(activity, 0L, true);
    }

    static void markMessage(Activity activity, long point) {
        submit(activity, point, false);
    }

    static boolean isRead(Activity activity, long point) {
        ReadWithoutReceiptHooks.Suppression session = sessionFor(activity);
        return session != null && session.manualState.isRead(point);
    }

    /** adapter が入室時に保持した ChatData.read_up だけを初期位置として採用します。 */
    public static void observeReadPosition(Activity activity, String chatId, String readUp) {
        try {
            ReadWithoutReceiptHooks.Suppression session = sessionFor(activity);
            if (session != null && session.chatId.equals(chatId)) {
                session.manualState.observeBaseline(readUp);
            }
        } catch (Throwable ignored) {
            // 不明な初期位置は維持し、公式表示へ例外を伝播させません。
        }
    }

    /** -1 は不明、1 は未読操作、2 は境界前、4 は手動既読位置の境界後です。 */
    static int presentation(Activity activity, long point, boolean firstUnread) {
        ReadWithoutReceiptHooks.Suppression session = sessionFor(activity);
        return session == null ? -1 : session.manualState.presentation(point, firstUnread);
    }

    private static ReadWithoutReceiptHooks.Suppression sessionFor(Activity activity) {
        try {
            ReadWithoutReceiptHooks.Suppression session = ReadWithoutReceiptHooks.currentSuppression();
            return session != null && session.confirmed && session.owns(activity)
                    && ReadWithoutReceiptHooks.shouldBlockMarkAsRead(session.chatId) ? session : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean hasManualInvocation() {
        return INVOCATION.get() != null;
    }

    /** local gate と RPC gate の両方で使う、同一 worker・同一 token 限定の許可です。 */
    static boolean isManualRead(String chatId) {
        ReadWithoutReceiptHooks.Suppression allowed = INVOCATION.get();
        ReadWithoutReceiptHooks.Suppression current = ReadWithoutReceiptHooks.currentSuppression();
        return allowed != null && current != null && current.confirmed
                && current.token == allowed.token && current.chatId.equals(chatId);
    }

    private static void submit(Activity activity, long point, boolean all) {
        ReadWithoutReceiptHooks.Suppression session = sessionFor(activity);
        if (session == null) {
            return;
        }
        ReadWithoutReceiptSendHooks.ReadAttempt attempt = session.sendState.snapshot();
        if (attempt == null || !session.manualState.begin(point, all)) {
            return;
        }
        long requestedPoint = all ? session.manualState.atLeastCompleted(attempt.readPoint) : point;
        WeakReference<Activity> owner = new WeakReference<>(activity);
        try {
            AsyncTask.SERIAL_EXECUTOR.execute(() -> execute(owner, session, attempt.executor, requestedPoint, all));
        } catch (Throwable ignored) {
            session.manualState.finish(0L);
            showResult(session.token, false);
        }
    }

    private static void execute(WeakReference<Activity> owner, ReadWithoutReceiptHooks.Suppression session,
            Object executor, long point, boolean all) {
        long completed = 0L;
        try {
            Activity activity = owner.get();
            ReadWithoutReceiptHooks.Suppression current = sessionFor(activity);
            if (current == null || current.token != session.token) {
                return;
            }
            completed = invokeRead(session, executor, point, all);
        } catch (Throwable ignored) {
            // 元の RPC 再送経路は変更せず、bridge の例外を UI へ伝播させません。
        } finally {
            session.manualState.finish(completed);
            showResult(session.token, completed > 0L);
        }
    }

    /** Android に依存しない worker 境界。失敗・入室変更時にも thread 上の許可を残しません。 */
    static long invokeRead(ReadWithoutReceiptHooks.Suppression session, Object executor,
            long point, boolean all) throws ReflectiveOperationException {
        ReadWithoutReceiptHooks.Suppression current = ReadWithoutReceiptHooks.currentSuppression();
        if (current == null || !current.confirmed || current.token != session.token) {
            return 0L;
        }
        Method bridge = executor.getClass().getMethod(BRIDGE, long.class, String.class, boolean.class);
        if (bridge.getReturnType() != long.class) {
            return 0L;
        }
        try {
            INVOCATION.set(session);
            ReadReceiptHooks.prepareSentMessageRead(session.chatId);
            return (Long) bridge.invoke(executor, point, session.chatId, all);
        } finally {
            INVOCATION.remove();
            ReadReceiptHooks.clearPreparedSupplier();
        }
    }

    private static void showResult(long token, boolean success) {
        ReadWithoutReceiptHooks.Suppression current = ReadWithoutReceiptHooks.currentSuppression();
        Object candidate = current != null && current.token == token && current.owner != null
                ? current.owner.get() : null;
        if (!(candidate instanceof Activity)) {
            return;
        }
        Activity activity = (Activity) candidate;
        activity.runOnUiThread(() -> {
            try {
                if (!activity.isFinishing() && !activity.isDestroyed()
                        && ReadWithoutReceiptHooks.sessionTokenFor(activity) == token) {
                    // worker の状態変更だけでは次の描画が起きないため、完了した行の操作を更新します。
                    activity.getWindow().getDecorView().invalidate();
                    Toast.makeText(activity, success ? "既読処理を実行しました" : "既読を反映できませんでした",
                            Toast.LENGTH_SHORT).show();
                }
            } catch (Throwable ignored) {
                // 案内を表示できなくても、公式の画面操作は続けます。
            }
        });
    }
}
