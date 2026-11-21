# Current State

**Last updated:** 2026-11-21
**Repository:** Mew (Android phone app + Wear OS app + Node Agent Host); package ID remains `com.past9.phoneaos`
**Branch:** `main` (upstream `72ab230` + the Mew sync commits; not pushed)
**Working tree:** Release-workflow changes (`D-018`) are committed on top of the live-assistant commit. 0.16.0 build-floor, Node bundle, Mew rebrand, security fixes, CI, test fixes, assist conversation mode (`D-015`), upstream dictation/live work, and the live assistant (`D-017`) are in `main`.
**Analysis status:** initial static review complete; Android 14+ build floor, Node-only bundle, Mew rebrand, security fixes, CI, and the upstream 0.15.76 dictation/live work are implemented. The full phone unit suite after the sync was green on Windows: **2,581 tests, 0 failures, 27 skipped** (2026-11-21, including the live-assistant change). The live assistant (`D-017`) now defaults to Gemini Live with OpenCode task hand-offs and spoken progress. A real OpenCode turn and `run_code` passed on the phone. The watcher/card device probe is written but not yet run because the phone disconnected from `adb`; release signing still needs the owner's keys. The assist sheet now holds a call-like conversation (reads answers aloud, listens again) with no voice key; verified by 111 targeted tests and a debug build, not yet on a device.

This is the living snapshot of the repository. Update it after every change. Decisions and their rationale live in `DECISIONS.md`; the working rules for agents live in `AGENT.md`.

---

## 1. What this repository is

Mew is a sideloaded Android assistant that runs its own agent loop, browser, memory wiki, routines, connected-app actions, SSH “machines”, voice, and a phone-as-window mode where a paired PC or server is the brain. The package ID remains `com.past9.phoneaos` for update compatibility.

It also contains:

- a Wear OS app and shared Watch protocol module;
- three Watch Face Format watch faces;
- a zero-dependency Node “Mew Host” for running jobs on user machines;
- a docs site and localization pipeline.

The README describes it as a personal, always-on agent: no account, no subscription, no vendor relay, MIT licensed, sideloaded rather than distributed through Google Play.

---

## 2. At a glance

| Area | Observation |
|---|---|
| Version | `0.16.0` (`versionCode 180`, above upstream 0.15.76/179) |
| Modules | Default: `app` (phone) + `wearproto`; `wear` and three watch-face modules are retained and included only with `-PwithWear`; `host/` Node service |
| Code size | 542 Kotlin files, ~86.1k LOC; 300 in phone main, 163 phone tests |
| Build | Gradle 9.3.1, AGP 9.1.1, Kotlin 2.2.10, compileSdk 37; default build is phone-only |
| SDK | **`minSdk 34` (Android 14+), `targetSdk 34`**, `arm64-v8a` only |
| Tests | Full phone suite after the live-assistant change: 2,581 tests in 174 result classes, 0 failures, 27 skipped (18 Unix-shell SSH/Handoff tests on Windows + 9 conditional skips); plus Roborazzi screenshot captures |
| Locales | 54 locale folders; translation checker reports 0 problems |
| Git | `main` above upstream `72ab230`; Mew work is local and not pushed, MIT |
| Docs | Extensive: remote protocol notes, Wear protocol, background screen, wake word, translations, signing |
| CI | `.github/workflows/ci.yml` (`D-014`): Linux debug build, translation checks, Mew Host tests, and the full phone unit suite on push/PR. `.github/workflows/release.yml` (`D-018`): signed phone release on a `v*` tag/manual run, published as a GitHub Release |
| Release status | Signed phone releases are built by GitHub Actions from the published keystore (`D-018`); the four `ANDROID_*` secrets are not yet present, so no release has run |
| Runtime model | OpenCode Zen/Go API key only (`D-011`); Node 24 bundled as `jniLibs` for `run_code`/watchers/cards (`D-107`); no Claude/Codex/Antigravity packs |

---

## 3. Architecture map

```text
MainActivity + Compose UI (ui/)
              |
        AgentRuntime                 <- orchestration, turns, helpers, branches, routines
              |
        AgentLoop  ->  LlmProvider   <- Anthropic / OpenAI-compatible adapters
              |
           tools/                    <- browser, screen, apps, SSH, files, memory, tasks, voice, cards
              |
  Android services                   <- BrowserService, ScreenService, CallService,
                                        WakeWordService, DictationService, WorkService
              |
   RemoteSession  <-> pinned WSS      SubscriptionRuntime -> Node 24 + Claude Code/Codex/
   (phone as screen)                   Antigravity packs + local MCP server
```

Key entry points:

- `App.kt` builds the long-lived `Graph`: database, settings, runtime, automation server, voice, wear relay.
- `MainActivity.kt` owns navigation and wires ~23 Compose routes.
- `agent/AgentRuntime.kt` is the core: user turns, helper delegation, branching, routines, wakeups, subscription turns, prompts.
- `agent/AgentLoop.kt` is the provider-neutral loop; `Model.kt` is the neutral message format.
- `remote/` handles pairing, pinned TLS, WebSocket JSON-RPC, mirrored chat/tasks/memory, media, and device commands.
- `runtime/` downloads hash-pinned Node/CLI runtime packs and exposes phone tools to them over localhost MCP.
- `browser/` is one offscreen WebView in a foreground service, with profiles, page outlining, JS evaluation, network logging, and WhatsApp call hooks.
- `screen/` uses Shizuku or a shell helper to run other apps on a hidden display.
- `wearproto/` + `wear/` implement the Data Layer protocol, watch UI, tile, complications, calls, and rings.
- `host/agent-host.mjs` is the machine-side job runner.
- `watchface/` holds three resource-only Watch Face Format packages.

