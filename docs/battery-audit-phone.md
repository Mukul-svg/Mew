# Battery audit — Android phone only

Date: 21 Nov 2026. Scope: `app/` (the Android phone app). Wear OS, watch faces, the Wear data layer and the desktop host are explicitly out of scope and were removed from the fix list.

## How this was audited

Six read-only audits were run across services/wakeups, live voice, networking/browser/Node, Compose, sensors/notifications and Wear. Every candidate below was re-read in the source and checked against Android platform contracts. The Wear findings were removed on request; no Wear or watch-face code is touched.

The audit does **not** claim a measured micro-percentage. It separates changes that are behavior-identical by construction from changes that would need a device power trace.

## Corrections to the first review

| First review said | Audit result |
|---|---|
| Wear always-on gesture service restarts forever | Real, but **out of scope**: Wear only. Removed. |
| Wear sensor/`rememberT`/complication fixes | **Out of scope**: Wear only. Removed. |
| Live screen capture runs with the display off in every case | Only when the live session survives screen-off (minimised bubble or over-lock), because `AssistActivity.onStop` ends the sheet otherwise. Real for that case; not the whole product. |
| `LiveFrames` encodes twice per look | Confirmed: `ScreenContext.save` writes a 1280 px q82 JPEG, then `LiveFrames.encode` decodes it and re-encodes 768 px q60. This audit removes the second compression for unchanged screens, not the first write yet. |
| Add an index to the notifications table | Rejected in the first pass because `AppDb` used `fallbackToDestructiveMigration(true)` at version 3. The second pass replaces that with real 1→2→3→4 migrations, so the indices are now safe and implemented. |
| Screen-sheet polling is always safe to pause | Confirmed only for lifecycle STOPPED (Home, screen off, keyguard). The loops are now gated with `repeatOnLifecycle(RESUMED)`, so a dialog freezes the preview until it closes and then refreshes immediately. No information is lost. |
| The call Wi-Fi lock leaks | Confirmed: the 45-minute wake lock has a timeout, `WifiLock.acquire()` does not. A teardown path that is missed leaves the radio in high-performance mode indefinitely. |

## Phone fixes implemented

All of these preserve the visible output, latency and quality of the existing path.

1. **Screen watch/peek/setup polling** — `ui/screens/ScreenSheets.kt:335,370,471`. The 200/220/1000 ms loops now run inside `repeatOnLifecycle(RESUMED)`. Nothing is visible while the activity is stopped; the first resumed frame is fetched immediately (sequence reset), then the normal cadence resumes. This removes radio, JPEG decode and main-thread work while backgrounded or screen-off.
2. **Screen helper idle loop** — `screen/helper/HelperMain.kt:82-96`. The helper now waits 5 s when no hidden display is open and 400 ms while one is open, and `create`/`release` wake it through the monitor. A newly opened display is seen immediately (no safety delay for the money-app sweep); with no display the process stops waking 2.5 times a second.
3. **Live screen frames** — `assist/LiveFrames.kt:57,102`. No capture while `PowerManager.isInteractive` is false, so a pocketed or locked bubble session stops taking assistant screenshots. Also `LiveFrames.encode` (`:121`) builds the 16×16 grid first and compresses/base64 only when the screen changed or the user forced a look. A still screen no longer costs a JPEG encode per look; a changed screen produces the same 768 px q60 JPEG as before.
4. **WhatsApp call Wi-Fi lock** — `browser/BrowserEngine.kt:335,351-354`. The high-performance Wi-Fi lock now has the same 45-minute ceiling as the wake lock, is removed when the call ends, and is released when the engine is destroyed. Normal calls are unchanged; a missed teardown can no longer leave the radio high-performance forever.
5. **Wake channel teardown** — `wakeup/WakeChannel.kt:71,186`. `WakeChannelService.onDestroy` now cancels the reconnect loop, closes the socket, and clears the link key. The 20 s-ping connection can no longer outlive the service after an OEM kill.
6. **Speaker queue** — `voice/PlaybackQueue.kt:35,58-65`. The speaker thread blocks on the next chunk instead of polling every 200 ms. `close()` enqueues the existing empty sentinel to wake it. Audio bytes, order, interrupt behaviour and `speaking` transitions are unchanged; five wakeups per second during silence are gone.
7. **Unused infinite animations** — `ui/screens/Chat.kt:554-559`, `assist/LiveSheet.kt:195-201`, `share/ShareActivity.kt:394-399`. The voice-note wave, the LIVE dot and the share audio bars now create their infinite transition only while playing/live. Idle pixels are identical because the animated value was already unused there. On play the animation starts at its first frame instead of a previously arbitrary phase; same motion, same speed, no quality loss.
8. **Location fix cleanup** — `tools/PhoneLocation.kt:238-252`. The first provider that answers now cancels the shared `CancellationSignal`, so GPS/network `getCurrentLocation` requests do not keep running until their timeouts. The chosen fix and its latency are unchanged.

