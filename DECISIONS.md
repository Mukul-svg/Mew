# Decisions

This is the decision log for the repository. It records choices that constrain future work, especially security, data handling, compatibility, release process, and architecture.

## How decisions are made

1. Before changing behavior, check this file for an existing decision.
2. If a new request conflicts with an **accepted** decision, stop and ask the user. Do not implement the conflicting change.
3. If two accepted decisions conflict, ask the user which one wins.
4. Record the user’s resolution here, then update `CURRENT_STATE.md`.
5. Keep entries short but complete. Link to code or docs where useful.

## Status values

- **accepted**: in force.
- **proposed**: recommended, waiting for the user.
- **superseded**: replaced by a later decision; the replacement is linked.
- **rejected**: considered and not adopted.

## Conflict rule

A conflict exists when a requested change:

- contradicts an accepted decision;
- removes a security boundary the decision established;
- changes a compatibility or release constraint without a migration plan;
- would make two accepted decisions impossible to satisfy at the same time.

When a conflict exists, **ask the user before doing any implementation work**. Write the question and the answer here.

---

## Accepted decisions

### D-001: Keep a living state file and a decision log

- **Date:** 2026-11-17
- **Status:** accepted
- **Decision:** `CURRENT_STATE.md` is the living snapshot and must be updated after every change. `DECISIONS.md` records all decisions that constrain future work. `AGENT.md` defines the workflow.
- **Rationale:** The repository is large and has many security-sensitive, platform-specific choices. A durable state and decision trail prevents repeated discovery, accidental reversals, and conflicting changes.
- **Alternatives considered:** Rely on git history and PR descriptions; use an external issue tracker; keep notes only in `PROGRESS-D.md`.
- **Consequences:** Every change has documentation overhead. In return, future agents can see what is true now, why earlier choices were made, and when to ask before changing course.

### D-002: Treat security-sensitive findings as decisions, not drive-by fixes

- **Date:** 2026-11-17
- **Status:** accepted
- **Decision:** Changes that affect release signing, certificate pinning, bearer-token handling, secret storage, approval gates, exported components, or the `targetSdk 28` execution model require a recorded decision and explicit user confirmation when the correct behavior is ambiguous.
- **Rationale:** These areas have non-obvious tradeoffs and can silently weaken the app’s trust boundaries.
- **Alternatives considered:** Fix findings immediately as ordinary bugs.
- **Consequences:** Some fixes wait for a decision. This is intentional.

### D-003: The phone app requires Android 14+

- **Date:** 2026-11-17
- **Status:** accepted
- **Decision:** Set the phone app's `minSdk` to 34 (Android 14). New phone builds are not installable on Android 9-13. There is no legacy build in this line.
- **Rationale:** The user wants the product to be Android 14+ only. A 2026 app can rely on modern platform behavior and stop spending effort on older devices.
- **Alternatives considered:** Keep `minSdk 28` and gate features by SDK; maintain a separate legacy build; support Android 10-13 only.
- **Consequences:** Devices on Android 9-13 cannot install 0.16.0 or later. The README and release notes must state the new floor. Existing installs on Android 14+ can upgrade normally, subject to the signing-key decision.

### D-004: Keep `targetSdk 28` while the subscription-runtime execution model is investigated

- **Date:** 2026-11-17
- **Status:** superseded by `D-107`
- **Decision:** Keep `targetSdk = 28` for now, even though `minSdk = 34`. Do not change the runtime download/execution code until the investigation is complete and the user chooses a migration path.
- **Rationale:** Android 10+ blocks apps targeting 29+ from executing downloaded binaries in app storage. The Claude Code, ChatGPT/Codex, and Antigravity subscription paths depend on that behavior. The user chose to investigate before committing to bundling runtimes, disabling subscriptions, or finding another execution mechanism.
- **Conflict note:** The Android 14+ request was surfaced as a conflict with the current `targetSdk 28` runtime model during the 2026-11-17 interview. The user chose to keep `targetSdk 28` and investigate first.
- **Alternatives considered:** Raise `targetSdk` immediately and disable subscription mode; bundle the runtime packs as `jniLibs` in the APK; drop subscription mode.
- **Consequences:** The app kept legacy target behavior and was not Play Store eligible. This was an interim decision only. It was superseded on 2026-11-17 by `D-107` (Node-only bundle, `targetSdk 34`); the merged manifest now reports min 34 / target 34.