### Main flows

1. **Local API-key mode:** user message -> `AgentRuntime` -> `AgentLoop` -> provider adapter -> tools -> chat rows; helpers run in the background; double-texting forks a branch.
2. **Subscription mode:** `SubscriptionRuntime` downloads Node 24 and a CLI pack -> bridge `.mjs` script -> local MCP server -> phone tools.
3. **Remote mode:** QR/short-code pairing -> pinned TLS + pass -> WebSocket RPC -> mirrored `linked.db`; the phone shows the computer’s brain and can lend browser, notifications, files, questions/approvals, and notify.
4. **Browser:** one WebView in a foreground service; profiles keep accounts separate; the agent reads page outlines, clicks, types, evaluates JS, downloads files, and hands control to the user for sign-in/captcha.
5. **Background screen:** a hidden display via Shizuku/helper process; an app blocklist protects banking and money apps; learned steps can be replayed.
6. **Cards and mini-apps:** agent-generated OpenUI/HTML drawn with a CSP and a small JS bridge; mini-apps are served from the paired computer and intercepted/allowlisted.
7. **Wear:** watch holds no keys or memory; Data Layer relay to the phone; watch can ask, answer cards, ring, and carry a live call.

---

## 4. Strengths

- Good separation around the agent loop, providers, tools, and remote link. Most protocol and policy code is plain Kotlin and unit tested.
- Security thinking is visible: pinned TLS by certificate fingerprint, constant-time token comparison, Keystore-encrypted secrets, localhost-only MCP and automation servers with bearer tokens, CSP on cards, allowlisted mini-app loading, bank-app blocklist, no keys on the watch.
- Strong test culture on paper: broad unit tests, screenshot tests at multiple sizes, translation checks, Node host tests, and detailed manual proof logs in `PROGRESS-D.md`.
- Runtime-pack supply chain is pinned: URLs and SHA-256 hashes live in `assets/runtime/native-manifest.json`, so a compromised download host cannot substitute a different binary.
- User-facing error copy is actionable, matching the non-developer audience.
- Documentation is unusually complete for a side project, especially the remote protocol, Wear call path, and background-screen design.

---

## 5. Verification performed

