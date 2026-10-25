# Forcing a GC at idle — what it buys the next heavy job

Run 2026-10-24 to answer one question: would calling the garbage collector while the app sits idle
(the owner on a menu) leave the next expensive computation — a page load, a recognition pass — "all
the heap it needs"?

**No. It buys nothing measurable and costs CPU every time it runs.** Not implemented.

## How it was measured

`app/src/androidTest/java/com/vivenotes/data/IdleGcCostTest.kt`, opt-in. Two jobs from the real
code: `open` is `InkPageLoader.load` on the Recognition Test page (433 strokes, 87 erases, 19 moves
replayed), and `recognize` is `HandwritingRecognizer.recognize` over the same page through the
bundled PP-OCRv5 graphs. Each round runs a job twice, in alternating order. One run follows an idle
second and the other follows an idle second that starts with `Runtime.gc()`. Before both, the heap
is filled with small garbage to 1 MB short of ART's next collection point. That is the worst case
for the uncollected arm, so the collected arm's margin is an upper bound.

```bash
./gradlew :app:assembleRelease :app:assembleReleaseAndroidTest -PtestRelease   # copy vivenotes-*.apk out first
adb install -r app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
adb shell am instrument -w -e class com.vivenotes.data.IdleGcCostTest \
  -e idleGc true -e idleGcForeground true -e idleGcRounds 12 \
  com.vivenotes.testrelease.test/com.vivenotes.ViveNotesTestRunner
adb pull /sdcard/Android/data/com.vivenotes.testrelease/cache/idle-gc-cost-foreground.csv
python3 -I simulations/idle-gc/analyze.py idle-gc-cost-foreground.csv
```

testRelease, so the numbers are from R8 code under AOT, and installed as `com.vivenotes.testrelease`
so a run neither kills nor replaces the app someone is using.

**`-e idleGcForeground true` is not optional for a real answer.** On Android 16 an instrumentation
process alone is not jank-perceptible (`ActivityManager.isProcStateJankPerceptible`, behind the
`jankPerceptibleNarrow` flag, no longer counts `PROCESS_STATE_FOREGROUND_SERVICE`). So ART sizes the
heap as it would for a background app, without its 2× foreground growth multiplier. The flag opens
`MainActivity` for the run, which makes the process the top app and gives the heap the editor's own
live set. Both modes are kept below, because the background one is the harsher test.

## Result

Medium_Tablet AVD, API 36, x86_64, 4 cores. ART 2.1.0 running the concurrent mark-compact collector,
heap growth limit 192 MB, `heapmaxfree` 8 MB, `heaptargetutilization` 0.75. Each cell is 12 rounds.

**Foreground** — `MainActivity` on screen, importance 100. `results/emulator-medium-tablet-api36-foreground.csv`

| | open: no GC | open: idle GC | recognize: no GC | recognize: idle GC |
|---|---|---|---|---|
| job wall ms, median (IQR) | 173.5 (171.9–177.6) | 173.8 (173.0–176.5) | 170.7 (166.0–178.3) | 170.8 (165.9–180.4) |
| paired difference, median [95% CI] | | +0.1 ms [−4.0, +2.8] | | −1.8 ms [−10.4, +5.1] |
| GCs during the job, mean | 2.50 | 2.83 | 1.00 | 1.00 |
| blocking GCs during the job, all rounds | 0 | 1 | 0 | 0 |
| heap left before the next GC at job start | 8.1 MB | 7.9 MB | 4.5 MB | 8.1 MB |
| the idle GC itself, wall / CPU | — | 27.8 / 13.6 ms | — | 21.2 / 14.1 ms |
| process CPU, idle second + job, median | 219.5 ms | 248.5 ms | 693 ms | 681 ms |

**Background policy**, instrumentation only, importance 125. `results/emulator-medium-tablet-api36-background.csv`

| | open: no GC | open: idle GC | recognize: no GC | recognize: idle GC |
|---|---|---|---|---|
| job wall ms, median (IQR) | 190.2 (188.2–191.2) | 189.0 (188.0–190.9) | 203.4 (194.3–211.9) | 197.5 (192.4–202.4) |
| paired difference, median [95% CI] | | +1.1 ms [−2.3, +3.6] | | +6.8 ms [−9.5, +16.0] |
| GCs during the job, mean | 17.9 | 17.6 | 7.6 | 7.2 |
| blocking GCs during the job, all rounds | 0 | 1 | 0 | 0 |
| heap left before the next GC at job start | 1.0 MB | 1.0 MB | 1.0 MB | 1.0 MB |
| the idle GC itself, wall / CPU | — | 6.5 / 6.1 ms | — | 6.5 / 6.2 ms |

Every interval straddles zero. No job, in either mode, waited on a collection for more than 2 ms in
total across twelve rounds.

## Why there is nothing to win

**A collection does not reserve heap; ART re-sizes the heap to the live set after every one.**
`Heap::GrowForUtilization` (art/runtime/gc/heap.cc) sets the next target to the bytes still alive
plus `clamp(live × (1/0.75 − 1), heapminfree, heapmaxfree)`, doubled in the foreground. The comment
there says it outright: "This doesn't actually resize any memory. It just lets the heap grow more
when necessary." An idle GC therefore hands the next job exactly the headroom ART's own first GC in
that job would have — about 8 MB for this app in the foreground (the "heap left" rows). Beyond that
the heap grows on demand up to the growth limit, and ART always runs a full, soft-reference-clearing
collection before it throws `OutOfMemoryError`, so an idle GC cannot prevent one either. The most it
can save is the one collection that would have swept the pre-existing garbage, and that collection
runs concurrently on `HeapTaskDaemon`, not on the job's thread. The background run is the extreme
case — eighteen collections per page load — and even there the job's time did not move.

**The heavy jobs' memory is not in the Java heap.** The owner's own debug session (2026-10-24, after
formula recognition and a SymPy evaluation) held 12 MB of live Java objects in a 24 MB heap, against
572 MB allocated in the native heap: ONNX Runtime's sessions and arena, Python, ink. A GC manages
only the first number.

**What the idea wants, the platform already does.**

- On leaving the foreground ART runs a full compacting collection by itself
  (`Heap::UpdateProcessState` → `RequestCollectorTransition`), whenever the app has allocated a
  quarter of its headroom since the last one, and every collection schedules a heap trim five
  seconds later (`RequestTrim`), which returns freed pages to the kernel.
- Native growth triggers collections (`CheckGCForNative`), which is what frees Bitmap pixels. The
  warm-up logged one freeing 98 MB of large objects with nothing asking for it.
- Under memory pressure ActivityManager asks idle background apps to collect
  (`AppProfiler.performAppGcsIfAppropriateLocked` → `processInBackground` → `ActivityThread.GcIdler`).

**The guidance agrees.** developer.android.com's ART notes: calling `System.gc()` "should be far
less necessary with ART, particularly if you're invoking garbage collection to prevent
`GC_FOR_ALLOC`-type occurrences or to reduce fragmentation". `StrictMode.ThreadPolicy` has
`detectExplicitGc()` to flag the call. And `System.gc()` itself did nothing on its own before API 35:
libcore only ran it if `runFinalization()` followed. Targeting 35+ it now calls `Runtime.gc()`,
which the test confirms on this image (`systemGcCollects=true`).

## Where the memory actually is

If the goal is headroom for the next heavy job, the lever is the native heap, not the GC. ONNX
Runtime's CPU arena grows to a pass's high-water mark and keeps it for as long as the session
stays open, which is the life of the process. `memory/performancePlan.md` PF1 has the options
(bound the arena, reuse the input buffer, close idle sessions).
