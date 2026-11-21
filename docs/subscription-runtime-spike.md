# Subscription runtime spike: Android 14+, targetSdk, and downloaded runtimes

**Date:** 2026-11-17
**Status:** resolved; `D-107` accepted (Node-only bundle, `targetSdk 34`)
**Scope:** can the phone app stay Android 14+ while raising `targetSdk` and keeping phone-local subscription
runtimes (Claude Code, ChatGPT/Codex, Antigravity) and `run_code`/watchers working?

> **Outcome (2026-11-17):** the app uses OpenCode Zen/Go API keys only (`D-011`). Node 24 is bundled as
> `jniLibs` so `run_code`, watchers, and card scripts keep working; Claude/Codex/Antigravity are not bundled.
> `minSdk` and `targetSdk` are both 34. The build stages the hash-pinned Node pack through
> `buildSrc/PrepareBundledNodeTask`. Debug APK: 169.1 MB (release estimate ~83 MB). See `CURRENT_STATE.md`.

No runtime code was changed during the initial spike; the outcome was implemented separately.

---

## 1. The constraint

The current phone app:

- downloads runtime zips into `filesDir/runtime` (`runtime/RuntimePacks.kt`);
- executes `filesDir/runtime/bin/node` with `ProcessBuilder` (`runtime/SubscriptionRuntime.kt`);
- runs `run_code` and watcher scripts through that same Node binary (`tools/CodeTools.kt`, `triggers/Watchers.kt`);
- keeps `targetSdk = 28` because Android 10+ (API 29+) blocks apps targeting 29+ from executing files the app
  wrote into its own storage.

So `targetSdk 28` is not cosmetic. It is load-bearing for **both** subscription turns and the Node-backed code tools.
Raising `targetSdk` without a replacement breaks `run_code`, watcher scripts, and all subscription paths.

`minSdk 34` with `targetSdk 28` builds successfully. Gradle warns that `minSdkVersion` is greater than
`targetSdkVersion`, but the merged manifest is valid and `:app:assembleDebug` succeeds. That is the current interim
state under `D-004`.

---

## 2. Historical evidence: the bundled-runtime design already worked on a modern target

The repository previously shipped the same runtimes inside the APK as native libraries.

At commit `7dca681^` (the 0.12.12 line), `app/build.gradle.kts` had:

```kotlin
minSdk = 29
targetSdk = 36
...
jniLibs { useLegacyPackaging = true }
```

`SubscriptionRuntime` used:

```kotlin
private val libDir get() = File(context.applicationInfo.nativeLibraryDir)
val node: File get() = File(libDir, "libnode.so")
```

and `linkBins()` created symlinks from `filesDir/runtime/bin/<name>` to the `lib<name>.so` files in
`nativeLibraryDir`. `LD_LIBRARY_PATH` pointed at `nativeLibraryDir`.

`tools/build-runtime-pack.sh` produced:

- `libnode.so` (Termux Node 24);
- `libclaude.so` and `libldmusl.so` (Claude Code + musl loader);
- `libcodex.so` and `libcodex-modehost.so` (Codex + code-mode host);
- `librg.so` (ripgrep);
- `assets/runtime/node_modules.zip`.

Commit `7dca681` then deliberately moved to the thin-app model (downloads, `targetSdk 28`) in 0.13.0. The older
design is therefore not theoretical: it shipped on `targetSdk 36` and `minSdk 29`.

---

## 3. Size reality

From `app/src/main/assets/runtime/native-manifest.json` (decimal MB):

| Pack | Compressed in APK | Unpacked on device |
|---|---:|---:|
| Node | 47.4 MB | 152.4 MB |
| Claude Code | 108.6 MB | 244.6 MB |
| Codex | 127.3 MB | 315.9 MB |
| Antigravity | 58.0 MB | 190.1 MB |
| **All four** | **341.3 MB** | **903.0 MB** |

The release base APK was about 35.6 MB after shrinking (`PROGRESS-D.md`, 0.14.1). Estimated release APKs:

| Bundle | Estimated APK | Estimated installed |
|---|---:|---:|
| Base, no runtimes | ~35.6 MB | ~35.6 MB |
| Node only | ~83.0 MB | ~188.0 MB |
| Node + Claude | ~191.6 MB | ~432.6 MB |
| Node + Codex | ~210.3 MB | ~503.9 MB |
| Node + Antigravity | ~141.0 MB | ~378.1 MB |
| Node + all three CLIs | ~376.9 MB | ~938.6 MB |

These are estimates from the pack manifest, not built artifacts. The installed size is larger because the APK's
compressed native libraries are extracted to `nativeLibraryDir` at install.

---

## 4. Options

### Option A: keep `targetSdk 28` and the download model

- **APK:** ~36 MB base; runtimes download later.
- **Subscriptions:** work.
- **`run_code` / watchers:** work.
- **Platform posture:** legacy target behavior; growing future-install risk.
- **Effort:** zero (current state).
- **Verdict:** interim only. This is `D-004`.

### Option B: bundle Node + all CLIs, raise `targetSdk`

- **APK:** ~377 MB.
- **Installed:** ~939 MB before user data.
- **Subscriptions:** all supported.
- **`run_code` / watchers:** work.
- **Platform posture:** modern target.
- **Effort:** high: revive the jniLibs build, adapt `SubscriptionRuntime`, build/sign/publish a very large APK.
- **Verdict:** the only known way to keep every subscription on the phone with a modern target.

### Option C: bundle Node only, raise `targetSdk`, ship API-key mode

- **APK:** ~83 MB.
- **Installed:** ~188 MB.
- **Subscriptions:** not on the phone; the paired computer can run them in remote mode.
- **`run_code` / watchers:** work.
- **Effort:** medium: jniLibs for Node only; hide/disable subscription UI while keeping the code.
- **Verdict:** the best compromise if subscription mode is optional.