- `git status`: clean before documentation work.
- `python scripts/check-translations.py` -> **54 locale folders checked, 0 problems**.
- `python scripts/test_check_translations.py` -> **13 of 13 pass on Windows** after passing `-X utf8` to the checker and decoding the child output as UTF-8. The checker itself still reports 54 locales, 0 problems.
- `node --test host/test/host.test.mjs` -> **7 of 9 pass on Windows; no hang**. The two Windows-only failures are process-control tests (`stop`, and a message restarting a job) where a `cmd.exe`-launched child is not the process `kill()` reaches. CI runs the host tests on Linux, where the original process semantics apply. The suite no longer leaks a listener when an assertion fails.
- `sh gradlew :app:prepareBundledNode` -> **BUILD SUCCESSFUL**. Downloaded the hash-pinned Node pack and staged `libnode.so`, its shared libraries, and `assets/runtime/cacert.pem` under `app/build/generated/bundledNode`.
- `sh gradlew :app:assembleDebug` -> **BUILD SUCCESSFUL**. The merged manifest contains `minSdkVersion="34"` and `targetSdkVersion="34"`. APK: `app/build/outputs/apk/debug/app-debug.apk` (169.1 MB debug; release is expected around 83 MB).
- `aapt list` on the debug APK confirms `lib/arm64-v8a/libnode.so`, `libtx*.so`, `libc++_shared.so`, and `assets/runtime/cacert.pem`.
- `aapt dump badging` reports `application-label:'Mew'`; the rebrand build is successful.
- `python scripts/check-translations.py` still reports **54 locale folders, 0 problems** after replacing the brand in the translated strings.
- `sh gradlew projects` -> default hierarchy is root + `:app` + `:wearproto` only.
- `sh gradlew -PwithWear projects` -> adds `:wear`, `:facestill`, `:faceorb`, `:facedata`.
- Configuration cache is reused successfully after the Node staging task was moved into a `buildSrc` task class (`PrepareBundledNodeTask`).
- A local, gitignored `local.properties` was created to point Gradle at the installed Android SDK.
- Installed successfully on a physical **CPH2707** (Android 16 / API 36, arm64-v8a) with `adb install -r`: **Success**.
- Cold launch succeeded (`am start -W`, 1.765 s). The onboarding welcome rendered, then the name/style/power flow.
- The power screen shows only the **OpenCode Zen or Go** subscription row; Claude, ChatGPT/Codex, and Antigravity are no longer offered. Tapping it opens the API-key screen with **OpenCode Go** selected and **OpenCode Zen** available.
- Node smoke test on the device: `libnode.so` executed as the app UID and printed **v24.18.0**; the app-style `files/runtime/bin/node` symlink also printed **v24.18.0**; a small ESM script printed `js-ok 42` and `fetch 200` for `https://example.com`.
- `adb logcat` showed no `AndroidRuntime` crash for the app.
- The temporary onboarding data (`Test`) was cleared with `run-as` because this OEM blocks `pm clear`; the app was left installed with onboarding reset. The user then completed onboarding as **Mew** on OpenCode Go (`deepseek-v4.1-flash`).
- A real OpenCode turn succeeded: the prompt “Use run_code to calculate 19*23 and tell me the result.” produced the activity “Ran code: Multiply 19 by 23” and the answer **19 × 23 = 437**.
- This proves the full bundled-Node path through the app, not just a shell test.
- The Mew rebrand was installed over the existing app; `aapt` on the installed base APK reports `application-label:'Mew'`. The existing settings survived (agent name Mew, API_KEY, OPENCODE_GO, deepseek-v4.1-flash).
- Security fix: `RemoteApi.download` sends `Authorization` only when the target has the linked brain's scheme, host and port. `RemoteLinkTest` (27 tests) passes, including `aDownloadOnlySendsThePassToTheLinkedOrigin`.
- Security fix: `WebNotifications` derives the host from the owning WebView's URL and ignores the page-supplied host. The build passes; there is no dedicated unit test.
- Added `.github/workflows/ci.yml` (`D-014`): Linux runs the debug build, translation checker, checker self-tests, Mew Host tests, and phone unit tests. Not yet executed on GitHub.
- Windows asset fix: a `prepareAgentHostAsset` copy task now stages `host/agent-host.mjs`; the broken git symlink was removed. Rebuild verification is recorded below.
- First full phone unit test suite on Windows: **2520 tests, 25 failed, 8 skipped**. All 25 were triaged and fixed (details in 6.8); the second full run is recorded below. Release signing was not exercised because no signing-properties file is present.
- Targeted re-runs after the fixes: `ProvidersTest`, `CustomProviderSettingsTest`, `NodeOpenSslConfTest`, `RuntimePacksTest`, `TranslationsCheckTest`, and `LocaleResolutionTest` all pass; `ScreenSessionTest` 21/21 pass; `MachinesTest` and `HandoffTest` skip on Windows because their fixture runs a Unix shell and POSIX SFTP.
- The phone disconnected from `adb` before the watcher/card device probe could run. The probe is written in `app/src/androidTest/.../RuntimeProbeDeviceTest.kt`; it still needs a connected, unlocked Android 14+ device.
- Local test runs created an untracked `scripts/__pycache__/test_check_translations.cpython-312.pyc`; it was removed. The repository also already tracks `scripts/__pycache__/test_check_translations.cpython-312-pytest-8.1.2.pyc`, which was preserved. Committing Python bytecode is a hygiene issue and should be cleaned up deliberately, not as a side effect of this analysis.

- Second full phone unit suite on Windows: **2517 tests, 0 failures, 0 errors, 26 skipped** (`BUILD SUCCESSFUL` in 31m 07s). The 26 skips are the 18 Unix-shell SSH/Handoff tests and 8 pre-existing conditional skips. `OpenUiButtonsAndPromptTest`, the two long-standing red tests, now pass.
- Full phone unit suite after the upstream sync: **2,580 tests, 0 failures, 0 errors, 27 skipped** (`BUILD SUCCESSFUL` in 31m 35s). The 27 skips are the 18 Unix-shell SSH/Handoff tests on Windows plus 9 conditional skips. The upstream dictation/live tests and the existing Mew tests are green.
- After the upstream sync, `./gradlew -PwithWear :wear:assembleDebug :wear:testDebugUnitTest` -> **BUILD SUCCESSFUL**. The Wear modules still build and test with the shared Gradle settings.
- `:app:assembleDebug` and `:app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**. `assets/agent-host.mjs` in the APK is **25,172 bytes** and starts with `#!/usr/bin/env node` (the real Host, not the broken 31-byte symlink). The test APK is 0.8 MB.
- Release-key guard: `:app:assembleRelease --dry-run -PsigningProps=<missing>` exits **1** with "No release key" and the published-certificate warning. No signed release was produced.
- `.github/workflows/ci.yml` has not yet run on GitHub; it is the new gate for Linux.
- The phone is no longer visible to `adb` (`adb devices` is empty), so the watcher/card probe in `app/src/androidTest/.../RuntimeProbeDeviceTest.kt` could not be installed or run.

- Corrected a rebrand regression: the HTTP `User-Agent` header had become `User-Mew` in the browser download, provider adapters, model catalogue, `web_fetch` and the watcher prompt. Restored all six; the `Agent-VScreen` attribution also stays. `:app:assembleDebug` and `ProvidersTest` pass after the fix.

