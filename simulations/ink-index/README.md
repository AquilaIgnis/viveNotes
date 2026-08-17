# Ink draw-order index — what schema 19 costs and what it buys

Run 2026-08-17, before the migration was written, to answer one question honestly: `ink_strokes`
carried a single index on `pageId`, and `memory/inkSyncPlan.md` IS1 replaces it with
`(pageId, seq, id)` — a wider index on the largest table in the app. Does it pay for itself?

Two queries touch it, and both are on a latency budget from `memory/plan.md` §9:

- `byPage` — `WHERE pageId = ? AND deletedAt IS NULL ORDER BY seq, id`, the page-load read, inside
  the "tap a page → page on screen, < 50 ms" budget.
- `nextSeq` — `MAX(seq) + 1 WHERE pageId = ?`, the draw-order allocator, which runs on **every
  stroke commit**, inside "stroke-end → committed, < 16 ms".

The corpus is the page the 2026-08-11 page-open study used: page "4", **9,553 strokes, 5.4 MB of
points** — 565 bytes per row — times four pages, inserted in shuffled order because rowid order is
not draw order on a real page and rowid is exactly what the old plan fell back on to break ties.

```
python3 simulations/ink-index/bench.py     # results/bench.txt
```

## Result

| | schema 18 | schema 19 | |
|---|---|---|---|
| `byPage` | 25.9 ms | **22.0 ms** | the temp B-tree disappears |
| `byPage` plan | `SEARCH … USING INDEX index_pageId` + `USE TEMP B-TREE FOR ORDER BY` | `SEARCH … USING INDEX index_pageId_seq_id` | |
| `nextSeq` | 4.51 ms | **0.007 ms** | ~640× |
| `nextSeq` plan | `SEARCH … USING INDEX index_pageId` | `SEARCH … USING **COVERING** INDEX` | |
| index bytes | 16 B/stroke, 0.61 MB | 61 B/stroke, 2.33 MB | +45 B/stroke |

Desktop SQLite, not Android's. What transfers is the plan and the ratio, not the milliseconds.

**`nextSeq` is the finding.** It looks like a trivial scalar read and was spending **4.5 ms of a
16 ms budget** computing one integer — because `MAX(seq)` over an index that stops at `pageId` has
to visit every row of the page to read `seq` from it. With `seq` in the index it is one seek to the
end of the range, and the row is never touched. The cost is quadratic in the wrong way: it grows
with how much ink is already on the page, so the slowest stroke to commit is the one drawn on the
page someone has actually been drawing on.

**`byPage` is a smaller, structural win.** 15% here, and the ratio is not the point — the old plan
sorted **whole rows**, `points` blobs included, so on a page big enough the sorter spills to a temp
file and the cost stops being linear. The new plan has no sorter at all.

**The cost is 45 bytes per stroke**, all of it the UUID in the index. At the 500,000-stroke corpus
limit `NotebookTransferManager` enforces, that is ~22 MB of index against ~280 MB of points. It also
*replaces* the `pageId` index rather than joining it — `pageId` is still the leading column, so the
foreign key to `pages` stays covered — which means a stroke insert maintains one index, as before.

## Why `id` is in the index at all

It is the tiebreak that makes draw order a total order. `seq` alone is not: two devices drawing on
one page while offline both allocate the same value from their own copy, and SQLite would settle the
tie by rowid, which differs per device — the same rows, two different pages. `id` is a UUIDv7 whose
hex form sorts chronologically under BINARY collation, so the tie is broken by real draw time and
identically everywhere. `memory/inkSyncPlan.md` §1.
