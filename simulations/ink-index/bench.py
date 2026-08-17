"""What the schema-19 index change buys, on a page the size of the real one.

`ink_strokes` carried one index, on `pageId`. Schema 19 replaces it with `(pageId, seq, id)`, which
has to pay for itself twice over: it is wider than what it replaces, and ink is the largest table in
the app. This measures the two queries that touch it — the page load and the draw-order allocator —
against the page the 2026-08-11 study used: page "4", 9,553 strokes, 5.4 MB of points.

Desktop SQLite, not Android's. What transfers is the query plan and the ratio; the absolute
milliseconds do not.

    python3 simulations/ink-index/bench.py
"""

import os
import random
import sqlite3
import tempfile
import time

STROKES = 9553
POINT_BYTES = 565
PAGES = 4

TABLE = """
CREATE TABLE ink_strokes (
  id TEXT NOT NULL, pageId TEXT NOT NULL, seq INTEGER NOT NULL,
  brushFamily TEXT NOT NULL, brushVersion INTEGER NOT NULL, sizeDp REAL NOT NULL,
  colorArgb INTEGER NOT NULL, colorFollowsTheme INTEGER, epsilon REAL NOT NULL,
  stabilization INTEGER NOT NULL, minX REAL NOT NULL, minY REAL NOT NULL,
  maxX REAL NOT NULL, maxY REAL NOT NULL, points BLOB NOT NULL, enc TEXT NOT NULL,
  createdAt INTEGER NOT NULL, groupId TEXT, deletedAt INTEGER, PRIMARY KEY(id))
"""

BY_PAGE_OLD = "SELECT * FROM ink_strokes WHERE pageId = ? AND deletedAt IS NULL ORDER BY seq"
BY_PAGE_NEW = "SELECT * FROM ink_strokes WHERE pageId = ? AND deletedAt IS NULL ORDER BY seq, id"
NEXT_SEQ = "SELECT COALESCE(MAX(seq), -1) + 1 FROM ink_strokes WHERE pageId = ?"

OLD_INDEX = "CREATE INDEX index_pageId ON ink_strokes (pageId)"
NEW_INDEX = "CREATE INDEX index_pageId_seq_id ON ink_strokes (pageId, seq, id)"


def uuid7(milliseconds: int) -> str:
    """The id shape `Document.newId()` writes: 48 bits of clock, so hex order is time order."""
    high = f"{milliseconds:012x}"
    rest = f"{random.getrandbits(76):019x}"
    return f"{high[:8]}-{high[8:12]}-7{rest[:3]}-8{rest[3:6]}-{rest[6:18]}"


def build(path: str, index_sql: str) -> sqlite3.Connection:
    database = sqlite3.connect(path)
    database.execute(TABLE)
    database.execute(index_sql)
    blob = os.urandom(POINT_BYTES)
    rows: list[tuple[object, ...]] = []
    stamp = 1_700_000_000_000
    for page in range(PAGES):
        for seq in range(STROKES):
            stamp += 1
            rows.append(
                (uuid7(stamp), f"page-{page}", seq, "marker", 1, 3.0, -16777216, None,
                 0.1, 0, 0.0, 0.0, 1.0, 1.0, blob, "ink/v1", stamp, None, None)
            )
    # Insertion order is not draw order on a real page either — replay, import and undo all write
    # out of sequence — and rowid order is exactly what the old plan fell back on for ties.
    random.shuffle(rows)
    database.executemany("INSERT INTO ink_strokes VALUES (" + ",".join("?" * 19) + ")", rows)
    database.commit()
    database.execute("ANALYZE")
    return database


def best_milliseconds(database: sqlite3.Connection, sql: str, repeats: int) -> float:
    best: float | None = None
    for _ in range(repeats):
        start = time.perf_counter()
        database.execute(sql, ("page-2",)).fetchall()
        elapsed = time.perf_counter() - start
        best = elapsed if best is None else min(best, elapsed)
    return (best or 0.0) * 1000


def plan(database: sqlite3.Connection, sql: str) -> str:
    rows = database.execute("EXPLAIN QUERY PLAN " + sql, ("page-2",))
    return " | ".join(row[3] for row in rows)


def index_bytes(database: sqlite3.Connection) -> int:
    query = "SELECT SUM(pgsize) FROM dbstat WHERE name LIKE 'index_%'"
    return int(database.execute(query).fetchone()[0])


def main() -> None:
    with tempfile.TemporaryDirectory() as directory:
        old = build(os.path.join(directory, "old.db"), OLD_INDEX)
        new = build(os.path.join(directory, "new.db"), NEW_INDEX)

        print(f"{STROKES} strokes x {PAGES} pages, {POINT_BYTES} B of points each\n")
        cases = (
            ("18  byPage ", old, BY_PAGE_OLD, 5),
            ("19  byPage ", new, BY_PAGE_NEW, 5),
            ("18  nextSeq", old, NEXT_SEQ, 200),
            ("19  nextSeq", new, NEXT_SEQ, 200),
        )
        for name, database, sql, repeats in cases:
            milliseconds = best_milliseconds(database, sql, repeats)
            print(f"{name}  {milliseconds:8.3f} ms   {plan(database, sql)}")

        rows = STROKES * PAGES
        for name, database in (("18", old), ("19", new)):
            total = index_bytes(database)
            print(f"\nschema {name} indexes: {total / 1e6:.2f} MB, {total / rows:.0f} B per stroke")


if __name__ == "__main__":
    main()