## Deliberately not changed

- Wake-word microphone and its untimed partial wake lock: that is the hotword path; the existing `ListenRules` already pauses it on battery saver, low battery, charging-only, calls and microphone contention.
- Exact alarms, WorkManager routine timing, and the 6-hour wake-alarm lock: visible timing and a ringing alarm are the product.
- Live audio sample rate, buffers, echo cancellation and audio focus: quality/latency.
- Live frame cadence (1.2 s active / 6 s idle / 400 ms forced): feature quality.
- Browser WebView background behaviour: product decision (background page audio and agent browsing).
- Health probes for non-current brains: they feed the Settings/linked-device status; changing their schedule is user-visible.
- Notification prune/index: the index would require destructive migration; the DELETE is small.
- `LiveSession` hold flusher (250 ms): low impact and changing it would need a new condition in `TurnHold`.
- Wake-detector allocations: the engine may retain the float buffer; not proven bit-identical without an engine-level test.
- MainActivity Composio poll: it intentionally keeps checking while the user finishes an OAuth sign-in in another app.
- `LiveHost.watchAway`: the post-call collector is event-driven, not a timer.

## Revyl before/after plan

The original Revyl session `bf3eae8c-81a3-460e-99d7-5d3973625974` ran build `remote-c0ab26da` on a Pixel 7 / Android 14 with manual onboarding:

- average CPU **46.4%**, peak **97%**
- average memory **310 MiB**, peak **367 MiB**
- average **44.2 fps**, minimum 5 fps
- 19 steps, 500.7 s

A controlled before/after was then run with the same scripted path: complete onboarding (OpenCode Go key, DeepSeek V4.1 Flash), open chat, send the booking-plan prompt, wait 30 s, stop.

| Run | Build | Session | Avg CPU | Avg memory | Duration | Visible result |
|---|---|---|---|---|---|---|
| Before | `remote-c0ab26da` (`077b523b…`) | `89b967dc-eafd-46f1-ac81-a209d744d0a5` | 35.6% | 302.8 MiB | 272 s | Working, 6-step plan |
| After | `remote-5255ad40` (`bbf37cd3…`) | `a5016d60-5100-4d45-9db4-4d19bae3acc5` | 40.0% | 329.4 MiB | 310 s | Working, 6-step plan, one more web-read step |

Both sessions reached the same onboarding, home and chat screens and produced the same six-step “Two mid-row seats, Thu 17 Dec” card. The after run did one more browser read in the same window, which explains part of the CPU/memory difference. Crucially, this flow does not exercise the screen-watch sheets, Live frames, WhatsApp call locks, wake channel, or speaker queue, so the run-level delta is **not** evidence of a battery regression or improvement. Revyl proves visual and flow continuity here. A power claim still requires a connected phone and Power Profiler/Power Rails traces on the specific paths.

Atlas comparison of the two builds found **9 shared screens and 0 screens unique to the after build**. Two screens were observed only in the before run: the finished plan-response state and the reminder-readiness sheet. Both are capture/flow timing, not removed UI; the after session showed the same plan card before it was stopped. The full flow (splash, onboarding steps, home, chat, prompt, working state, plan card) is present in both.

