package dev.utaa.linimal.extension.features.readwithoutreceipt;

import java.lang.ref.WeakReference;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import dev.utaa.linimal.extension.config.LinimalConfig;

/**
 * 「既読をつけずに読む」で開いたトークの、既読処理の呼び出し口 hook です。
 *
 * <p>単一トークだけを抑制対象として保持します。{@link ReadWithoutReceiptAction} がトークを開く
 * 直前に {@link #markSuppressed(String)} を呼び、以後そのトーク ID に対する既読処理は本体へ入る
 * 前に何もせず return します。注入先は 2 箇所です。</p>
 * <ol>
 *   <li>「既読にする」処理そのもの（{@code q33.e.d(J, String, Z)V} 相当）の入口。ここを止めると
 *   ローカルの未読クリアと既読位置の前進も走らないため、トーク一覧の未読バッジと下部タブの
 *   バッジが残ります。</li>
 *   <li>既読送信 RPC の choke point（{@code LegacyTalkServiceClientImpl->j1(I, String, String)V}
 *   相当。Thrift IDL 上の RPC 名は {@code sendChatChecked}）。1 を通らない別経路から送信された
 *   場合の押さえです。</li>
 * </ol>
 *
 * <h2>状態の解除について</h2>
 * <p>抑制は「そのトークを開いている間」だけ有効にします。トークを閉じたあとに通常の操作で開き直せ
 * ば、LINE 本来どおり既読になりバッジも消えます。解除は次の 3 つを組み合わせています。</p>
 * <ol>
 *   <li>抑制対象のトークの既読処理が最初に止められた時点で前面にある Activity を、そのトークを
 *   表示している Activity として記録します（{@link ReadWithoutReceiptForegroundTracker}）。その
 *   Activity が前面から外れたら抑制を解除します。LINE の難読化クラスには依存しません。</li>
 *   <li>抑制対象は常に 1 トークだけです。別のトークを「既読をつけずに読む」で開くと、
 *   古い抑制は {@link #markSuppressed(String)} の上書きで自動的に消えます。</li>
 *   <li>{@link #SUPPRESSION_TIMEOUT_MILLIS} を過ぎると、明示的な解除がなくても
 *   {@link #shouldBlockMarkAsRead(String)} は false を返すようになり、状態は永続しません。
 *   Application を取得できず Activity を観測できない端末では、これが唯一の解除になります。</li>
 * </ol>
 */
public final class ReadWithoutReceiptHooks {
    /** Activity の観測に失敗した場合でも状態を永続させない fallback の上限です。 */
    static final long SUPPRESSION_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(30);

    /**
     * 抑制対象のトークと、その登録時刻、表示中の Activity を 1 つにまとめた不変値です。
     *
     * <p>この hook は既読処理の worker thread と UI 操作の両方から呼ばれます。トーク ID と
     * 時刻を別々の field に持つと「新しいトーク ID + 古い時刻」という中間状態が観測でき、
     * 登録直後の抑制が期限切れと誤判定されます。まとめて 1 instance に閉じ込め、
     * {@link #SUPPRESSION} への 1 回の書き込みで差し替えることでその中間状態を無くします。</p>
     */
    static final class Suppression {
        final String chatId;
        final long atMillis;
        /** そのトークを表示している Activity。まだ観測していなければ null です。 */
        private final WeakReference<Object> viewingActivity;

        Suppression(String chatId, long atMillis) {
            this(chatId, atMillis, null);
        }

        private Suppression(String chatId, long atMillis, WeakReference<Object> viewingActivity) {
            this.chatId = chatId;
            this.atMillis = atMillis;
            this.viewingActivity = viewingActivity;
        }

        /** 表示中の Activity を記録した新しい instance を返します。元の instance は変更しません。 */
        Suppression withViewingActivity(Object activity) {
            return new Suppression(chatId, atMillis, new WeakReference<>(activity));
        }

        /** 記録済みの Activity。未記録または回収済みなら null です。 */
        Object viewingActivity() {
            return viewingActivity == null ? null : viewingActivity.get();
        }

        boolean hasViewingActivity() {
            return viewingActivity != null;
        }
    }

    private static final AtomicReference<Suppression> SUPPRESSION = new AtomicReference<>();

    private ReadWithoutReceiptHooks() {
    }

    /** {@link ReadWithoutReceiptAction} がトークを開く直前に呼びます。 */
    public static void markSuppressed(String chatId) {
        // トークが前面へ来る前に観測を始めます。登録できなくても抑制自体は成立します。
        try {
            ReadWithoutReceiptForegroundTracker.ensureRegistered(ChatListMenuHooks.applicationContext());
        } catch (Throwable ignored) {
            // 観測できない場合はタイムアウトだけが解除条件になります。
        }
        markSuppressedAt(chatId, System.currentTimeMillis());
    }

