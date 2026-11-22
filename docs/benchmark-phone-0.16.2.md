# Phone Macrobenchmark — Mew 0.16.2

Run on the physical **CPH2707** (OnePlus Nord 5), Android 16 / API 36, `arm64-v8a`, 1080×2400. This is the phone-grade
run that the Revyl cloud emulator could not provide.

The app under test was a **separate, release-signed benchmark build**: package `com.past9.phoneaos.bench`, version
`0.16.2`/182, R8-minified, non-debuggable, `profileable`, with its own data directory. The real `com.past9.phoneaos`
install (0.16.1/181) and its chats/keys were never modified; the benchmark packages were uninstalled afterwards.

- Benchmarks: AndroidX Macrobenchmark 1.5.0, 6–10 iterations per case, animations off, screen awake, charging.
- Test APK: `com.past9.phoneaos.macrobenchmark`, signed with the same release key.
- Metrics: `StartupTimingMetric`, `FrameTimingMetric`, `MemoryUsageMetric`, `TraceSectionMetric`.
- Power: not run. `PowerMetric` only supports a physical Pixel 6+, and this is a OnePlus.

## Startup

| Case | TTID median | Graph trace median | Heap max | RSS-anon | RSS-file |
|---|---:|---:|---:|---:|---:|
| Cold, into Home | **505.5 ms** | 93.7 ms | 22.9 MB | 49.2 MB | 134.4 MB |
| Warm | **157.4 ms** | 0 ms | 14.4 MB | 42.9 MB | 99.5 MB |
| Hot | **161.8 ms** | — | 16.4 MB | 45.0 MB | 102.0 MB |
| Cold, directly into Chat | **495.6 ms** | 94.3 ms | 25.6 MB | 53.8 MB | 138.3 MB |

`mew.chat.group` was **1.1 ms** on the cold-chat launch. Folding the seeded 270-row thread is not a startup
bottleneck. The graph (Room open, settings, wake/reminder arming) is about **94 ms** of the ~500 ms cold start.

## Chat

| Case | Frames | Frame CPU P50 / P90 / P99 | Overrun P90 / P99 | RSS-anon | Heap | GPU |
|---|---:|---:|---:|---:|---:|---:|
| Long thread scroll | 385.5 | **10.9 / 18.2 / 25.9 ms** | 6.2 / 13.9 ms | 49.4 MB | 17.5 MB | 64.4 MB |
| Expand live-call block | 206.5 | **12.2 / 23.3 / 26.8 ms** | **11.4 / 14.3 ms** | 58.2 MB | 15.9 MB | 65.2 MB |

The live-call expansion is the clearest jank target: its P90 is **23.3 ms** and P90 overrun **11.4 ms**, versus
18.2 / 6.2 ms for plain chat scrolling. It expands the collapsed call card with `animateContentSize` and lays out the
transcript; this is worth a follow-up trace.

## Screens

Each frame row is 6 iterations. Six static/empty screens were measured for memory only, because they do not redraw
during the block and `FrameTimingMetric` correctly refuses a trace with no RenderThread frames.

| Route | Frames | P50 / P90 / P99 ms | Overrun P90 ms | RSS-anon |
|---|---:|---:|---:|---:|
| home | 111 | 8.4 / 12.6 / 16.7 | 1.0 | 55.0 MB |
| profile | 118 | 8.1 / 12.5 / 17.3 | 0.7 | 58.6 MB |
| tasks | 102 | 8.9 / 12.3 / 14.1 | 0.5 | 56.8 MB |
| people | 92.5 | 8.9 / 11.9 / 15.1 | 0.7 | 51.0 MB |
| person | 110.5 | 8.4 / 12.3 / 16.6 | 0.8 | 55.1 MB |
| memory | 124 | 7.9 / 13.1 / 18.3 | 1.4 | 58.1 MB |
| routines | 83 | 10.0 / 13.0 / 17.6 | 1.2 | 52.9 MB |
| connections | 93 | 9.2 / 13.0 / 21.0 | 1.4 | 52.7 MB |
| settings | 98 | 9.2 / 12.0 / 14.4 | -0.0 | 67.4 MB |
| notifications | 125 | 7.5 / 12.6 / 16.4 | 0.5 | **72.6 MB** |
| report | 16 | 5.2 / 11.1 / 13.6 | -0.8 | 48.7 MB |
| style | 91.5 | 9.8 / 13.5 / 16.2 | 2.1 | 57.2 MB |
| linked devices | 20 | 6.1 / 12.5 / **101.2** | 1.3 (P99 105.3) | 51.8 MB |
| project chat | 6 | 11.6 / 18.7 / 23.6 | 6.3 | 48.6 MB |
| open chat | 105.5 | 9.7 / 13.5 / 17.4 | 1.2 | 57.9 MB |
| mini-apps | — | memory only | — | 45.4 MB |
| mini-app | — | memory only | — | 45.6 MB |
| key setup | — | memory only | — | 47.5 MB |
| recordings | — | memory only | — | 45.7 MB |
| call screen | — | memory only | — | 46.5 MB |
| memory page | — | memory only | — | 48.9 MB |

Two concrete outliers:

- **Linked devices** has a single **101 ms** P99 frame (105 ms overrun) despite a 12.5 ms P90. The page is only 20
  frames; likely first layout or a connection animation.
- **Notifications** holds the highest anonymous RSS of the screen set: **72.6 MB** with 180 seeded rows. Settings
  (67.4 MB) and profile (58.6 MB) follow.

