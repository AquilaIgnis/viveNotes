# What a box hanging over a sheet edge should do

Run 2026-09-13, against a real `notes.db`, to settle one question that a screenshot answers slowly
and badly: **Export PDF's "Fit content to pages" was printing one working on top of another.**

`memory/pdfExportPlan.md` PD5 said the box should be pulled back inside the tile its corner is in, by
exactly its overhang — and said, in as many words, "no collision handling, deliberately". That reads
fine against the reference drawing, which is a canvas with two diagrams on it. Against a page of
handwriting it is wrong in a way the drawing cannot show: **the room above is not empty.** Pulling a
paragraph up by its overhang does not find space, it finds the paragraph before it.

The page that showed it is the Computer architecture notebook's first, 499 strokes in twelve
entities over three sheets. The last entity of sheet one overhung the boundary by 120 dp, was pulled
up into a 64 dp gap, and landed 56 dp on top of the one above it.

## The candidates

| | when the box hangs over | when the pull would land on something | what follows it |
|---|---|---|---|
| `pull` | pulled back | pulled back anyway | untouched — **what shipped** |
| `clear` | pulled back | starts the next sheet *if that lands clear*, else left cut | untouched |
| `break` | pulled back | starts the next sheet | **comes with it** |
| `break-y` | pulled back | starts the next sheet, carried down a column but not across | mixed |
| `break-only` | starts the next sheet | starts the next sheet | comes with it |

`break`'s last column is the whole of the argument. A break that moves only the broken item puts it
at the top of the next sheet and leaves the *next* paragraph exactly where the broken one used to
be — so the overlap is not removed, it is relocated one sheet down. Carrying the rest of the strip by
the same distance is what makes a page break a page break. Per strip, and the strips are the grid's
own columns and rows, because a break in one column of tiles must not move the column beside it:
that is a different stack of sheets.

## Result

`overlaps / cut / sheets`, A4 at the new quarter-inch default — `results/margin-0.25.txt`, and the
same shape at 0 and 1 inch:

```
  page  strokes  entities            pull           clear           break         break-y      break-only
     3        4         8    2 /  0 /   2    0 /  0 /   3    0 /  0 /   3    0 /  0 /   3    0 /  0 /   3
     5      304        15    0 /  0 /   1    0 /  0 /   1    0 /  0 /   1    0 /  0 /   1    0 /  0 /   1
     6      416        13    0 /  0 /   2    0 /  0 /   2    0 /  0 /   2    0 /  0 /   2    0 /  0 /   2
     7      499        12    2 /  0 /   3    0 /  0 /   3    0 /  0 /   3    0 /  0 /   3    0 /  0 /   3
     8      584        19    1 /  0 /   3    0 /  0 /   3    0 /  0 /   3    0 /  0 /   3    0 /  0 /   4
     9     9555       397   30 /  0 /  38    0 /  7 /  39    0 /  0 /  40    3 /  5 /  39    5 /  0 /  43
```

- **`pull` overlaps on nearly every page that needs more than one sheet**, and thirty times on the
  9,555-stroke page. It is not an edge case; it is what the rule does whenever a page is full.
- **`break` is the only rule that is clean on every real page** — no overlap, nothing cut, and the
  same sheet count as before. Page 9 is a scribble corpus rather than a document (395 entities over
  38 sheets of canvas) and costs it two sheets and, at some margins, two or three overlaps where two
  carried bands meet.
- **`clear` and `break-y` trade the overlap for a cut**: refusing to move anything they cannot place
  cleanly, they leave 5–7 entities with a sheet edge through them. A sliced formula is the failure
  the option exists to prevent, so buying one with the other is not a fix.
- **`break-only`** — never pull — costs paper for nothing: the pull is still right when the room
  above really is empty, which on a sparse canvas it usually is.

`break` shipped. `PageTiling.Fitting` is the Kotlin, `PageTilingTest` pins the four cases, and PD5
in `memory/pdfExportPlan.md` is rewritten around it.

## Running it

```
python3 study.py  path/to/notes.db 0.25            # the table above
python3 render.py path/to/notes.db <pageId> 0.25 pull  before.svg
python3 render.py path/to/notes.db <pageId> 0.25 break after.svg
rsvg-convert -w 1800 before.svg -o before.png
```

`render.py` draws the emitted sheets with the ink on them, in export order, which is how the overlap
was confirmed to be the reported one and the fix confirmed to be a fix. Pull a database off a device
with `adb exec-out run-as com.vivenotes.debug cat databases/notes.db`; nothing here writes to it, and
no database belongs in this repo.

## What this is not

Text containers and tables are measured with `StaticLayout` on a device, so a page whose entities are
typed text comes out of this with only its stored frame — the numbers above are honest for ink and
approximate for text. That is the right trade for this question: the fit was wrong on pages of ink,
and `PageTiling` is Android-free precisely so that the arithmetic can be checked without a device.