### D-005: Default builds are phone-only; Wear code is retained

- **Date:** 2026-11-17
- **Status:** accepted
- **Decision:** The default Gradle build configures `:app` and `:wearproto` only. `:wear` and the three watch-face modules remain in the repository but are included only with `-PwithWear`. Watch tests are not part of default verification.
- **Rationale:** The user wants Android phone APKs only, but explicitly does not want the Wear OS or watch-face code deleted.
- **Alternatives considered:** Keep all modules in the default build; add a separate aggregate task; remove watch modules from `settings.gradle.kts` entirely.
- **Consequences:** A normal `./gradlew assembleDebug` or `:app:assembleRelease` is phone-only. Working on the watch requires `-PwithWear`. The code is retained but receives no default test coverage.

### D-006: The Android APK remains `arm64-v8a` only

- **Date:** 2026-11-17
- **Status:** accepted
- **Decision:** Keep `ndk.abiFilters = ["arm64-v8a"]`; do not add x86_64 or 32-bit ARM in this change.
- **Rationale:** The wake-word engine and downloaded runtime packs are arm64-only. Adding another ABI would require runtime and test work outside the current scope.
- **Alternatives considered:** Add x86_64 for emulators; add `armeabi-v7a`; ship universal APKs.
- **Consequences:** The APK does not install on x86_64 emulators or 32-bit Android devices. Use a physical arm64 device or an arm64 emulator for instrumentation tests.

### D-007: Version 0.16.0 marks the Android 14+ compatibility break

- **Date:** 2026-11-17
- **Status:** accepted
- **Decision:** Set `versionName = "0.16.0"` (the initial versionCode was 176; `D-016` raises it to 180 after the upstream 0.15.76 sync). Release notes must state that Android 14+ is required and Android 9-13 are no longer supported.
- **Rationale:** The install floor is a user-visible compatibility break and should not ship under the previous version number.
- **Alternatives considered:** Keep 0.15.72; use a build suffix; bump to 0.17.0.
- **Consequences:** The next release is a minor version bump with a breaking platform requirement.

### D-008: Debug builds only for now

- **Date:** 2026-11-17
- **Status:** superseded by `D-018`
- **Decision:** Do not produce or publish a signed release until the signing rotation is decided. Verify with debug APKs.
- **Rationale:** The published key is a machine debug certificate and the v3 lineage rotation is not done. Shipping another release under the current key increases the damage if the key is lost or compromised.
- **Alternatives considered:** Use an existing release signing file; keep shipping signed releases with the current key.
- **Consequences:** No release artifact is produced in this phase. The 0.16.0 version number is reserved for the eventual release.

### D-009: No CI yet

- **Date:** 2026-11-17
- **Status:** superseded by `D-014`
- **Decision:** Keep verification local for now. Do not add a CI workflow until the runtime direction and signing path are settled.
- **Rationale:** The build and test matrix is still moving, and the runtime work may change the verification commands.
- **Alternatives considered:** Add Linux CI immediately; add Linux and Windows CI.
- **Consequences:** Every verification must be run and reported manually. This is recorded as a known gap.

### D-010: Runtime spike before runtime code changes

- **Date:** 2026-11-17
- **Status:** accepted
- **Decision:** Investigate the runtime execution options before modifying `SubscriptionRuntime`, `RuntimePacks`, `run_code`, or watchers. Record the result in `docs/subscription-runtime-spike.md`.
- **Rationale:** The choice affects APK size, `targetSdk`, subscription support, and core tools. Changing code before the decision risks rework.
- **Alternatives considered:** Choose the bundle path immediately; raise `targetSdk` and disable subscriptions immediately.
- **Consequences:** Runtime code remains on the current download model until D-107 is resolved.

### D-011: OpenCode Zen/Go API key is the only required model path

- **Date:** 2026-11-17
- **Status:** accepted
- **Decision:** Use OpenCode Zen/Go through the existing API-key provider path (`Provider.OPENCODE_GO` / `Provider.OPENCODE_ZEN`). Claude Code, Codex, and Antigravity subscription runtimes are out of product scope. Their code may remain, but do not build, bundle, or spend effort supporting those runtimes.
- **Rationale:** The user uses only OpenCode. OpenCode API-key turns are plain HTTPS calls and need no phone-local CLI runtime.
- **Alternatives considered:** Keep all subscription runtimes; keep one of Claude/Codex/Antigravity; build the dormant OpenCode CLI subscription path.
- **Consequences:** The 341 MB all-runtime bundle problem disappears. The only remaining runtime dependency is Node for `run_code`, watchers, and card scripts. The dead `SubKind.OPENCODE` CLI path should eventually be removed or left dormant; do not present it as a supported subscription.