## Browser, background, notifications, entry points

| Benchmark | Result | Memory |
|---|---|---|
| Browser: local page load + scroll | P50 8.8 / P90 11.4 / P99 13.8 ms; overrun P90 -0.4 ms; `mew.browser.goto` 638.3 ms | RSS-anon 64.5 MB, RSS-file **182.4 MB**, GPU **91.5 MB** |
| Reschedule every clock | **197.2 ms** | RSS-anon 23.9 MB |
| Routine worker | **64.4 ms** (queue trace 13.3 ms) | RSS-anon 22.2 MB |
| Helper watchdog | **16.7 ms** | RSS-anon 16.4 MB |
| Node runtime | **113.4 ms** | RSS-anon 15.0 MB |
| Notification writes (250) | **321.8 ms** | RSS-anon 24.1 MB |
| Notification prune | **6.3 ms** | — |
| Boot/rearm | **1,400 ms** | RSS-anon 33.0 MB |
| Idle in background | — | RSS-anon 45.0 MB, RSS-file 130.9 MB |
| Notification listener intake | 18 records, **124.5 ms** total (~6.9 ms each) | RSS-anon 38.1 MB, GPU 7.2 MB |
| Assist sheet | TTID **374.1 ms** | RSS-anon 43.8 MB, heap 25.2 MB |
| Share sheet | TTID **366.3 ms** | RSS-anon 41.0 MB, heap 23.9 MB |

## What the memory numbers mean

`MemoryUsageMetric` reports **RSS**, not PSS, for the app's main process. Each value is the maximum sample in an
iteration, then the median across iterations:

- `RSS-anon` — anonymous resident pages: Java/native heap and other process allocations, the closest of these numbers
  to actual memory pressure.
- `RSS-file` — file-backed resident pages: APK, dex, native libraries, fonts and WebView's Chromium library. These pages
  are reclaimable under pressure and are not the same thing as a leak.
- `HeapSize` — ART heap after GC.
- `GPU` — graphics allocations (textures, surfaces, buffers), not Java heap.

Only the main process is measured. A WebView renderer running in `:sandboxed_process0` is not included, so the browser
number is a lower bound for the whole browser cost.

## Findings

1. **Live-call expansion is the main UI jank hotspot** (P90 23.3 ms, overrun P90 11.4 ms). The collapsed block itself
   is correct; the animation/expansion is the cost.
2. **Linked devices has a 101 ms frame** at P99; inspect the first-layout/connection animation.
3. **WebView is the heaviest memory user — but the headline numbers are not anonymous heap.** The browser measured
   182.4 MB RSS-file and 91.5 MB GPU, but only **64.5 MB RSS-anon** (about 15 MB more than chat). Most of the file
   figure is Chromium's `libwebviewchromium.so` plus APK/dex/native mappings; the GPU figure is graphics memory. The
   benchmark keeps the browser attached and active, so this is the cost of an open browser, not proof of a leak.
   `BrowserService` stops after 3 minutes idle and destroys every WebView, and helper engines are released when the
   helper finishes; the main engine intentionally stays for the agent. Measure after leaving the browser and waiting
   out that idle window before calling it retained memory. Also note the test loads a local page with no images,
   scripts or video, so real sites can be higher.
4. **The notifications screen's 72.6 MB RSS-anon is not 180 rendered notification rows.** The screen only draws
   `recent.take(6)` (at most six notification rows); the other seeded rows are just `NotificationRow` objects in the
   `recent(200)` query. The expensive part of this route is that it eagerly loads a **96×96 bitmap icon for every
   launcher app** and remembers the whole list, plus the normal Compose/SubScreen cost. Settings is next at 67.4 MB
   because it is a fully composed `Column(verticalScroll)` rather than a lazy list. These are warm-process high-water
   marks: the activity is cleared between iterations but the process is not killed, so later screens inherit caches
   from earlier ones. They identify the highest screens, not a leak by themselves. The concrete lever is lazy icon
   loading in `NotificationsScreen` (and optionally a lazy settings list), then re-measure with a fresh process.
5. **Cold startup is ~500 ms**, of which the graph is ~94 ms; warm/hot are ~160 ms. Deferring more wake/reminder/DB
   work out of `Graph` would move the cold number, if it is worth the trade-off.
6. **Background work is modest**: routine 64 ms, reschedule 197 ms, 250 notification writes 322 ms, Node 113 ms,
   watchdog 17 ms. Boot/rearm at 1.4 s is the longest background path but happens once per boot.
7. **Chat grouping is not a bottleneck**: 1.1 ms in the cold-chat trace.

## Harness notes

- `com.past9.phoneaos.bench` was used so the benchmark seed could clear chat rows without touching the real Mew data.
- Cold startup seeds outside `measureRepeated` and then disables the system-bound notification listener and
  force-stops the package; otherwise Macrobenchmark rejects the run because the seed process is still alive.
- Two chat selector tests (`scrollThroughOpenUiCard`, `questionAndApprovalCards`) did not find their cards after
  full-screen swipes; the cards are present in the seeded chat and covered by the OpenUI/screenshot suites. The
  scroll itself is covered by `scrollLongThread`; the live-call block by `expandTheLiveCallBlock`.
- Static screens are memory-only by design; an idle background frame test was changed to memory-only for the same
  reason.
- `PowerMetric` was skipped: it requires a physical Pixel 6 or newer.

The disposable benchmark app, test APK, traces and screenshots were removed after the run. The real Mew install remains
`0.16.1`/181 with its data intact.
