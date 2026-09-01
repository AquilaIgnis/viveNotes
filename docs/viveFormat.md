# The `.vive` file — what an exported notebook contains

A `.vive` file is **one notebook**, exported from the File tab. It is a ZIP container
(`application/vnd.vivenotes.notebook+zip`) whose entries are a fixed allowlist — anything else makes
the file invalid, not merely ignored.

Source: `data/NotebookTransferManager.kt`. Container format version 1, source app schema 13.

```text
manifest.json                     small JSON header
notebook.sqlite                   the notebook itself
checksums.sha256                  SHA-256 of every other entry
attachments/<sha256>              zero or more picture files
```

Only `notebook.sqlite` is deflated. Attachments are STORED (already-compressed image bytes), and no
directory entries exist.

## `manifest.json`

Identifies the bundle and lets the importer reject a file before it opens any SQLite database.

```json
{
  "format": "com.vivenotes.notebook",
  "formatVersion": 1,
  "appSchemaVersion": 13,
  "bundleId": "019ff9e3-…",          // this export
  "createdAt": 1786603864980,        // epoch ms
  "sourceNotebookId": "019ff891-…",  // stable notebook id, same on every export
  "notebookName": "My Notebook",
  "database": { "path": "notebook.sqlite", "byteCount": 839680, "sha256": "74b8…" },
  "attachments": [ { "id", "path", "mimeType", "pixelWidth", "pixelHeight", "byteCount", "sha256" } ],
  "counts": { "sections": 2, "pages": 3, "strokes": 436, "revisions": 2, "attachments": 0 }
}
```

`counts` is not decoration: import compares it against the real table counts, and a mismatch aborts.

## `notebook.sqlite`

A compact, transactionally consistent snapshot produced with `VACUUM INTO` (never a copy of the live
file, so no `-wal`/`-shm` ever travels), then pruned to the one notebook and re-vacuumed. Tables:

| Table                                   | Holds                                                                                |
| --------------------------------------- | ------------------------------------------------------------------------------------ |
| `notebooks`                             | exactly one row — the exported notebook, live (never a tombstone)                    |
| `sections`, `pages`                     | its structure, **including tombstones** (`deletedAt`) so a restore can re-delete     |
| `page_content`                          | the current document per page: `docJson` + the `format` id that wrote it (`json/1`)  |
| `page_revisions`                        | full version history — gzipped document and ink snapshots, each with its own SHA-256 |
| `ink_strokes`                           | every stroke: brush, colour, bounds and the encoded input points (`ink/androidx1`)   |
| `ink_erases` + `ink_erase_targets`      | erases as replayable operations, not as removed strokes                              |
| `ink_moves` + `ink_move_targets`        | lasso moves/resizes, likewise replayable                                             |
| `attachments`                           | metadata only for the pictures the documents actually reference                      |
| `vive_bundle`                           | `format`, `formatVersion`, `appSchemaVersion`, `notebookId`                          |
| `room_master_table`, `android_metadata` | Room/SQLite bookkeeping carried by the snapshot                                      |

Because ink is an append-only operation log, the bundle carries the _operations_ — a `.vive` restores
the exact drawing history, not a flattened result.

Format identity is stamped in three independent places so a stray SQLite file cannot pass as a
notebook: the manifest, the SQLite header (`application_id = 0x56495645` = `VIVE`, `user_version = 1`)
and the `vive_bundle` table.

### What is deliberately left out

- **`local_metadata`** — installation identity, not notebook content; it must never travel.
- **`attachment_text`** (schema 15) — cached OCR of pictures. It is derived, so the importing device
  rebuilds it with whatever engine that build ships. Dropping it also keeps the bundle format at
  schema 13, so every `.vive` ever written still imports.
- Other notebooks, and any attachment no document or revision references.

## `attachments/<sha256>`

Content-addressed picture bytes, WebP or JPEG. The entry name, the manifest `id`, the declared
`sha256`, the file's actual digest and `attachments.id` must all be the same lowercase 64-hex digest
— so the same screenshot pasted ten times is one entry. Import additionally decodes each file's
header and checks the pixel dimensions match the metadata.

## `checksums.sha256`

Plain `sha256␠␠path` lines, sorted by path, covering the manifest, the database and every attachment
— but never itself. Import requires the checksum list and the archive entry list to match exactly,
in both directions, and verifies every digest before SQLite opens anything.

## Reading one by hand

```bash
unzip -l notebook.vive
unzip -p notebook.vive manifest.json | jq
unzip -o notebook.vive -d out && sqlite3 out/notebook.sqlite '.tables' 'SELECT * FROM vive_bundle;'
```

The database is ordinary SQLite with no views, triggers or virtual tables, so any SQLite tool reads
it. Editing it, however, invalidates both `checksums.sha256` and the manifest digests, and the file
will be refused on import.

## Import, in one paragraph

Import is a **restore**, not a merge: choosing a `.vive` means "make this notebook match the saved
copy". The whole archive is copied and validated in a private cache directory first — entry
allowlist, size and count limits, checksums, read-only SQLite open, schema allowlist, `quick_check`,
`foreign_key_check`, document and ink decoding, attachment set equality — and only a fully validated
bundle reaches the single Room transaction that writes to the live database. Stable ids mean
reimporting the same file is idempotent — with one exception: if the sync account has *permanently
deleted* `sourceNotebookId`, the server retires that id for good, so the import is moved to a fresh
notebook id and the device records where it went. Reimports stay idempotent through that record, but
the notebook no longer has the id the bundle names. See `memory/notebookTransfer.md` for the full
semantics.
