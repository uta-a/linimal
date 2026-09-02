package dev.utaa.linimal.extension.features.readwithoutreceipt;

import java.util.concurrent.TimeUnit;

import dev.utaa.linimal.extension.config.LinimalConfig;

/**
 * 「既読をつけずに読む」で開いたトークの、outbound 既読送信 RPC の呼び出し口 hook です。
 *
 * <p>単一トークだけを抑制対象として保持します。{@link ReadWithoutReceiptAction} がトークを開く
 * 直前に {@link #markSuppressed(String)} を呼び、以後そのトーク ID を引数に持つ既読送信 RPC の
 * choke point（{@code LegacyTalkServiceClientImpl->j1(I, String, String)V} 相当。Thrift IDL 上の
 * RPC 名は {@code sendChatChecked}）の呼び出しは、本体へ入る前に何もせず return します。</p>
 *
 * <h2>状態の解除について</h2>
 * <p>LINE 自身がトーク終了を検知する {@code Lho1/b;->invoke()} 相当の boundary は、命令列や
 * register の実測情報が無く、安全に fingerprint 化できませんでした（該当箇所は今回 no-op です）。
 * そのため解除は次の 2 つを組み合わせています。</p>
 * <ol>
 *   <li>抑制対象は常に 1 トークだけです。別のトークを「既読をつけずに読む」で開くと、
 *   古い抑制は {@link #markSuppressed(String)} の上書きで自動的に消えます。</li>
 *   <li>{@link #SUPPRESSION_TIMEOUT_MILLIS} を過ぎると、明示的な解除がなくても
 *   {@link #shouldBlockMarkAsRead(String)} は false を返すようになり、状態は永続しません。</li>
 * </ol>
 * <p>この設計では、「抑制対象のトークを開いて即座に閉じ、タイムアウト前に通常の方法で
 * 再度開く」場合に短時間だけ既読が送られない残余リスクがあります。「既読をつけずに読む」は
 * 利用者の明示的な選択であり、影響はタイムアウトで必ず収束するため許容しています。</p>
 */
public final class ReadWithoutReceiptHooks {
    /** LINE 自身のトーク終了検知に便乗できないため、状態を永続させない fallback の上限です。 */
    static final long SUPPRESSION_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(30);

    private static volatile String suppressedChatId;
    private static volatile long suppressedAtMillis;

    private ReadWithoutReceiptHooks() {
    }

    /** {@link ReadWithoutReceiptAction} がトークを開く直前に呼びます。 */
    public static void markSuppressed(String chatId) {
        markSuppressedAt(chatId, System.currentTimeMillis());
    }

    /** {@code atMillis} を注入できるテスト用の実体。 */
    static void markSuppressedAt(String chatId, long atMillis) {
        if (chatId == null) {
            return;
        }
        suppressedChatId = chatId;
        suppressedAtMillis = atMillis;
    }

    /**
     * 注入点。既読送信 RPC choke point（{@code LegacyTalkServiceClientImpl->j1} 相当）の入口で
     * 呼びます。true を返した場合、呼び出し側は本体を実行せず即座に return void します。
     */
    public static boolean shouldBlockMarkAsRead(String chatId) {
        return shouldBlockMarkAsReadAt(chatId, System.currentTimeMillis());
    }

    /** {@code nowMillis} を注入できるテスト用の実体。設定の読み取りは {@link #shouldBlockGivenEnabled} へ分離しています。 */
    static boolean shouldBlockMarkAsReadAt(String chatId, long nowMillis) {
        try {
            boolean enabled = chatId != null && LinimalConfig.get().isReadWithoutReceiptEnabled();
            return shouldBlockGivenEnabled(chatId, nowMillis, enabled);
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
        String current = suppressedChatId;
        if (current == null || !current.equals(chatId)) {
            return false;
        }
        if (nowMillis - suppressedAtMillis > SUPPRESSION_TIMEOUT_MILLIS) {
            // 期限切れの抑制は残さず、以後の呼び出しを毎回タイムアウト計算しません。
            suppressedChatId = null;
            return false;
        }
        return true;
    }

    /** テストでだけ状態を初期化直後へ戻します。 */
    static void resetForTesting() {
        suppressedChatId = null;
        suppressedAtMillis = 0L;
    }
}