- **2026-11-21 upstream sync:** fetched `Past-da-king/agent` `main` (`72ab230`, 28 commits, 0.15.73-0.15.76: whole-take dictation, instant send, live truth). Rebased the Mew work onto it; conflicts were limited to `app/build.gradle.kts` and `docs/live-voice.md`. Kept `minSdk 34`/`targetSdk 34`, Mew, OpenCode-only, Node bundle, and CI; set `versionCode 180` above upstream 179 (`D-016`). Upstream adds `Takes`, `DictationNames`, `LiveTruth`, `LiveHistory`, `LivePending`, the debug dictation probe, and their tests.
- Upstream targeted tests: **106 tests, 0 failures, 1 skipped** after fixing the Windows `TakeQueue.save` atomic replace. The first run had 5 `TakesTest` failures, all the same Windows `renameTo` issue; `:app:assembleDebug` succeeds. The full suite after the live-assistant change: **2,581 tests, 0 failures, 27 skipped**.

### Assist conversation mode (2026-11-19, `D-015`)

- The assist sheet (power button / side key, wake word, shortcut) now holds a conversation like a call: it reads each answer aloud and listens again, turn after turn. On by default: Settings > Default assistant > **Keep the conversation going** (`AssistPrefs.talk`, `x_assist_talk`).
- Voice: the configured cloud voice (Gemini/OpenAI/ElevenLabs) when present, else the phone's own TextToSpeech (`system/Speaker`, now with an utterance-completion callback and `release()`). Works with only the OpenCode key; no new key, account, or network dependency. Answers go through `SpokenResult.speakable/cut` (markdown stripped, ~4,000-char cap).
- Ends on: goodbye phrases (`AssistCore.isExit`: bye/goodbye/end call/hang up/that's all/stop listening, with ok/thanks leads and the agent's name), the End button, swiping the sheet away, or two quiet listening rounds (`AssistCore.quiet`). "stop" ends it when the agent is idle.
- Mic closed while the voice speaks (no self-echo); the mic button interrupts. Questions are read aloud with options, then it listens (existing routing). Approvals are read aloud but never answered by words — held on screen (`D-013` unchanged). A failed Gemini Live start falls back to this conversation.
- New `AssistPhase.SPEAKING`; sheet shows "Talking" (existing `voice_talking` string), an End button, and interrupt. Four new strings (`assist_talk`, `assist_talk_line`, `assist_talk_end`, `assist_talk_interrupt`) translated in all 54 locales.
- Verification: `python -X utf8 scripts/check-translations.py` → **54 locales, 0 problems**; `scripts/test_check_translations.py` → **13/13**. Targeted Gradle run: **111 tests, 0 failures, 0 skipped** — `AssistTest` 27 (5 new conversation tests), `AssistShotsTest` 26, `AssistContextTest` 21, `AssistContextShotsTest` 18, `LiveAssistTest` 17, `TranslationsCheckTest` 2. `:app:assembleDebug` **BUILD SUCCESSFUL** (169 MB debug APK). One new test initially failed (it called `sent()` before `routed()`; routing happens instead of `sent()`); fixed and re-run green. Full phone suite re-run recorded below.
- Not verified on a device: `adb devices` is empty (CPH2707 not connected). The voice loop, system-TTS sound, and power-button path need a connected Android 14+ phone. Exit phrases are English; other languages end with End or by leaving.

- **Live assistant (`D-017`):** Gemini Live is now the default when a Gemini key or a linked computer is available; otherwise the sheet falls back to system conversation mode. Real work still goes to the OpenCode agent through `hand_to_agent`. Spoken progress is emitted at most every 12 seconds, only while a job is out, and `TurnHold` waits for a natural pause. The session stays open through normal jobs, with a 10-minute unanswered-job cap; the sheet + bubble ending is goodbye/End, with a 5-minute open / 10-minute minimised idle guard. Full suite: **2,581 tests, 0 failures, 27 skipped**.
- **Release workflow (`D-018`):** `.github/workflows/release.yml` builds the phone release APK on `v*` tags or manual dispatch, signs with the published keystore from four GitHub Actions secrets, verifies it with `tools/check-apk-signature.sh`, uploads the APK + SHA-256, and creates/updates the GitHub Release as `Agent-<version>.apk`. YAML parses; it cannot run signed until the secrets are added. No release keystore is present on this machine.
### Subscription-runtime investigation (0.16.0)

Full report: [`docs/subscription-runtime-spike.md`](docs/subscription-runtime-spike.md). **Outcome: `D-107` accepted, Node-only bundle.**

Key findings:

- `targetSdk 28` was load-bearing for **more than subscriptions**: `run_code` and watchers also execute the downloaded `filesDir/runtime/bin/node`. Raising `targetSdk` without a replacement breaks core tools.
- The repository already proved the alternative: the 0.12.12 line used `targetSdk 36`, `minSdk 29`, and shipped Node/Claude/Codex/Antigravity as `jniLibs` executed from `nativeLibraryDir`. 0.13.0 deliberately switched to the thin download model and `targetSdk 28`.
- Bundle sizes from `native-manifest.json`: Node 47.4 MB compressed / 152.4 MB unpacked; Claude 108.6 / 244.6; Codex 127.3 / 315.9; Antigravity 58.0 / 190.1. All four are ~341 MB compressed and ~903 MB unpacked.
- Node-only is the chosen path: OpenCode Zen/Go API key mode needs no CLI runtime; Node is retained only for `run_code`, watchers, and card scripts. The build stages the hash-pinned Node pack as `jniLibs`; no Claude/Codex/Antigravity packs are bundled.
- The shipped SDK is now `minSdk 34` / `targetSdk 34`. Existing subscription-mode installs migrate to the OpenCode API-key path in `Graph.init`.