---

### D-107: Bundle Node only and raise `targetSdk` to 34

- **Date:** 2026-11-17
- **Status:** accepted
- **Decision:** Bundle Node 24 only as `jniLibs`, raise `targetSdk` to 34, and keep `minSdk` 34. No Claude/Codex/Antigravity packs. `run_code`, watchers, and card scripts continue to use the bundled Node. The download-on-first-use model remains in the code as a dormant fallback but is not the shipped path.
- **Rationale:** The user uses OpenCode Zen/Go via API key only. Node is the only remaining runtime dependency, and bundling it removes the `targetSdk 28` workaround while preserving the code tools. The 0.12.12 line already proved `jniLibs` execution on `targetSdk 36`.
- **Alternatives considered:** Keep `targetSdk 28` and download Node; remove Node and lose `run_code`/watchers/cards; bundle all CLIs; use Shizuku.
- **Consequences:** The release APK is about 83 MB and installed size about 188 MB. The build downloads the hash-pinned Node pack on first build and stages it under `build/generated/bundledNode`. `targetSdk 34` is now the shipped target. The UI offers only the OpenCode path; existing subscription-mode installs migrate to OpenCode API key in `Graph.init`.
- **Supersedes:** D-004, D-105.
- **Related:** D-011, D-010.
- **Spike result:** the 0.12.12 line proved the same `jniLibs` execution model on `targetSdk 36`/`minSdk 29`. Node is 47.4 MB compressed / 152.4 MB unpacked. `run_code` and watchers also need Node, so removing Node would require replacing core tools.
- **Verification:** debug APK installed on a CPH2707 running Android 16 (API 36, arm64). Node 24.18.0 ran from `nativeLibraryDir`, the `files/runtime/bin/node` symlink ran, and `fetch` returned HTTP 200. A real OpenCode Go turn using `run_code` returned 437. Watcher/card-script runs and full tests are still pending.

### D-012: The product name is Mew

- **Date:** 2026-11-17
- **Status:** accepted
- **Decision:** Use **Mew** as the user-facing app name everywhere: phone launcher label, watch app and watch-face names, UI strings, README, docs site, and product documentation. Keep the package ID `com.past9.phoneaos`, URL paths, release artifact names, historical records, certificate names, and technical component names unchanged unless a separate migration is approved.
- **Rationale:** The user wants the app branded as Mew.
- **Alternatives considered:** Keep Agent as the product name and use Mew only as the personal agent name; rename the package and URLs as well.
- **Consequences:** Launcher and watch labels show Mew; user-facing strings and docs say Mew. Generic lowercase “agent” remains where it describes the role rather than the product.
- **Verification:** `aapt` on the installed base APK reports `application-label:'Mew'`; the watch and three watch-face debug APKs report Mew labels; the translation checker passes 54 locales; existing app settings survived the reinstall.

### D-013: Approvals cover known sends, not open-ended browser and code actions

- **Date:** 2026-11-17
- **Status:** accepted
- **Decision:** Keep code-enforced approvals for connected-app actions that send or change something, machine commands, app intents, notification replies, and file uploads. Do not claim that arbitrary browser clicks, form submissions inside a page, or `run_code` are automatically gated: they are open-ended and cannot be detected reliably. The README, docs site, and Risks section now say exactly that.
- **Rationale:** The previous wording ("anything that spends money or sends something stops at an approve button") was stronger than the code. A false security promise is worse than a precise one. The agent is still instructed to call `request_approval` before spending, sending or deleting, but that is a model-level guardrail, not an enforcement boundary.
- **Alternatives considered:** D-102 (gate browser POST/XHR, sensitive clicks, `run_code` and `web_fetch` in code); sandbox `run_code` without network; require approval on every `run_code`.
- **Consequences:** Users must watch open-ended browsing and code runs. Connected-app, machine and intent actions remain enforced in code. A future sandbox or network-egress gate can revisit this decision.
- **Related:** D-102, D-101.

### D-014: Linux CI is the required verification gate

