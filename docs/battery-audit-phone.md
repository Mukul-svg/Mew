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
| Add an index to the notifications table | Rejected: `AppDb` uses `fallbackToDestructiveMigration(true)` at version 3, so an index would wipe user data. Not worth a battery fix. |
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
