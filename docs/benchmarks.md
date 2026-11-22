# Macrobenchmark

The suite lives in `:macrobenchmark` and measures the real phone app (package `com.past9.phoneaos`) in the
`benchmark` build type: release-like R8/minification, **not debuggable**, signed with the debug key so a device
accepts it, and `profileable` for Macrobenchmark. Nothing from `app/src/benchmark` is compiled into a release or a
normal debug APK.

It covers startup, every navigation route, the long chat (including OpenUI and the live-call block), the WebView
browser, Node, notification intake, WorkManager routines, the helper watchdog, boot/rearm, the assist sheet, the share
sheet, and optional hardware power on a physical Pixel 6 or newer.

## Device and one-time setup

- Android 14+ (`minSdk 34`), `arm64-v8a`. A physical phone is required: the app ships no x86 emulator ABI.
- USB debugging on, device unlocked, screen awake.
- No model key is needed. The seeded data is deterministic and local.
- Power tests need a **physical Pixel 6/Pro or newer**, unplugged, on battery.

From the repository root, on Unix:

```bash
./gradlew :app:assembleBenchmark :macrobenchmark:assembleBenchmark
adb install -r app/build/outputs/apk/benchmark/app-benchmark.apk
adb install -r macrobenchmark/build/outputs/apk/benchmark/macrobenchmark-benchmark.apk
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell svc power stayon true
adb shell pm grant com.past9.phoneaos android.permission.RECORD_AUDIO
adb shell pm grant com.past9.phoneaos android.permission.POST_NOTIFICATIONS
adb shell cmd notification allow_listener com.past9.phoneaos/com.past9.phoneaos.system.NotificationReader
```

On Windows the repository has no `gradlew.bat`; use the wrapper directly:

```powershell
java -classpath "gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain `
  :app:assembleBenchmark :macrobenchmark:assembleBenchmark
```

`scripts/benchmark-device.ps1` does the install and device setup, and checks that exactly one device is attached.

## Run

All benchmarks:

```bash
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest
```

On Windows (this checkout has no `gradlew.bat`), use the wrapper jar directly:

```powershell
java -classpath "gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain `
  :macrobenchmark:connectedBenchmarkAndroidTest