## Second pass — every phone module

Eight parallel audits covered agent runtime/tools, data/startup, networking/wakeups, Compose, browser/Node, media/voice, services/scheduling, and an independent web-research pass. Every candidate below was re-read in source and checked against current Android/Kotlin/SQLite/OkHttp/Node documentation. The rule was: no change to visible output, animation timing, image/audio quality, answer freshness, latency, accuracy or reliability.

### Implemented

**Data and startup**

- Non-destructive Room migrations `1→2`, `2→3`, `3→4`; `fallbackToDestructiveMigration` removed. A future schema bump can no longer wipe chat, memories, tasks or people. `AppDb` version 4.
- Indices on `chat_items(kind, createdAt)`, `turns(thread, id)`, `notifications(postedAt)`, `notifications(pkg, postedAt)` and `person_logs(personId, at)`. The v3→v4 migration only creates indices; rows are untouched.
- `clearAllTables()` moved off the main thread for Reset All.
- Pinned agents, their histories and cards now use the existing atomic temp-file + fsync + rename writer instead of `File.writeText`.

**Agent runtime/tools**

- `Block.Image` Base64 is memoised in a small LRU keyed by path + size + mtime. The bytes sent are identical; a long turn no longer reads and encodes the same screenshot on every step.
- Worker tool-name set cached by settings/Composio/machines/learned-app state. Subscription tool events no longer rebuild the 65+ tool catalog just to compare a name.
- Hot-path regexes hoisted in memory recall, branching, routine parsing and helper failure checks.

**Networking and wakeups**

- `WakeChannelService` now loads the saved link before its reconnect loop, stops itself if the link is off, retries a failed `wakeups.list` with backoff on the same socket, and stops retrying on `Unpaired`/`CertChanged`.
- Pinned TLS clients are cached by certificate fingerprint instead of rebuilt per attempt.
- `RemoteSession.jobEnded()` schedules the existing cellular pause after the last job; `readRunState()` settles the work lock if a `run_finished` event was lost.
- The media loopback server is stopped and its shown-media cache released on session close.
- Handoff uses one pooled client and cancels the blocking long-poll when the app leaves the screen.

**Browser, Node and local servers**

- Browser idle check is one scheduled wake at the idle deadline instead of a 30 s poll; page use pushes the deadline.
- Browser screenshot drawing stays on main, PNG compression/write moves to `Dispatchers.IO`, and the bitmap is recycled.
- Node processes are `destroy()`ed, given 2 s, then `destroyForcibly()`ed on cancellation.
- Local MCP server: connection semaphore, 10 s socket timeout, 16 KB header cap, 1 MB body cap, and authentication before route/tool construction or body allocation.
- Automation server: 10 s socket read timeout (its existing line/body caps remain).

**Media, voice and images**

- Duplicate loudness scan removed from the Live microphone loop; the level is computed once and reused for the quiet-period check.
- Camera photos, video key frames and screenshot encoders recycle the bitmaps they own; `ScreenContext.save` never recycles the caller's bitmap.
- Full-screen `VideoView` gets `onRelease { stopPlayback() }`.
- `LiveFrames` screen-off gate and grid-first encode remain from the first pass.

**Compose and lifecycle**

- Screen watch/peek bitmaps decode on `Dispatchers.Default`, not the main thread.
- Screen-run playback is gated to `RESUMED`; it pauses offscreen and resumes at the same step.
- Recordings folder reads run on `Dispatchers.IO`; the visible 1.5 s cadence is unchanged.
- Chat audio progress updates only while `RESUMED`, with one immediate refresh on return.
- Project-chat event refresh runs only while `STARTED`; the initial load catches up on return.
- Chat rows have stable content types.
- Unused infinite transitions (from the first pass) remain.

**Services and scheduling**