- **Date:** 2026-11-17
- **Status:** accepted
- **Decision:** Add `.github/workflows/ci.yml`. On every push to `main` and every pull request, GitHub-hosted Ubuntu runs: the debug phone build, the translation checker, the checker's self-tests, the Mew Host tests, and the full phone unit suite. All are blocking. The watch modules stay out of the default job and are built on demand with `-PwithWear`.
- **Rationale:** The repository had no automated verification, and the Windows checkout broke the Host asset symlink. Linux matches the symlink and shell-script assumptions and is the cheapest reliable runner.
- **Alternatives considered:** Local-only verification (D-009); a Windows job first (D-104); watch builds in CI.
- **Consequences:** A red unit suite blocks merges and must be fixed, not skipped. The build downloads the pinned Node pack on each cold cache. Windows-specific paths are still verified locally; a Windows job can be added later if it earns its cost.
- **Related:** D-009, D-104, D-008.

### D-101: Fix the remote-link bearer leak before other feature work

- **Date:** 2026-11-17
- **Status:** accepted
- **Decision:** Before adding features, restrict `RemoteApi.download` so the link pass is only sent to a paired brain’s origin, and reject or fetch without credentials for arbitrary absolute URLs.
- **Rationale:** The current code can send the pairing pass to any `https://` URL supplied by the computer or by prompt-injected content.
- **Alternatives considered:** Accept the risk because the computer is user-owned; add a warning only.
- **Consequences:** Some third-party media URLs may need a different fetch path or no auth.
- **Verification:** `RemoteLinkTest.aDownloadOnlySendsThePassToTheLinkedOrigin` passes: the pass is sent to the linked origin, and an absolute URL on another origin is fetched without `Authorization`.

### D-015: The assistant holds a conversation with the phone's own voice when no cloud voice is set up

- **Date:** 2026-11-19
- **Status:** accepted
- **Decision:** The assist sheet gets a conversation mode ("Keep the conversation going", on by default): it reads each answer aloud and listens again, like a call, ending on goodbye phrases, the End button, leaving the sheet, or two quiet listening rounds. The voice is the configured cloud voice (Gemini/OpenAI/ElevenLabs) or, with none set up, the phone's own TextToSpeech. Gemini Live (`D-011`'s optional voice path) remains the richer mode when a Gemini key exists; a failed Live start falls back to this conversation.
- **Rationale:** The owner uses only the OpenCode API key (`D-011`) and asked for two-way voice from the power-button assistant: hear the answer and keep talking. Before, the sheet was one-request and stayed silent without a cloud voice key, and Live voice needs a Gemini key the owner does not have. System STT (already used by the sheet) plus system TTS give a call-like loop with no new key, account, or network dependency.
- **Alternatives considered:** require a free Gemini key and use the existing Live mode; keep the sheet one-request and only add read-aloud; cloud TTS only.
- **Consequences:** The phone's robotic voice is now heard by default in the assistant (the old "never the robotic phone voice" rule applied to chat read-aloud, which is unchanged: `App.speak` still stays quiet without a cloud voice). The microphone is closed while the voice speaks; interrupt is a tap. Approvals are read aloud but still held on the screen, never answered by words (`D-013` unchanged). Exit phrases are English; other languages use End. The `speakReplies` setting and `App.speakSystem()` remain dead code, untouched.
- **Related:** D-011, D-013.

### D-016: Version code 180 after syncing upstream 0.15.76

- **Date:** 2026-11-21
- **Status:** accepted
- **Decision:** Keep `versionName = "0.16.0"` and raise `versionCode` to **180** after integrating upstream `main` at `72ab230` (upstream released `0.15.76` with versionCode 179). Keep `minSdk 34`, `targetSdk 34`, the Mew name, the OpenCode-only model path, the Node bundle, and the CI workflow.
- **Rationale:** Android refuses to install an APK with a lower versionCode than the installed one. Keeping 176 would make the Mew 0.16.0 build unable to update an upstream 0.15.76 install. The product version should stay 0.16.0, so only the code is raised.
- **Alternatives considered:** Keep 176; adopt upstream `0.15.76`/179; bump the product version to 0.17.0.
- **Consequences:** `D-007`'s versionCode is superseded in part by this decision; its versionName and Android 14+ floor remain. Future releases continue from 180 upward.
- **Verification:** `app/build.gradle.kts` reports `versionCode = 180`, `versionName = "0.16.0"`; the built APK is checked with `aapt`.
- **Related:** D-007, D-003, D-011, D-012.