### Option D: bundle Node + one CLI, raise `targetSdk`

- **APK:** ~141 MB (Antigravity), ~192 MB (Claude), or ~210 MB (Codex).
- **Installed:** ~378-504 MB.
- **Subscriptions:** one family locally; the others via API key or remote mode.
- **`run_code` / watchers:** work.
- **Effort:** medium-high; needs a product decision about which CLI(s) ship and a build flavor.
- **Verdict:** a good fit if one subscription family matters more than the others.

### Option E: raise `targetSdk` and remove Node entirely

- **APK:** ~36 MB.
- **Installed:** ~36 MB.
- **Subscriptions:** none locally.
- **`run_code` / watchers:** **broken**. They are core tools, not just subscription plumbing.
- **Effort:** medium: remove or reimplement `run_code` and watcher execution.
- **Verdict:** not "API keys only" as a drop-in; it removes core agent capability. Do not choose without a replacement.

### Option F: execute downloaded binaries through Shizuku or root

- **APK:** ~36 MB.
- **Subscriptions:** work only while Shizuku is running and the user has set it up.
- **Security:** hands executable files to a shell-uid bridge; requires a privileged helper; not universal.
- **Effort:** high and hard to support.
- **Verdict:** optional fallback for users who already run Shizuku, not a primary path.

### Option G: run the subscription CLIs on the paired computer

- **APK:** ~36 MB.
- **Subscriptions:** work through remote mode; the phone is the window.
- **Phone-local subscriptions:** not supported.
- **Effort:** low; remote mode already exists.
- **Verdict:** a legitimate alternative for users with a PC/server, but not a phone-local feature.

---

## 5. Implementation notes for Option B/C/D

The shortest path is to revive the 0.12.12 design rather than invent a new one:

1. Run `tools/build-runtime-pack.sh` on Linux or WSL to produce `jniLibs` plus `assets/runtime/node_modules.zip`.
2. Keep `jniLibs { useLegacyPackaging = true }` so Android extracts the `.so` files into `nativeLibraryDir`.
3. Restore a runtime source abstraction:
   - bundled: `nativeLibraryDir/libnode.so`, `libclaude.so`, etc.;
   - thin: current downloaded `filesDir/runtime/bin/*`.
4. In bundled mode, re-create `filesDir/runtime/bin/<name>` symlinks to `nativeLibraryDir` on every launch/update,
   because the native library path changes after an app update.
5. Set `LD_LIBRARY_PATH` to `nativeLibraryDir` in bundled mode; keep the downloaded `lib` directory in thin mode.
6. Preserve the Claude musl loader handling (`libldmusl.so` and the `ld-musl-aarch64.so.1` symlink) and the Codex
   `libcodex-modehost.so` name patch.
7. Unpack JS assets (`node_modules.zip`, bridge scripts, certs) to `filesDir/runtime` as before.
8. Raise `targetSdk` to 34 or 36 and verify on a physical Android 14+ device.

Risks and details:

- `CLAUDE_INTERP` is hardcoded to `/data/user/0/com.past9.phoneaos/...`; an `applicationIdSuffix` build
  (`-PappIdSuffix=.linktest`) will not work. Make it dynamic from `context.packageName` if test installs matter.
- The Claude Code binary is a musl executable; the loader and interpreter path are fragile and must be re-tested.
- Codex spawns `codex-code-mode-host`; the built binary must be shipped and the lookup patched as the script does.
- Bundled binaries are not updated by the download-pack mechanism. Shipping a new CLI requires a new APK.
- The runtime-pack build is Linux/WSL-only and downloads Termux/Alpine/npm artifacts. It needs a reproducible
  build and license review before release.
- APK signing rotation is still unresolved. A 377 MB `full` APK that cannot update is worse than a broken runtime.
- The `THIRD-PARTY-NOTICES.md` file does not currently cover the redistributed runtimes.

---

## 6. Recommendation

- **If phone-local subscriptions are a must:** choose **Option D or B**, and raise `targetSdk`. A separate `full`
  flavor is worth considering so API-key users can keep the ~36 MB APK.
- **If subscriptions are optional:** choose **Option C**. It keeps `run_code` and watchers working, keeps the APK
  around 83 MB, and leaves Claude/Codex/Antigravity to API keys or a paired computer.
- **Do not choose Option E** without replacing `run_code` and watchers; removing Node breaks core agent features.
- **Keep Option A only as the current interim.** It is the least work but retains legacy target behavior.
- **Treat Option F as an optional Shizuku fallback**, not the main design.

This is a product decision, not a build-config detail. It is tracked as `D-107`.

---

## 7. Verification status

- The historical `minSdk 34` + `targetSdk 28` interim built successfully.
- The implemented Node-only bundle builds successfully with `minSdk 34` / `targetSdk 34`; the debug APK contains
  `libnode.so`, `libtx*.so`, and `cacert.pem`.
- Installed on a physical CPH2707 running Android 16 (API 36, arm64). Node 24.18.0 ran from `nativeLibraryDir`;
  the app-style `files/runtime/bin/node` symlink ran; a small ESM script printed `js-ok 42` and fetched
  `https://example.com` with HTTP 200.
- A real OpenCode Go turn used `run_code` and returned the right result (19 x 23 = 437), proving the full path
  through the app rather than a shell smoke test.
- Watchers and card scripts share that same Node path; a device probe for both is in
  `app/src/androidTest/.../RuntimeProbeDeviceTest.kt` and is run on a phone, not in CI. It has not run yet: the
  phone disconnected from `adb` before the test APK could be installed.
