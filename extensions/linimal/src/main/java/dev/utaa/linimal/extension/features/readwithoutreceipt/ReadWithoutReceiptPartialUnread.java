package dev.utaa.linimal.extension.features.readwithoutreceipt;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;

/**
 * 部分既読の成功後に、一覧バッジを押した位置より新しい未読数へ減らします。
 *
 * <p>公式に部分更新の API はないため、バッジの元になる chat.read_message_count だけを増やします。
 * read_up と message_count、メンション、クーポンの件数は公式の管理のまま触りません。
 * 押した行が見つからない場合は何も書きません。</p>
 */
final class ReadWithoutReceiptPartialUnread {
    private static final String MAIN_DATABASE = "linimalMainDatabase";
    private static final String OWN_MID = "linimalOwnMid";
    private static final String NOTIFY_CHAT_LIST = "linimalNotifyChatListUpdated";

    static final String READ_ROW_EXISTS_SQL =
            "SELECT 1 FROM chat_history WHERE chat_id = ? AND server_id = ? LIMIT 1";

    /**
     * 受信時に message_count が増える行だけを数えます。自分の行は from_mid が空か自分の mid です。
     * type は MESSAGE(1)、VOIP(4)、STICKER(5)、POSTNOTIFICATION(8)、CHATEVENT(13)、
     * E2EE_UNDECRYPTED(17) で、取り消し済み(27, 28, 38)と入室・退室などのシステム行は含みません。
     * 引数は文字列で渡るため、server ID の比較では両辺を INTEGER にそろえます。
     */
    static final String NEWER_INCOMING_COUNT_SQL =
            "SELECT COUNT(*) FROM chat_history WHERE chat_id = ?"
                    + " AND CAST(server_id AS INTEGER) > CAST(? AS INTEGER)"
                    + " AND from_mid IS NOT NULL AND from_mid NOT IN ('', ?)"
                    + " AND type IN (1, 4, 5, 8, 13, 17)";

    /** 通話は状態によって message_count が増えず、SQL では判別できないので、含むトークは補正しません。 */
    static final String NEWER_CALL_COUNT_SQL =
            "SELECT COUNT(*) FROM chat_history WHERE chat_id = ?"
                    + " AND CAST(server_id AS INTEGER) > CAST(? AS INTEGER) AND type = 4";

    /** 増やす方向の変更だけを行います。変更がなければ 0 行になり、一覧へ通知しません。 */
    static final String RAISE_READ_COUNT_SQL =
            "UPDATE chat SET read_message_count ="
                    + " MAX(COALESCE(message_count, 0) - CAST(? AS INTEGER), 0)"
                    + " WHERE chat_id = ? AND COALESCE(read_message_count, 0) <"
                    + " MAX(COALESCE(message_count, 0) - CAST(? AS INTEGER), 0)";

    /** Android の DB 実装を隠す最小の seam です。 */
    interface Database {
        void beginTransaction();

        void setTransactionSuccessful();

        void endTransaction();

        /** 先頭行の先頭列を返します。行がなければ null です。 */
        Long queryLong(String sql, String[] args);

        int update(String sql, String[] args);
    }

    private ReadWithoutReceiptPartialUnread() {
    }

    /** 件数の算出と更新を同じ transaction で行い、途中で届いた受信との不整合を防ぎます。 */
    static boolean apply(Database database, String chatId, long point, String ownMid) {
        if (database == null || chatId == null || chatId.isEmpty() || point <= 0L
                || ownMid == null || ownMid.isEmpty()) {
            return false;
        }
        String serverId = String.valueOf(point);
        database.beginTransaction();
        try {
            if (database.queryLong(READ_ROW_EXISTS_SQL, new String[] {chatId, serverId}) == null) {
                return false;
            }
            Long calls = database.queryLong(NEWER_CALL_COUNT_SQL, new String[] {chatId, serverId});
            if (calls == null || calls != 0L) {
                return false;
            }
            Long newer = database.queryLong(NEWER_INCOMING_COUNT_SQL, new String[] {chatId, serverId, ownMid});
            if (newer == null || newer < 0L) {
                return false;
            }
            String count = String.valueOf(newer);
            int changed = database.update(RAISE_READ_COUNT_SQL, new String[] {count, chatId, count});
            database.setTransactionSuccessful();
            return changed > 0;
        } finally {
            database.endTransaction();
        }
    }

    /** 例外を呼び出し元へ伝えず、更新できた場合だけ通知します。バッジ以外の動作には影響しません。 */
    static boolean applyAndNotify(Database database, String chatId, long point, String ownMid, Runnable notifier) {
        try {
            if (!apply(database, chatId, point, ownMid)) {
                return false;
            }
        } catch (Throwable ignored) {
            // DB の競合や列の不一致では公式の値を残します。
            return false;
        }
        try {
            notifier.run();
        } catch (Throwable ignored) {
            // 通知できなくても、一覧は次回の読み込みで DB の値を表示します。
        }
        return true;
    }

    /** worker 上で呼びます。executor には patch が検証済みの公式参照だけを持つ method を追加しています。 */
    static boolean sync(Object executor, String chatId, long point) {
        try {
            Class<?> type = executor.getClass();
            Object database = type.getMethod(MAIN_DATABASE).invoke(executor);
            Object ownMid = type.getMethod(OWN_MID).invoke(executor);
            if (!(database instanceof SQLiteDatabase) || !(ownMid instanceof String)) {
                return false;
            }
            java.lang.reflect.Method notify = type.getMethod(NOTIFY_CHAT_LIST);
            return applyAndNotify(new AndroidDatabase((SQLiteDatabase) database), chatId, point, (String) ownMid, () -> {
                try {
                    notify.invoke(executor);
                } catch (ReflectiveOperationException error) {
                    throw new IllegalStateException(error);
                }
            });
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static final class AndroidDatabase implements Database {
        private final SQLiteDatabase database;

        AndroidDatabase(SQLiteDatabase database) {
            this.database = database;
        }

        @Override
        public void beginTransaction() {
            database.beginTransactionNonExclusive();
        }

        @Override
        public void setTransactionSuccessful() {
            database.setTransactionSuccessful();
        }

        @Override
        public void endTransaction() {
            database.endTransaction();
        }

        @Override
        public Long queryLong(String sql, String[] args) {
            try (Cursor cursor = database.rawQuery(sql, args)) {
                return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getLong(0) : null;
            }
        }

        @Override
        public int update(String sql, String[] args) {
            try (SQLiteStatement statement = database.compileStatement(sql)) {
                statement.bindAllArgsAsStrings(args);
                return statement.executeUpdateDelete();
            }
        }
    }
}
