# Mew Working Instructions

This file is the operating guide for any agent working in this repository. It is not product documentation; it is the process the agent must follow.

## Read first

Before making any change:

1. Read `CURRENT_STATE.md` for the current snapshot, known findings, and previous verification.
2. Read `DECISIONS.md` for accepted decisions and open decisions.
3. Check `git status` and inspect any unfamiliar or uncommitted files before editing or deleting them.
4. If the requested change conflicts with an accepted decision, **stop and ask the user** before implementing it. Record the resolution in `DECISIONS.md`.

## Update `CURRENT_STATE.md` after every change

**This is mandatory after each change you make.** Do not batch unrelated changes into one entry.

At the end of every change, update `CURRENT_STATE.md` with:

- date and time;
- what changed;
- which files or modules were affected;
- why the change was made;
- how it was verified (commands run, tests passed/failed, manual checks);
- any new risk, regression, open question, or follow-up;
- whether any decision was added or changed.

If a change is abandoned or reverted, record that too. The state file is a factual snapshot, not a wish list.

## Record decisions in `DECISIONS.md`

When a decision is made:

1. Add a new entry with a stable ID (`D-001`, `D-002`, ...).
2. Record the date, status, decision, rationale, alternatives considered, and consequences.
3. If the decision supersedes an earlier one, mark the earlier entry `superseded` and link the replacement.
4. If two accepted decisions conflict, or a new request conflicts with one, ask the user which should win. Do not silently choose.
5. Record the user’s resolution and the resulting decision.

Do not use `DECISIONS.md` for ordinary implementation details that are already obvious from the code. Use it for choices that constrain future work, especially security, data handling, compatibility, release process, and architecture.

## Change workflow

1. **Inspect first.** Read the relevant code and tests. Do not guess at behavior.
2. **Check the docs.** If behavior or setup changes, update `README.md`, `docs/`, or module comments in the same change.
3. **Keep changes focused.** Do not mix unrelated refactors with a fix.
4. **Verify before claiming success.** Run the narrowest relevant tests first, then the broader suite when practical. Report exactly what was and was not run.
5. **Respect user work.** Do not overwrite, revert, or delete files you did not create without checking with the user.
6. **Update the state file last.** The final action of a change is updating `CURRENT_STATE.md`.

## Definition of done

A change is not complete until:

- the implementation is finished;
- relevant tests or checks have been run and reported;
- docs are updated when behavior or setup changed;
- any new decision is recorded in `DECISIONS.md`;
- `CURRENT_STATE.md` reflects the new state.

## Current repository constraints

- Android app: Kotlin, Jetpack Compose Material 3 Expressive, Room, WorkManager, **minSdk 34 (Android 14+), targetSdk 34**, arm64 only.
- Phone and Wear app share `applicationId` and signing key; the watch must never hold keys, models, or memory.
- The phone app is the default build. Wear modules are kept in the repository but configured only with `-PwithWear`; do not delete them.
- Node 24 is bundled as `jniLibs` for `run_code`, watchers, and card scripts (`D-107`). The build downloads the hash-pinned pack on the first build through `buildSrc`'s `prepareBundledNode` task. Do not switch back to downloaded Node or raise/lower the SDK floors without a user decision.
- Android 9-13 are no longer supported for new phone builds. Do not lower `minSdk` without a user decision.
- Signed phone releases are built by GitHub Actions with the fork's release key (`D-018`, `D-019`). The keystore lives outside the repo; never commit it or the passwords. Debug builds and old upstream-key builds cannot update to it without an uninstall.
- CI is the verification gate (`D-014`): Linux runs the debug build, translation checks, host tests, and phone unit tests on every push and pull request. Report manual verification too; do not claim a green suite without the CI result or a full local run.
- OpenCode Zen/Go API key is the only required model path (`D-011`). Do not add Claude Code, Codex, or Antigravity runtime/bundling work unless the user changes that decision.
- The power-button assistant uses Gemini Live for full-duplex voice when a Gemini key or linked computer is available, and falls back to system STT/TTS conversation mode otherwise (`D-017`). Real work still goes to the OpenCode agent; approvals stay hold-to-approve (`D-013`).
- `run_code`, watchers, and card scripts are part of the product. Do not remove Node without replacing them.
- Secrets belong in Keystore-encrypted storage. Do not add a new plaintext fallback.
- Remote links are pinned by certificate fingerprint. Do not weaken pinning or send the link pass to arbitrary hosts.
- Approvals and account rules are security boundaries. Connected-app sends, machine commands and app intents are gated in code; browser clicks and `run_code` are open-ended and are not automatically gated (`D-013`). If a change affects what can spend money, send data, or act in the user's name, treat it as a security change and ask if the correct behavior is unclear.
- Do not claim a suite is green unless CI or a full local run says so.

## Useful commands

```bash
# Translation coverage
python scripts/check-translations.py

# Translation checker self-tests
python scripts/test_check_translations.py

# Node Mew Host tests
node --test host/test/host.test.mjs

# First build downloads the pinned Node pack; later builds reuse it.
./gradlew :app:prepareBundledNode

# Android phone unit tests and screenshots
./gradlew testDebugUnitTest

# Phone release APK (requires a signing properties file outside the repo)
./gradlew :app:assembleRelease -PsigningProps=<file>

# Signed release from GitHub Actions (uses the four ANDROID_* secrets)
gh workflow run release.yml -R Mukul-svg/Mew -f tag=v0.16.0

# Wear modules, only when working on them
./gradlew -PwithWear :wear:assembleDebug
./gradlew -PwithWear testDebugUnitTest
```

On Windows, use `sh gradlew ...` or add a local `gradlew.bat`; the repository currently ships only the shell wrapper.