```

One class (fast feedback while tuning a fix):

```bash
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.past9.phoneaos.macrobenchmark.ChatBenchmark
```

One method:

```bash
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.past9.phoneaos.macrobenchmark.ChatBenchmark#scrollLongThread
```

Results and traces are written to
`macrobenchmark/build/outputs/connected_android_test_additional_output/benchmark/`. Each iteration has a Perfetto
trace linked from the test output; open it in <https://ui.perfetto.dev> and search the trace sections below.

## What is measured

| Suite | What it exercises | Metrics |
|---|---|---|
| `StartupBenchmark` | cold, warm and hot process/Activity start; home; cold start directly into chat | `StartupTimingMetric`, `mew.startup.graph`, `mew.chat.group`, `MemoryUsageMetric` |
| `ChatBenchmark` | a 220+ row thread, OpenUI money card, question/approval cards, expanding the live-call block after a hand-off | `FrameTimingMetric`, `mew.chat.group`, `MemoryUsageMetric` |
| `ScreensBenchmark` | home, profile, tasks, people, person, memory, memory page, routines, connections, settings, notifications, recordings, report, style, mini-apps, a mini-app, linked devices, key setup, call screen, project chat, chat | `FrameTimingMetric`, `MemoryUsageMetric` |
| `BrowserBenchmark` | the real WebView browser service loading a deterministic local page, then scrolling and reloading it | `FrameTimingMetric`, `mew.browser.goto`, `MemoryUsageMetric` |
| `BackgroundBenchmark` | every clock/WorkManager schedule, a routine worker, the helper watchdog, Node 24, notification writes and pruning, an idle background process | `TraceSectionMetric`, `MemoryUsageMetric`, `FrameTimingMetric` |
| `NotificationBenchmark` | the system shade -> `NotificationListenerService` -> Room path, using `cmd notification post` | `mew.notif.record`, `MemoryUsageMetric` |
| `EntryPointsBenchmark` | the system assist sheet, the share sheet, the boot/rearm path | `StartupTimingMetric`, `mew.bench.rearm`, `MemoryUsageMetric` |
| `PowerBenchmark` | chat and browser on battery; automatic skip off a physical Pixel 6+ | `PowerMetric` |

### Trace sections

These names are stable and are the first thing to open in Perfetto when a metric regresses:

| Trace section | Code |
|---|---|
| `mew.startup.graph` | `App.graph` (Room open, settings, WorkManager, wake/reminder arming) |
| `mew.chat.group` | `Chat.group` + `withMarker` in `ChatScreen` |
| `mew.live.handoff` | the whole live-assistant hand-off, including the screen read, hand-off row and send |
| `mew.live.saveCall` | the end-of-call write: last turns, hand-off backfill, `rememberCall` |
| `mew.bg.routine` | `RoutineWorker.doWork` |
| `mew.bg.watchdog` | `WatchdogWorker.doWork` |
| `mew.node.run` | `RunCodeTool` (Node process start, script, output) |
| `mew.browser.goto` | `BrowserEngine.goto` |
| `mew.notif.record` | `NotificationReader.record` |
| `mew.bench.*` | the benchmark-only receiver's seed/trigger actions |

## Seeded state

The benchmark build has a receiver, `com.past9.phoneaos.BENCHMARK`, that seeds private app data the benchmark process
cannot reach without `run-as`. Every bulk seed clears the app's Room tables first, so one class's rows never make the
next class slower or change its layout.

- `seed_onboard`: completes onboarding without a key.
- `seed_chat`: 220+ user/agent rows, activity steps, an OpenUI money card, question and approval cards, a helper, a
  live card, a report, a notice, and the **exact live-call shape from 0.16.1**: a user turn, `On it: …`, the agent's
  reply, the hand-off row written at session end, then a later turn. `Chat.group` must show one call block.
- `seed_screens`: goals, tasks, memories, people with dates, four routines, a live card and 180 notifications.
- `reschedule`, `rearm`, `run_routine`, `watchdog`, `node`, `browser`, `notifications`, `prune`, `clear`: one
  background path at a time.
- `allow_shell_notifications`: allows the shell package so `cmd notification post` reaches the listener.

The route extra is `--es benchmark_route <route>` (for example `chat`, `routines`, `browser`, `settings`). It is read
only when `BuildConfig.BENCHMARK` is true.

## Reading a run

- **Startup**: `timeToInitialDisplayMs` is the honest cold-start number; `mew.startup.graph` explains how much is the
  graph, and `MemoryUsageMetric` catches a graph that grows.
- **Frames**: compare p50/p90/p99 `frameDurationCpuMs` and `frameOverrunMs`. A p99 spike with a high `mew.chat.group`
  sum points at grouping; a spike without it points at Compose/layout or image decode.
- **Background**: `mew.bg.routine` and `mew.node.run` are wall-clock async slices. The memory max is the process high
  water mark; run the idle case twice to see whether Node/MCP/browser subprocesses are being released.
- **Power**: only trust it on a physical Pixel 6+ on battery; compare like-for-like screen brightness and network.
- **Traces**: every iteration's Perfetto trace is the source of truth. Check the `mew.*` slice, then the thread's
  `deliverInputEvent`/`Choreographer` slices and the process's CPU frequency residency.

## Limits (deliberate)

- **Voice and audio**: a live Gemini session needs a key, a microphone and a speaker. The suite measures the assist
  sheet, the call screen and the live-call chat block, but not speech recognition/playback quality.
- **Camera, OCR, screen helper, wake word**: hardware/permission paths. They are exercised by their targeted tests; a
  Macrobenchmark number from a device without those permissions would be meaningless.
- **Wear OS**: the watch app and faces are only configured with `-PwithWear` and need a paired watch. The phone-side
  relay/protocol is covered by `:wearproto` and phone unit tests, not by this phone benchmark.
- **Linked computer / SSH machines**: require credentials and a second machine.
- **Model turns**: non-deterministic and network-bound, so they are not in the measured block. The routine worker
  still runs its real WorkManager/DB/runtime path; the model call fails fast without a key.
