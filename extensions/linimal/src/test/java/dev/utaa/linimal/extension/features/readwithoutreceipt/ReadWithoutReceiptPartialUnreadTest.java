package dev.utaa.linimal.extension.features.readwithoutreceipt;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public final class ReadWithoutReceiptPartialUnreadTest {
    private static final class FakeDatabase implements ReadWithoutReceiptPartialUnread.Database {
        Long rowExists = 1L;
        Long calls = 0L;
        Long newer = 2L;
        int changed = 1;
        RuntimeException queryFailure;
        final List<String> events = new ArrayList<>();
        String[] countArgs;
        String[] updateArgs;

        @Override
        public void beginTransaction() {
            events.add("begin");
        }

        @Override
        public void setTransactionSuccessful() {
            events.add("success");
        }

        @Override
        public void endTransaction() {
            events.add("end");
        }

        @Override
        public Long queryLong(String sql, String[] args) {
            if (queryFailure != null) {
                throw queryFailure;
            }
            if (sql.equals(ReadWithoutReceiptPartialUnread.READ_ROW_EXISTS_SQL)) {
                return rowExists;
            }
            if (sql.equals(ReadWithoutReceiptPartialUnread.NEWER_CALL_COUNT_SQL)) {
                return calls;
            }
            countArgs = args;
            return newer;
        }

        @Override
        public int update(String sql, String[] args) {
            events.add("update");
            assertEquals(ReadWithoutReceiptPartialUnread.RAISE_READ_COUNT_SQL, sql);
            updateArgs = args;
            return changed;
        }
    }

    @Test
    public void countsFromTheSentPointAndUpdatesInOneTransaction() {
        FakeDatabase database = new FakeDatabase();
        assertTrue(ReadWithoutReceiptPartialUnread.apply(database, "chat", 42L, "me"));
        assertArrayEquals(new String[] {"chat", "42", "me"}, database.countArgs);
        assertArrayEquals(new String[] {"2", "chat", "2"}, database.updateArgs);
        assertEquals(List.of("begin", "update", "success", "end"), database.events);
    }

    @Test
    public void newerCallRowsSkipTheCorrectionBecauseTheirCountIsUnknown() {
        FakeDatabase database = new FakeDatabase();
        database.calls = 1L;
        assertFalse(ReadWithoutReceiptPartialUnread.apply(database, "chat", 42L, "me"));
        assertEquals(null, database.updateArgs);
    }

    @Test
    public void zeroNewerMessagesClearsTheBadge() {
        FakeDatabase database = new FakeDatabase();
        database.newer = 0L;
        assertTrue(ReadWithoutReceiptPartialUnread.apply(database, "chat", 42L, "me"));
        assertArrayEquals(new String[] {"0", "chat", "0"}, database.updateArgs);
    }

    @Test
    public void missingRowOrCountWritesNothing() {
        FakeDatabase missingRow = new FakeDatabase();
        missingRow.rowExists = null;
        assertFalse(ReadWithoutReceiptPartialUnread.apply(missingRow, "chat", 42L, "me"));
        assertEquals(List.of("begin", "end"), missingRow.events);

        FakeDatabase missingCount = new FakeDatabase();
        missingCount.newer = null;
        assertFalse(ReadWithoutReceiptPartialUnread.apply(missingCount, "chat", 42L, "me"));
        assertEquals(List.of("begin", "end"), missingCount.events);
    }

    @Test
    public void invalidInputDoesNotOpenATransaction() {
        FakeDatabase database = new FakeDatabase();
        assertFalse(ReadWithoutReceiptPartialUnread.apply(database, "", 42L, "me"));
        assertFalse(ReadWithoutReceiptPartialUnread.apply(database, "chat", 0L, "me"));
        assertFalse(ReadWithoutReceiptPartialUnread.apply(database, "chat", 42L, null));
        assertFalse(ReadWithoutReceiptPartialUnread.apply(null, "chat", 42L, "me"));
        assertTrue(database.events.isEmpty());
    }

    @Test
    public void notifiesOnlyWhenTheCountIncreased() {
        AtomicInteger notified = new AtomicInteger();
        FakeDatabase raised = new FakeDatabase();
        assertTrue(ReadWithoutReceiptPartialUnread.applyAndNotify(raised, "chat", 42L, "me", notified::incrementAndGet));
        assertEquals(1, notified.get());

        FakeDatabase unchanged = new FakeDatabase();
        unchanged.changed = 0;
        assertFalse(ReadWithoutReceiptPartialUnread.applyAndNotify(unchanged, "chat", 42L, "me", notified::incrementAndGet));
        assertEquals(1, notified.get());
        assertEquals(List.of("begin", "update", "success", "end"), unchanged.events);
    }

    @Test
    public void databaseFailureEndsTheTransactionAndStaysSilent() {
        AtomicInteger notified = new AtomicInteger();
        FakeDatabase database = new FakeDatabase();
        database.queryFailure = new IllegalStateException("database locked");
        assertFalse(ReadWithoutReceiptPartialUnread.applyAndNotify(database, "chat", 42L, "me", notified::incrementAndGet));
        assertEquals(0, notified.get());
        assertEquals(List.of("begin", "end"), database.events);
    }

    @Test
    public void notifierFailureKeepsTheCommittedResult() {
        assertTrue(ReadWithoutReceiptPartialUnread.applyAndNotify(new FakeDatabase(), "chat", 42L, "me", () -> {
            throw new IllegalStateException("bus unavailable");
        }));
    }

    @Test
    public void executorWithoutPatchedMethodsIsIgnored() {
        assertFalse(ReadWithoutReceiptPartialUnread.sync(new Object(), "chat", 42L));
        assertFalse(ReadWithoutReceiptPartialUnread.sync(null, "chat", 42L));
    }
}
