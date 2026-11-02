"""部分既読のバッジ更新 SQL を、Java の定数のまま実際の SQLite で検証する。"""
import re
import sqlite3
import unittest
from pathlib import Path

SOURCE = (
    Path(__file__).resolve().parents[1]
    / "extensions/linimal/src/main/java/dev/utaa/linimal/extension/features/readwithoutreceipt"
    / "ReadWithoutReceiptPartialUnread.java"
).read_text(encoding="utf-8")


def constant(name):
    body = re.search(rf"static final String {name} =(.*?);", SOURCE, re.S).group(1)
    return "".join(re.findall(r'"([^"]*)"', body))


READ_ROW = constant("READ_ROW_EXISTS_SQL")
NEWER = constant("NEWER_INCOMING_COUNT_SQL")
RAISE = constant("RAISE_READ_COUNT_SQL")
OTHER = "u-other"
ME = "u-me"


class PartialReadBadgeSqlTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.executescript(
            """
            CREATE TABLE chat (chat_id TEXT PRIMARY KEY, message_count INTEGER,
                read_message_count INTEGER, latest_mentioned_position INTEGER,
                unread_type_and_count TEXT, read_up TEXT);
            CREATE TABLE chat_history (id INTEGER PRIMARY KEY, server_id TEXT, type INTEGER,
                chat_id TEXT, from_mid TEXT);
            """
        )
        self.db.execute("INSERT INTO chat VALUES ('c', 10, 5, 3, 'COUPON:1', '100')")

    def row(self, server_id, from_mid=OTHER, kind=1, chat="c"):
        self.db.execute(
            "INSERT INTO chat_history (server_id, type, chat_id, from_mid) VALUES (?, ?, ?, ?)",
            (server_id, kind, chat, from_mid),
        )

    def newer(self, point):
        return self.db.execute(NEWER, ("c", str(point), ME)).fetchone()[0]

    def raise_to(self, count):
        return self.db.execute(RAISE, (str(count), "c", str(count))).rowcount

    def counts(self):
        return self.db.execute("SELECT message_count, read_message_count FROM chat").fetchone()

    def test_counts_only_newer_incoming_messages_of_the_chat(self):
        self.row("100")
        self.row("101")
        self.row("102", kind=5)
        self.row("103", from_mid=None)
        self.row("104", from_mid="")
        self.row("105", from_mid=ME)
        self.row("106", kind=27)
        self.row("107", kind=38)
        self.row("108", kind=2)
        self.row("109", chat="other")
        self.row(None)
        self.assertEqual(self.newer(100), 2)

    def test_server_ids_compare_numerically_across_digit_counts(self):
        self.row("99")
        self.row("1000")
        self.assertEqual(self.newer(100), 1)

    def test_read_row_lookup_is_scoped_to_the_chat(self):
        self.row("100", chat="other")
        self.assertIsNone(self.db.execute(READ_ROW, ("c", "100")).fetchone())
        self.assertIsNotNone(self.db.execute(READ_ROW, ("other", "100")).fetchone())

    def test_raises_read_count_to_leave_only_newer_messages(self):
        self.assertEqual(self.raise_to(2), 1)
        self.assertEqual(self.counts(), (10, 8))

    def test_zero_newer_messages_clears_the_badge(self):
        self.assertEqual(self.raise_to(0), 1)
        self.assertEqual(self.counts(), (10, 10))

    def test_never_lowers_the_read_count(self):
        self.assertEqual(self.raise_to(7), 0)
        self.assertEqual(self.counts(), (10, 5))
        self.assertEqual(self.raise_to(50), 0)
        self.assertEqual(self.counts(), (10, 5))

    def test_null_counts_are_not_written_as_null(self):
        self.db.execute("UPDATE chat SET read_message_count = NULL")
        self.assertEqual(self.raise_to(2), 1)
        self.assertEqual(self.counts(), (10, 8))

    def test_other_columns_stay_untouched(self):
        self.raise_to(2)
        self.assertEqual(
            self.db.execute(
                "SELECT latest_mentioned_position, unread_type_and_count, read_up FROM chat"
            ).fetchone(),
            (3, "COUPON:1", "100"),
        )


if __name__ == "__main__":
    unittest.main()
