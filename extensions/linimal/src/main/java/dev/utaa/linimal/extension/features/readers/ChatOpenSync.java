package dev.utaa.linimal.extension.features.readers;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.widget.TextView;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * トークを開いたときに、そのトークの既読範囲を 1 回だけサーバーと同期します。
 *
 * <p>端末内の {@code read_message_range} には、他人のメッセージを誰が読んだかが同期まで入らないことがあり、
 * 一覧を開いて同期するまでアイコンが出ませんでした。「開くたびにサーバーと同期」が ON のときは、
 * 他人のメッセージの行が最初に bind された時点で同期し、表示中の行を読み直します。</p>
 *
 * <p>「開いた」は、画面（Activity）とトークの組で初めて行が bind されたことで判定します。同じ画面の中での
 * スクロールや新着の bind では同期しません。画面は弱参照で持つため、閉じた画面の記録は一緒に捨てられます。
 * main thread からだけ呼びます。</p>
 */
final class ChatOpenSync {
    private static final Map<Object, Set<String>> OPENED = new WeakHashMap<>();

    private ChatOpenSync() {
    }

    /** 他人のメッセージの行を表示する直前に呼びます。同期しない場合は何もしません。 */
    static void onReceivedRowBound(TextView timestamp, String chatId, boolean syncEnabled) {
        // 設定 OFF なら reflection の解決も行いません。
        if (!syncEnabled || !shouldSync(true, ReadReceiptReadersBridge.isSyncAvailable(), chatId)) {
            return;
        }
        Context context = timestamp.getContext();
        if (!markFirstOpen(screenOf(context), chatId)) {
            return;
        }
        ReadersRepository.get(context).sync(chatId, success -> {
            if (success) {
                ReceivedTimestampDecorator.reloadChat(chatId);
            }
        });
    }

    static boolean shouldSync(boolean syncEnabled, boolean syncAvailable, String chatId) {
        return syncEnabled && syncAvailable && ReadersChat.isMultiMemberChat(chatId);
    }

    /** 画面とトークの組で初めてのときだけ true を返し、以後は false を返します。 */
    static boolean markFirstOpen(Object screen, String chatId) {
        if (screen == null || chatId == null) {
            return false;
        }
        synchronized (OPENED) {
            Set<String> chats = OPENED.get(screen);
            if (chats == null) {
                chats = new HashSet<>();
                OPENED.put(screen, chats);
            }
            return chats.add(chatId);
        }
    }

    /** View の Context から Activity を探します。見つからなければ Context 自身を画面として扱います。 */
    private static Object screenOf(Context context) {
        Context current = context;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof Activity) {
                return current;
            }
            if (!(current instanceof ContextWrapper)) {
                break;
            }
            Context base = ((ContextWrapper) current).getBaseContext();
            if (base == current) {
                break;
            }
            current = base;
        }
        return context;
    }

    static void resetForTesting() {
        synchronized (OPENED) {
            OPENED.clear();
        }
    }
}