### D-017: The power-button assistant uses Gemini Live for voice and OpenCode for work

- **Date:** 2026-11-21
- **Status:** accepted
- **Decision:** When a Gemini key is configured on the phone, or a linked computer can mint a Gemini Live token, the assistant opened by the power-button shortcut uses Gemini Live as its voice layer. Real work is handed to the same OpenCode agent as the typed chat; the Live session stays open while a job runs, speaks brief progress updates only after the user and the voice are quiet, and speaks the final result, questions and approvals as they arrive. If neither a key nor a linked computer is available, it falls back to the existing system-STT/TTS conversation mode. Keep the slide-up sheet and floating bubble; end on goodbye or End, with a 5-minute idle guard when the sheet is open and 10 minutes when minimised. Approvals remain hold-to-approve and are never answered by voice (`D-013`).
- **Rationale:** The user wants a live, call-like personal assistant that can work in the background. Gemini Live provides full-duplex audio, barge-in and tool calls; the existing `LiveAssistant`/`hand_to_agent` path already connects it to the OpenCode agent, approvals and chat. No extra orchestration server is needed.
- **Alternatives considered:** System STT/TTS only (half-duplex); OpenAI Realtime (another provider and adapter); OpenCode's experimental server-brokered voice API (experimental, OpenAI/xAI-oriented, needs a server); Pipecat/LiveKit (duplicates the existing agent integration).
- **Consequences:** Full-duplex voice needs a Gemini key or a linked computer with one; without it the assistant is half-duplex. Microphone audio goes to Google only during a Live call. Progress updates are throttled to one every 12 seconds and wait for a natural pause; they are not results. A Live session still ends after a long silence or on End/goodbye; a job nobody answers for ten minutes no longer holds the line, though its result is notified.
- **Related:** D-011, D-013, D-015, D-016.

### D-018: GitHub Actions builds signed phone releases