    /** {@code atMillis} を注入できるテスト用の実体。 */
    static void markSuppressedAt(String chatId, long atMillis) {
        if (chatId == null) {
            return;
        }
        // トーク ID と時刻は必ず同時に切り替えます。
        SUPPRESSION.set(new Suppression(chatId, atMillis));
    }

    /**
     * 注入点。「既読にする」処理の入口と既読送信 RPC の入口で呼びます。true を返した場合、
     * 呼び出し側は本体を実行せず即座に return void します。
     */
    public static boolean shouldBlockMarkAsRead(String chatId) {
        return shouldBlockMarkAsReadAt(chatId, System.currentTimeMillis());
    }

    /** {@code nowMillis} を注入できるテスト用の実体。設定の読み取りは {@link #shouldBlockGivenEnabled} へ分離しています。 */
    static boolean shouldBlockMarkAsReadAt(String chatId, long nowMillis) {
        try {
            boolean enabled = chatId != null && LinimalConfig.get().isReadWithoutReceiptEnabled();
            boolean blocked = shouldBlockGivenEnabled(chatId, nowMillis, enabled);
            if (blocked) {
                captureViewingActivity();
            }
            return blocked;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 一致判定とタイムアウトの核心部分。{@code enabled} は呼び出し側（実体では config の値）が渡し、
     * ここでは LinimalConfig を読みません。local JVM test から設定値を直接制御できるようにする境界です。
     */
    static boolean shouldBlockGivenEnabled(String chatId, long nowMillis, boolean enabled) {
        if (!enabled || chatId == null) {
            return false;
        }
        return shouldBlockGivenObserved(chatId, nowMillis, currentSuppression());
    }

    /**
     * 一致判定とタイムアウトを、観測した {@code observed} 1 instance に対してだけ行います。
     * 判定に使うトーク ID と時刻が必ず同じ instance 由来になる境界で、
     * local JVM test から「観測後に別の抑制が登録された」状況を直接組み立てられるようにしています。
     */
    static boolean shouldBlockGivenObserved(String chatId, long nowMillis, Suppression observed) {
        if (observed == null || !observed.chatId.equals(chatId)) {
            return false;
        }
        if (nowMillis - observed.atMillis > SUPPRESSION_TIMEOUT_MILLIS) {
            // 期限切れの抑制は残さず、以後の呼び出しを毎回タイムアウト計算しません。
            // 消すのは自分が観測した instance だけで、その後に登録された抑制は消しません。
            SUPPRESSION.compareAndSet(observed, null);
            return false;
        }
        return true;
    }

    /**
     * 抑制対象のトークを表示している Activity を、最初に既読処理を止めた時点で記録します。
     * その既読処理が走っている以上、このとき前面にある Activity がそのトークの画面です。
     * すでに記録済みの場合と前面 Activity を観測できていない場合は何もしません。
     */
    static void captureViewingActivity() {
        Suppression observed = SUPPRESSION.get();
        if (observed == null || observed.hasViewingActivity()) {
            return;
        }
        Object activity = ReadWithoutReceiptForegroundTracker.currentActivity();
        if (activity == null) {
            return;
        }
        // 記録できるのは自分が観測した instance に対してだけです。その後に別のトークが
        // 登録されていれば CAS は失敗し、新しい抑制はそのまま残ります。
        SUPPRESSION.compareAndSet(observed, observed.withViewingActivity(activity));
    }

    /**
     * {@link ReadWithoutReceiptForegroundTracker} から呼ばれます。抑制対象のトークを表示していた
     * Activity が前面から外れたときだけ抑制を解除します。以後そのトークを通常の操作で開けば、
     * LINE 本来どおり既読になりバッジも消えます。
     */
    static void releaseIfViewing(Object activity) {
        if (activity == null) {
            return;
        }
        Suppression observed = SUPPRESSION.get();
        if (observed == null || observed.viewingActivity() != activity) {
            return;
        }
        SUPPRESSION.compareAndSet(observed, null);
    }

    /** 現在の抑制の観測点です。以後の判定はこの戻り値だけを見ます。 */
    static Suppression currentSuppression() {
        return SUPPRESSION.get();
    }

    /** テストでだけ状態を初期化直後へ戻します。 */
    static void resetForTesting() {
        SUPPRESSION.set(null);
        ReadWithoutReceiptForegroundTracker.resetForTesting();
    }
}