---

## 6. Findings, highest risk first

### 6.1 Release signing is still the debug-key era (critical)

`docs/signing-and-key-rotation.md` states that every APK published so far is signed with a **machine debug keystore**, and that the move to a real release key via APK Signature Scheme v3 is planned but not yet done. `app/build.gradle.kts` now refuses keyless release builds, which is the right guard, but existing installs still trust the old certificate. Anyone with that private key can sign an update that installs over the app.

**Recommendation:** complete the v3 lineage rotation before the next release, publish the lineage file, pin the expected certificate in CI, and verify every artifact with `tools/check-apk-signature.sh`.

**Blocked on keys:** checked on 2026-11-17. There is no `~/phone-aos/keys/` directory and no `signing.properties`. The local machine debug key has SHA-256 `1D:D8:C0:CA:B8:D1:2C:C7:5F:AF:BF:22:95:C0:0A:3D:C5:43:DB:0E:B5:30:49:C8:C1:C1:62:9D:32:DB:B1:C7`, which does **not** match the published key `38d9d337...`. Neither the old private key nor a release key is present here, so a v3 lineage cannot be created on this machine.

### 6.2 The remote link could leak its bearer pass to an arbitrary host (fixed)

`RemoteApi.download` accepted a `pathOrUrl`; if it started with `https://`, that URL became the target and the phone
added `Authorization: Bearer <link pass>`. A compromised or prompt-injected computer could point the phone at an
attacker-controlled HTTPS URL and receive the pairing pass.

**Fix:** `remote/Net.kt` now compares the target with the linked brain's scheme, host and port and sends the
`Authorization` header only on a match. An absolute URL on another origin is fetched without credentials.

**Verification:** `RemoteLinkTest` (27 tests) passes, including
`aDownloadOnlySendsThePassToTheLinkedOrigin`, which asserts that the pass reaches the linked origin and is absent on
a second origin. Decision: `D-101` accepted.

### 6.3 Browser and code tools are not behind an approval gate (resolved by narrowing the claim)

Only specific tools enforce approval in code: connected-app actions that send or change something, machine
commands, app intents, notification replies and file uploads. `browser_eval`, `browser_click`, `browser_type`,
`run_code` and `web_fetch` do not, and cannot detect every spend or send reliably. The system prompt tells the model
to call `request_approval` first, but that is a model-level guardrail, not an enforcement boundary.

**Resolution:** the README and docs site now say exactly that: connected-app sends, machine commands and app intents
are gated; browser clicks and `run_code` are open-ended and are not automatically gated. Decision: `D-013`. A future
sandbox or egress gate can revisit the superseded `D-102`.

### 6.4 Any website could spoof web notifications (fixed)

`BrowserEngine.newWebView()` installed `__aosWeb` on every page and injected the notification shim with
`setOf("*")`. `WebNotifications.notify(title, body, host)` took the host **from JavaScript** and stored
`pkg = "web:$host"`. A malicious page could call `__aosWeb.notify(...)` directly and claim to be another site,
triggering notification-based routines or creating convincing fake notifications.

**Fix:** `WebNotifications` ignores the page-supplied host and derives it from the owning WebView's current URL. A
page without an http(s) host is ignored. The bridge signature stays compatible, so existing page shims keep working.

**Verification:** `:app:assembleDebug` succeeds. There is no dedicated unit test; the host derivation is a small,
native-side guard in `browser/BrowserEngine.kt`.

### 6.5 Keystore failure silently downgrades to plaintext secrets (high/medium)

`SettingsStore` catches any `Throwable` from `EncryptedSharedPreferences.create` and falls back to `secrets_fallback`, a normal private `SharedPreferences` file. That path holds API keys, the automation token, and the local MCP token. A broken or unavailable Keystore therefore turns “encrypted at rest” into “plaintext” with no visible warning.

**Recommendation:** fail closed for secrets, or at least show a persistent, prominent warning and refuse to save new keys until encryption is restored. Do not silently accept the weaker storage path.

### 6.6 Windows checkouts broke the Mew Host asset (fixed)

`app/src/main/assets/agent-host.mjs` used to be a git symlink (mode `120000`). On a Windows checkout with
`core.symlinks=false`, it became a **31-byte text file** containing `../../../../host/agent-host.mjs`, so
`AgentRuntime.kt` and `handoff/HandoffTools.kt` shipped a broken Mew Host.

**Fix:** removed the symlink. A `prepareAgentHostAsset` Copy task stages `host/agent-host.mjs` into
`build/generated/agentHost`, and the app's asset source set includes that directory. Linux and Windows now package
the real script from one source file.

**Verification:** `:app:assembleDebug` succeeds and the built APK contains `assets/agent-host.mjs` at 25,172 bytes,
starting with `#!/usr/bin/env node`. There is still no `gradlew.bat`; Windows uses `sh gradlew` or a locally
generated wrapper.

### 6.7 Exported recognition services lack the platform permission (medium)