- Notification backfill is one sequential worker, not one coroutine per notification. Watched-app filtering happens before `App.graph`. The 7-day prune runs at most once a day; retention is unchanged.
- Reminder alarms fire directly through `goAsync()` instead of starting the foreground WorkService and posting “Mew is working”.
- Screen-service notification thumbnails stop when the display is off; the next screen-on tick refreshes.
- An explicit background-screen close sends `exit` to the shell helper; job-end release still keeps it warm.
- Alarm volume is persisted before raising and restored after a kill; the previous in-memory-only restore could leave the alarm stream at maximum.
- Wake-word battery receiver buckets the percentage at the only threshold that changes a decision (`<20%`), not every percent.
- Calendar attendee counts use one query per 500 events instead of one query per event.
- Reminder preferences are only committed when the armed/said sets actually changed.
- Alarm cancellation uses `FLAG_NO_CREATE`, so it cannot create a PendingIntent as a side effect.

### Rejected or deferred — quality/freshness risk

- **Full chat history paging / recent-window query**: the UI currently shows the whole chat; changing it needs a visible-history and scroll-position design.
- **Lazy `Graph` / deferred startup**: broad lifecycle change; needs a cold-start profile before touching.
- **Learned-app JS moved out of SharedPreferences**: would require a verified file migration; the 14 MB worst case is real but the migration is a data-risk change.
- **People decryption cache**: improves CPU but lengthens plaintext lifetime; needs a security decision.
- **Dictation whole-take streaming**: avoids 3–5 full copies, but changes the JSON/RPC framing; needs desktop-protocol proof.
- **TTS cache pruning**: old chat voice notes could become unplayable; rejected without a reference-aware policy.
- **Quiet-level sort cache**: needs a maintained order-statistics structure; deferred.
- **WebView `onPause()`/`pauseTimers()`**: would stop background page JS/audio; product decision, not a quality-neutral change.
- **JS polling → event bridge**: large renderer refactor; worth doing with a WebView test harness.
- **Call-bridge WebAudio teardown**: needs a real WhatsApp call on-device to prove no audio regression.
- **Popup cap**: an OAuth flow can legitimately use several windows; needs a device matrix.
- **5-minute background health probes**: increases failover discovery latency; rejected as a freshness change.
- **Skipping full sync after reconnect**: risks stale history; rejected.
- **Silent heads-up screen-on**: product behavior, not a pure resource fix.
- **Boot/time rearm de-duplication**: scheduler refactor; deferred.
- **Host-side Node/SSE fixes**: the Node host is a PC service, not the phone runtime; audited separately and not part of this phone battery batch.
- **RGB_565 / low-RAM decode / lossy re-encode**: rejected for image quality.
- **Baseline/startup profiles**: recommended, but generating them needs a macrobenchmark device run.
- **Compose graphicsLayer rewrites, `SubcomposeAsyncImage` replacement, root/Home recomposition split**: visible-frame or broad refactor risk; deferred until screenshot/recomposition tests.

### Web references used

- Room migrations/indices: https://developer.android.com/training/data-storage/room/migrating-db-versions, https://developer.android.com/training/data-storage/room/defining-data#column-indexing
- Room invalidation: https://developer.android.com/reference/androidx/room/InvalidationTracker
- Compose performance: https://developer.android.com/develop/ui/compose/performance/bestpractices, https://developer.android.com/develop/ui/compose/performance/stability/strongskipping
- Bitmap/ImageDecoder: https://developer.android.com/topic/performance/graphics/load-bitmap, https://developer.android.com/reference/android/graphics/ImageDecoder
- WebView lifecycle/memory: https://developer.android.com/reference/android/webkit/WebView#onPause(), https://developer.android.com/develop/ui/views/layout/webapps/manage-webview-memory
- OkHttp connections/WebSockets: https://square.github.io/okhttp/features/connections/, https://square.github.io/okhttp/features/websockets/
- AudioRecord/AudioTrack: https://developer.android.com/reference/android/media/AudioRecord, https://developer.android.com/reference/android/media/AudioTrack
- Doze/WorkManager/FGS: https://developer.android.com/training/monitoring-device-state/doze-standby, https://developer.android.com/develop/background-work/services/fgs/service-types
- Node child processes: https://nodejs.org/api/child_process.html
- SQLite query planner: https://www.sqlite.org/optoverview.html