- **Date:** 2026-11-21
- **Status:** accepted (signing-key clause superseded by `D-019`)
- **Decision:** Add `.github/workflows/release.yml`. It runs on a `v*` tag or manual dispatch, builds the **phone** release APK only, signs it with the release keystore supplied as four GitHub Actions secrets (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`), verifies it with `tools/check-apk-signature.sh`, uploads the APK and its SHA-256 as workflow artifacts, and creates or updates the GitHub Release for the tag with `Agent-<version>.apk`.
- **Rationale:** The user wants tagged release APKs built and published from GitHub Actions. The verifier refuses a wrong key, so a bad APK cannot be published silently.
- **Alternatives considered:** Generate a new release key (chosen later, `D-019`); build unsigned artifacts (not installable); keep local debug-only releases (`D-008`).
- **Consequences:** The workflow is phone-only; Wear releases can be added later with `-PwithWear`. The four secrets are now set on `Mukul-svg/Mew`; the first run uses the `D-019` key. It does not perform a v3 rotation.
- **Update (2026-11-21):** the published keystore was unavailable and the owner confirmed there were only debug installs, so `D-019` replaces the key choice with a clean-break release key. The workflow itself is unchanged.
- **Related:** D-008, D-019, D-103, D-016.

### D-019: Clean-break signing with a new release key

- **Date:** 2026-11-21
- **Status:** accepted
- **Decision:** Because the old published keystore (`38d9d3377b0ae2229a0803a1ff84eb7a7d35c2db80334772c4b8072f27b0959a`) is not available and the owner has only debug installs, generate a new 4096-bit RSA release key at `~/phone-aos/keys/release.jks` (alias `agent`, SHA-256 `bb7c20567a9df530964119726aad4cc999c1fbaf7fd8e6bcbc01f71a78fa6985`), keep it and its `signing.properties` outside the repository, and supply it to the GitHub Actions release workflow through the four `ANDROID_*` secrets. Update `tools/check-apk-signature.sh` and the build guard to expect the new certificate.
- **Rationale:** The user needs a signed release and has no published-signature install to preserve. Waiting for the upstream private key blocks the release indefinitely. A clean break is honest and verifiable: the new key is generated here, stored outside the repo, and checked into CI as a fingerprint.
- **Alternatives considered:** Wait for the upstream published key (could not proceed); unsigned artifact (not installable); keep debug builds only (`D-008`); reuse the local machine debug key (fragile and not a release key).
- **Consequences:** APKs signed with the old published key cannot update to this build, and this build cannot update them; any published install must be uninstalled. This fork had only debug installs. Debug builds also require an uninstall before installing the release. The keystore and password must be backed up; losing them ends future updates. `D-103`'s v3 lineage from the old key is impossible; future rotations start from the new key.
- **Related:** D-018, D-103, D-008.

## Superseded decisions

### D-105: Write an exit plan for `targetSdk 28`

- **Date:** 2026-11-17
- **Status:** superseded by `D-107`
- **Decision:** Document how the app will move off `targetSdk 28` while preserving the subscription-runtime path, before a future Android release blocks installation.
- **Rationale:** `targetSdk 28` is deliberate but is a growing install and security risk.
- **Alternatives considered:** Stay on 28 indefinitely; drop the downloadable-runtime path.
- **Consequences:** May require a different execution model or a separate build flavor.

### D-102: Enforce browser/code approvals in code

- **Date:** 2026-11-17
- **Status:** superseded by `D-013`
- **Decision:** Gate browser form submissions, POST/XHR, sensitive clicks, `run_code`, and `web_fetch` egress in code rather than relying on the model to call `request_approval`.
- **Rationale:** The README currently claims stronger protection than the code enforces.
- **Alternatives considered:** Narrow the README claim and accept model-level guardrails; keep the current behavior.
- **Consequences:** More friction for legitimate browsing and code tasks. Approval UX needs design work.

### D-104: Add CI on Linux, then Windows

- **Date:** 2026-11-17
- **Status:** superseded by `D-014`
- **Decision:** Add a GitHub Actions workflow that builds and runs the unit tests, translation checks, and host tests on Linux; add a Windows job for the Windows-specific paths after the asset and test fixes.
- **Rationale:** There is no CI, two tests are known red, and the Windows checkout currently breaks the Mew Host asset.
- **Alternatives considered:** Keep relying on manual runs and `PROGRESS-D.md`.
- **Consequences:** CI time and maintenance. Windows runners are slower and may need extra setup.

## Proposed decisions (waiting for the user)

### D-103: Complete signing-key rotation before the next release

- **Date:** 2026-11-17
- **Status:** superseded by `D-019` (the old private key is unavailable)
- **Decision:** Perform the APK Signature Scheme v3 lineage rotation from the published debug-era key to the release key, publish the lineage, and pin the expected certificate in CI.
- **Update (2026-11-21):** the old published private key is not available, so no lineage from it can be created. `D-019` makes a clean break to a new release key; future rotations start from that key.
- **Rationale:** Existing installs currently trust a machine debug certificate. A wrong or lost key can break updates or let an attacker sign a valid update.
- **Alternatives considered:** Keep the published key and rely on the new build guard; ask every user to reinstall.
- **Consequences:** Requires the owner’s keys and a real-device update test. The lineage file must be backed up.
- **Blocker (2026-11-17):** this machine has neither the published private key nor a release key: there is no `~/phone-aos/keys/`, and the local debug key fingerprint starts `1DD8C0CA...`, not the published `38D9D337...`. The rotation needs the owner to supply both keystores (or a secure copy) and the passwords outside the repo.

### D-106: Remove or restore the dead OpenCode subscription path

- **Date:** 2026-11-17
- **Status:** proposed
- **Decision:** Either remove `SubKind.OPENCODE`, its `SubscriptionRuntime` branch, and `bridge-opencode.mjs`, or add an `opencode` pack to `native-manifest.json` and route the UI to it.
- **Rationale:** The UI currently sends OpenCode to the API-key flow, but the subscription code and bridge remain, and no runtime pack exists. The code is confusing and untested in practice.
- **Alternatives considered:** Leave it as dormant code.
- **Consequences:** Removing it simplifies the runtime matrix; restoring it adds another downloadable pack and support burden.


---

## Rejected decisions

None yet.

---

## Decision template

```markdown
### D-XXX: Short title

- **Date:** YYYY-MM-DD
- **Status:** proposed | accepted | superseded | rejected
- **Decision:** What is being decided, in one or two sentences.
- **Rationale:** Why this choice.
- **Alternatives considered:** What else was on the table.
- **Consequences:** What becomes easier, harder, or newly required.
- **Supersedes / superseded by:** D-XXX, if applicable.
```