`AssistRecognitionService` and the Wear `WatchRecognitionService` are exported with `android:exported="true"` but no `android.permission.BIND_RECOGNITION_SERVICE`. The platform convention for a `RecognitionService` is to require that signature permission, like the app already does for `BIND_VOICE_INTERACTION` on the assist services. Without it, any app can bind the exported forwarding recognizer.

**Recommendation:** add `android:permission="android.permission.BIND_RECOGNITION_SERVICE"` to both services.

### 6.8 CI, red tests, and the test harness (fixed, with Windows-only skips)

`.github/workflows/ci.yml` now runs on every push and pull request (`D-014`). On Linux it builds the debug phone app
and runs the translation checker, the checker's self-tests, the Mew Host tests, and the full phone unit suite. The
watch modules stay out of the default job.

The first full local run on Windows was **2520 tests, 25 failed, 8 skipped**. Every failure was triaged:

- **5 provider tests** (`ProvidersTest`, `CustomProviderSettingsTest`): `MockWebServer` advertises the machine's host
  name, which `HttpPolicy` correctly blocks as non-loopback. The tests now point the server at `127.0.0.1`.
- **3 `LocaleResolutionTest` cases**: the test still asked Robolectric for SDK 28, below the new `minSdk 34`. It now
  runs on 34.
- **2 `NodeOpenSslConfTest` cases**: `applicationInfo.nativeLibraryDir` is null under Robolectric.
  `SubscriptionRuntime` now treats that as "Node is not bundled" instead of crashing.
- **1 `RuntimePacksTest` case**: Java's `renameTo` does not replace an existing file on Windows, so a pack update
  failed. Unpacking now uses `Files.move(..., REPLACE_EXISTING)`.
- **2 translation tests**: `python3` resolved to the Microsoft Store alias. The test now finds `python3`, `py -3`
  or `python`, and forces UTF-8.
- **7 `ScreenSessionTest` cases**: stale expectations for the new end-check step and the visible-app rule, plus one
  real bug: a task borrowed from the person's own screen was not moved back when the background screen closed.
- **5 `MachinesTest` / `HandoffTest` cases**: the fixture runs a real `/bin/bash` and POSIX SFTP paths. They now skip
  on Windows with an explicit assumption and run in CI on Linux.

The run after the upstream sync: **2,580 tests, 0 failures, 0 errors, 27 skipped**, `BUILD SUCCESSFUL` in 31m 35s.
The OpenUi tests that had failed since 17 October also pass.

### 6.9 The main agent’s five-step cap is only a prompt, not code (medium)

The main prompt says “you have a hard limit of 5 action steps per turn” (`AgentRuntime.kt:497`), but the main `AgentLoop` is constructed without `maxSteps` (`AgentRuntime.kt:1066`), and `AgentLoop` defaults to `Int.MAX_VALUE`. Helpers are capped at 120, quiet runs at 40, self-check at 3; the main agent is effectively unbounded.

**Recommendation:** enforce the cap in code, or replace it with a token/time budget. Keep the prompt and the loop consistent.

### 6.10 Platform hardening after `targetSdk 34` (medium)

The app now targets Android 14 rather than Android 9. Remaining hardening items:

- `network_security_config.xml` still permits cleartext globally; provider traffic is protected by `HttpPolicy`, but browser and `web_fetch` traffic can be plain HTTP.
- `REQUEST_INSTALL_PACKAGES` is declared but no code uses it.
- 29 permissions overall, including `MANAGE_EXTERNAL_STORAGE`, `SYSTEM_ALERT_WINDOW`, and background location.
- `targetSdk 34` is not the latest platform target; revisit 36 after a dedicated compatibility pass.

**Recommendation:** remove unused `REQUEST_INSTALL_PACKAGES`, narrow cleartext where possible, and record a `targetSdk 36` evaluation separately.

### 6.11 Maintainability and hygiene

- **Monolithic files:** `AgentRuntime.kt` is 1,773 lines, `RemoteSession.kt` 1,511, `Chat.kt` 1,324, `MainActivity.kt` 1,130. The runtime especially mixes turn state, helpers, branching, prompts, subscription handling, and tools.
- **JSON-in-TEXT data model:** `ChatItem.meta` and `TurnRow.json` are queried with SQL `LIKE` in places (`ChatDao.workingHelpers`), which is brittle.
- **Destructive migrations:** `Db.kt` uses `fallbackToDestructiveMigration(true)`; fine pre-release, but a migration plan is required before 1.0.
- **Manifest size:** 135 icon aliases (270 `<activity-alias>` tags) in a 646-line manifest. Only 10 components are genuinely exported, but the alias bloat is significant and could be generated or replaced with a smaller icon mechanism.
- **Dead code and drift:** `SubKind.OPENCODE` and its `SubscriptionRuntime`/`bridge-opencode.mjs` path are unreachable (the UI routes OpenCode to the API-key flow and `native-manifest.json` has no `opencode` pack). `App.speakSystem()` is a no-op. `androidx.datastore.preferences` is declared but unused. `wear` declares zxing twice.
- **Docs drift:** `docs/index.html` metadata and visible install copy were updated to 0.16.0 / Android 14, but the Wear app is still `0.15.62` while the phone is `0.16.0`, and the remaining docs site copy has not been re-checked.
- **Licensing:** `THIRD-PARTY-NOTICES.md` now covers scrcpy, Agent-VScreen, Shizuku, and the bundled Node runtime. It still does not cover sshj, BouncyCastle, zxing, sherpa-onnx, and ML Kit, so complete the notices before publishing. Claude Code, Codex, and Antigravity are no longer redistributed.
- **Public-repo hygiene:** `PROGRESS-D.md` and several docs contain personal names, internal server paths, and owner-specific notes. Decide whether those belong in a public repository. The repository also tracks a compiled Python file at `scripts/__pycache__/test_check_translations.cpython-312-pytest-8.1.2.pyc`; add `__pycache__/` to `.gitignore` and remove it from version control when appropriate.

---

## 7. Open decisions

The following choices still need a decision or an owner action. They are tracked in `DECISIONS.md`:

- whether to complete signing rotation before the next release (`D-103`): needs the published and release keys, the
  lineage file, and a real-device update test;
- whether to remove or restore the dead OpenCode CLI subscription path (`D-106`);
- whether to evaluate `targetSdk 36` in a dedicated pass.

Resolved on 2026-11-17:

- remote-link bearer leak: fixed, `D-101` accepted;
- browser/code approval claim: narrowed to the enforced boundary, `D-013` accepted (`D-102` superseded);
- CI: Linux workflow added, `D-014` accepted (`D-009`, `D-104` superseded).

---

## 8. Recommended next actions

1. Finish the full phone unit suite and make the remaining red tests green (or record a platform-specific skip).
2. Run the watcher/card-script device probe on the CPH2707 and record both results.
3. Complete the signing-key rotation: obtain the owner's published and release keys, create the v3 lineage, sign a
   release APK, and prove an update over the installed debug-era build.
4. Remove the plaintext-secrets fallback and the unused `REQUEST_INSTALL_PACKAGES` permission.
5. Enforce the main-agent step budget and add a Room migration plan.
6. Split `AgentRuntime`, `RemoteSession`, and `MainActivity` along the seams that already exist, then align the
   remaining docs and third-party notices.

---

## 9. Document change log

- **2026-11-17:** Created from the initial static repository analysis. Added `CURRENT_STATE.md`, `AGENT.md`, and `DECISIONS.md`. Removed the untracked `scripts/__pycache__` file created by local test runs; the already-tracked `.pyc` under `scripts/__pycache__` was restored and left untouched. No source code changed.
- **2026-11-17:** Implemented the Android 14+ build floor for 0.16.0: `minSdk 34`, `targetSdk 28` kept deliberately, version `0.16.0` (`versionCode 176`), phone-only default build, Wear/watch-face modules gated behind `-PwithWear`, README/AGENT/DECISIONS updated. Verified with `:app:processDebugMainManifest` and `:app:assembleDebug` (successful). Full unit tests and release signing were not run. Subscription-runtime investigation recorded; no runtime code changed.
- **2026-11-17:** Completed the subscription-runtime spike in `docs/subscription-runtime-spike.md`. Confirmed the 0.12.12 `jniLibs` design ran on `targetSdk 36`/`minSdk 29`, measured the runtime pack sizes, and established that `run_code`/watchers also depend on Node. No runtime code changed; `D-107` awaits the user’s choice. Recorded debug-only release policy (`D-008`) and no-CI policy (`D-009`).
- **2026-11-17:** Recorded `D-011`: OpenCode Zen/Go API key is the only required model path; Claude Code, Codex, and Antigravity runtimes are out of scope.
- **2026-11-17:** Implemented `D-107`: Node 24 is bundled as `jniLibs`, `targetSdk` raised to 34, UI offers only the OpenCode path, and existing subscription-mode installs migrate to OpenCode API key in `Graph.init`. Added `buildSrc/PrepareBundledNodeTask` so the Node pack is downloaded/verified/staged without breaking the configuration cache. Updated `README.md`, `docs/index.html`, and `THIRD-PARTY-NOTICES.md` for the Node bundle and OpenCode-only path. `:app:assembleDebug` succeeded; debug APK is 169.1 MB and contains `libnode.so`, `libtx*.so`, and `cacert.pem`; merged manifest is min 34 / target 34.
- **2026-11-17:** Installed the debug APK on a CPH2707 running Android 16 (API 36, arm64). Cold launch and onboarding rendered; the power screen offers only OpenCode. Node 24.18.0 ran from `nativeLibraryDir`, the app-style `files/runtime/bin/node` symlink ran, and `fetch('https://example.com')` returned 200. No app crash in logcat. Temporary onboarding data was cleared with `run-as`.
- **2026-11-17:** After the user completed onboarding as Mew on OpenCode Go, a real prompt (“Use run_code to calculate 19*23…”) produced “Ran code: Multiply 19 by 23” and the answer 437. The bundled-Node path is proven end to end through the app. Watcher/card-script runs and full tests are still pending.
- **2026-11-17:** Rebranded the user-facing product from Agent to **Mew** (`D-012`): launcher/watch labels, watch-face names, UI strings and translations, README, docs site, and product documentation. Package ID, URLs, historical records, and technical component names remain. `:app:assembleDebug` and the watch/face debug builds succeed; `aapt` reports `application-label:'Mew'` and `Mew Data` / `Mew Orb` / `Mew Still`; translation checker still passes 54 locales. The rebrand APK installed over the existing app and kept its settings. Targeted tests: 96 run, 3 Handoff SFTP failures (environment/SFTP path, not the rebrand), the other tests passed. Full suite is still pending.
- **2026-11-17:** Finished the security, CI, and test work. `RemoteApi.download` now sends the link pass only to the linked origin (`D-101`); `WebNotifications` derives its host from the WebView URL; the README/docs claim was narrowed to the code-enforced approval boundary (`D-013`). Added `.github/workflows/ci.yml` (`D-014`). Removed the broken `agent-host.mjs` symlink and stage the real file with `prepareAgentHostAsset`; the APK contains the 25,172-byte Host. Fixed the full-suite failures: provider tests now use loopback, `LocaleResolutionTest` runs on SDK 34, `SubscriptionRuntime` tolerates a null `nativeLibraryDir`, pack unpacking replaces files on Windows, the translation tests find a working Python, the screen replay fake handles `act(click)`, and `ScreenSession.close` moves borrowed tasks back (real bug). Second full suite: **2517 tests, 0 failures, 0 errors, 26 skipped**. Debug app and test APK build. Release guard verified. Watcher/card device probe is ready but the phone is disconnected; signing still needs the owner's keys.
- **2026-11-17:** Corrected rebrand collateral found in the final build review: `User-Agent` had been renamed to `User-Mew` in the browser, providers, model catalogue, `web_fetch` and the watcher prompt; all six now send the standard header. Restored the `Agent-VScreen` attribution. `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, and `ProvidersTest` pass.
- **2026-11-17:** Renamed the Wear launch theme `Theme.Agent.Launch` to `Theme.Mew.Launch` in the resource and manifest, matching the docs and the Mew brand. `./gradlew -PwithWear :wear:assembleDebug` passes.
- **2026-11-17:** `./gradlew -PwithWear :wear:testDebugUnitTest` passes after the launch-theme rename. The phone debug APK and test APK are built; the release-key guard still refuses an unsigned release.
- **2026-11-19:** Implemented the assist conversation mode (`D-015`): the sheet reads each answer aloud and listens again, like a call, with the phone's own TTS when no cloud voice key is set up. Changed `assist/AssistCore.kt` (SPEAKING phase, quiet rounds, exit phrases), `assist/AssistActivity.kt` (the loop, read-aloud, card read-out, End/interrupt), `assist/AssistSheet.kt` (SPEAKING UI, End button), `assist/ScreenContext.kt` (`AssistPrefs.talk`, on by default), `system/Phone.kt` (`Speaker` completion callback + release), `ui/screens/AssistantSheet.kt` (settings toggle); 4 new strings in `values/strings_more.xml` + all 54 locale files; 5 new tests in `AssistTest.kt`; docs in `docs/live-voice.md` and `README.md`. Verified: translation checker 54/0, checker self-tests 13/13, targeted suite 111 tests 0 failures (Assist/AssistShots/AssistContext/AssistContextShots/LiveAssist/TranslationsCheck), `:app:assembleDebug` successful. Full phone suite re-run in progress; result appended here. No device connected, so the voice loop is not yet proven on the phone; signing and the watcher/card probe remain blocked as before.
- **2026-11-21:** Synced upstream `Past-da-king/agent` `main` at `72ab230` (28 commits, upstream 0.15.76) into the Mew work. Snapshotted the pre-sync tree on `backup/mew-pre-sync-20261121` (tag `backup-pre-sync-20261121`), rebased, and resolved the two conflicts in `app/build.gradle.kts` and `docs/live-voice.md`; the Kotlin files auto-merged with upstream. Kept Mew, Android 14+, Node bundle, OpenCode-only, and CI; raised `versionCode` to 180 (`D-016`). Fixed `TakeQueue.save` to replace the record atomically on Windows, restored the Mew voice-result markers, and verified the new dictation/live tests (106 tests, 0 failures). The full suite after the sync is **2,580 tests, 0 failures, 0 errors, 27 skipped**.
- **2026-11-21:** Implemented `D-017`: the power-button assistant uses Gemini Live by default when a Gemini key or linked computer is available, with the existing OpenCode agent doing real work; without a key it falls back to conversation mode. Added `LiveToken.configured` so the sheet does not try an unconfigured Live start, a Gemini-key hint in Settings > Default assistant, spoken progress updates at most every 12 seconds that wait for a pause (`TurnHold`), and a longer idle window while a job is out. Verified: `:app:assembleDebug`, `LiveAssistTest`/`LiveFastTest`/`LiveTruthTest`, and the full suite: **2,581 tests, 0 failures, 27 skipped**.
- **2026-11-21:** Added the signed GitHub Actions release workflow (`D-018`): phone release only, `v*` tag or manual dispatch, published-keystore secrets, signature verification, artifact upload, and GitHub Release publication as `Agent-<version>.apk`. Added the four-secret setup instructions to `docs/signing-and-key-rotation.md` and `AGENT.md`. The workflow is not run yet because `Mukul-svg/Mew` has no signing secrets and the published keystore is not on this machine.
