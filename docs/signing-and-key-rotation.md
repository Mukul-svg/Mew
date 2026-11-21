# Signing, and how to rotate the key without breaking anyone's install

Written 3 Nov 2026, after a release went out signed with the wrong key.

## 2026-11-21: clean break to a new release key

The old published keystore (`38d9d3377b0ae2229a0803a1ff84eb7a7d35c2db80334772c4b8072f27b0959a`) was not available
to this fork, and the owner confirmed there were only debug installs so far. The project therefore made a clean break:

- **New key:** `~/phone-aos/keys/release.jks` (on this machine
  `C:\Users\mukul\phone-aos\keys\release.jks`), alias `agent`, 4096-bit RSA, SHA-256
  `bb7c20567a9df530964119726aad4cc999c1fbaf7fd8e6bcbc01f71a78fa6985`.
- `tools/check-apk-signature.sh` and the build guard expect this certificate.
- GitHub Actions uses the four `ANDROID_*` secrets; the key is never in the repository.
- This is **not** a v3 rotation: an APK signed with the old published key cannot install over this one, and vice
  versa. Any existing published install must be uninstalled first; this fork had only debug installs.
- **Back up `release.jks` and `signing.properties` somewhere safe.** Losing the key ends future updates.

Debug builds still use the machine's `~/.android/debug.keystore`; they also cannot be updated to the release build
without uninstalling. The old rotation plan below is kept for history; it cannot run without the old private key.

## Where we are, plainly

Every APK this project has published is signed with **the debug keystore of the machine that built it**
(`~/.android/debug.keystore`, certificate `CN=Android Debug`, SHA-256
`38d9d3377b0ae2229a0803a1ff84eb7a7d35c2db80334772c4b8072f27b0959a`). That was never a decision. It is what happens when
`assembleRelease` runs on a machine that has no release key: Android signs a release APK with the debug key rather than
leave it unsigned, so the file works, installs, and quietly becomes the identity every installed copy trusts.

It is also fragile in a way that has already bitten us once:

- A new machine, a fresh Android Studio, or a deleted `~/.android` folder **regenerates that key with a different
  certificate**. Every later release then fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` and each user has to uninstall,
  losing their chats, memory, tasks and connections.
- On 3 Nov 2026 a build machine happened to have a proper release keystore (`~/phone-aos/keys/release.jks`, certificate
  `CN=Agent (open source phone agent)`, SHA-256 `2564b86434c75ba4f5dd6862bebc2d736c0375f658583bfa5fc5baa5df91ff6d`). The
  0.15.27 APK was signed with it, correctly and honestly, and it was still the wrong key: it could not install over
  0.15.26. That file was pulled and replaced, and the build now stops instead (**Guard**, below).

The goal is the opposite of what happened: a **deliberate, one-time move to the release key**, done in a way that
existing installs accept as an update.

## Why it can be done at all

Android has supported exactly this since Android 9 (API 28): **APK Signature Scheme v3 key rotation**. The new APK
carries a *lineage* that proves the new signing key descends from the old one, so a device that holds the old key accepts
the new key for that app and keeps the data.

- `minSdk` in `app/build.gradle.kts` is **34** (Android 14), so every device this app supports understands v3.
  There is no "old phones cannot rotate" problem.
- Rotation is a chain, not a replacement: the lineage file is created once and reused for every later rotation. **Lose it
  and you can never rotate again** without asking everyone to reinstall.

## The old plan (not run: it needs the old private key)

1. **Keep the two keys where they are, and make both permanent.** The published key is
   `~/phone-aos/keys/published.keystore` (a copy of the debug keystore, so a fresh `~/.android` cannot regenerate it);
   the new key is `~/phone-aos/keys/release.jks`. Passwords live in `~/phone-aos/keys/signing.properties`, which is
   outside every repository. Back both up somewhere he controls.
2. **Create the lineage once**, from the published key to the release key:

   ```
   apksigner rotate --out ~/phone-aos/keys/agent-lineage.bin \
     --old-signer --ks ~/phone-aos/keys/published.keystore --ks-key-alias androiddebugkey \
     --new-signer --ks ~/phone-aos/keys/release.jks         --ks-key-alias agent
   ```

   The lineage file is not a secret: it is public proof of the chain, and a copy belongs in the repo
   (`tools/signing/agent-lineage.bin`) so a lost laptop does not end the project.
3. **Sign the next release with the lineage**:

   ```
   apksigner sign --ks ~/phone-aos/keys/release.jks --ks-key-alias agent \
     --next-signer --ks ~/phone-aos/keys/published.keystore --ks-key-alias androiddebugkey \
     --lineage ~/phone-aos/keys/agent-lineage.bin \
     --out Agent-<version>.apk app-release-unsigned.apk
   ```
   (Or let Gradle sign with the release key and then rotate the signature on the built file, which is the same command
   against `app-release.apk` after stripping its signature.)
4. **Prove it on a phone that already has the app**: a device carrying a build signed with the published key must accept
   `adb install -r` of the rotated APK and keep its data. Nothing ships until that is seen working on a real device.
5. **Publish it as a normal release**, with one line in the notes: the app's signing key was rotated, updates continue
   as they always did, and no reinstall is needed. Every supported device (Android 14 or newer) takes the rotated
   packaging.
6. **From then on** the published key is `release.jks`, and the expected certificate in the guard becomes its
   fingerprint. The lineage stays, so a future rotation is the same three commands.

## GitHub Actions release

`.github/workflows/release.yml` builds the **phone release APK** on a `v*` tag or a manual run. It signs with the
release keystore, verifies the certificate with `tools/check-apk-signature.sh`, uploads the APK and its SHA-256, and
creates or updates the GitHub Release.

Add these four repository secrets in **Settings, Secrets and variables, Actions** (or with `gh secret set`):

- `ANDROID_KEYSTORE_BASE64`: the release keystore, base64-encoded
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS` (`agent` for the fork's release keystore)
- `ANDROID_KEY_PASSWORD`

```bash
# Linux: from the directory holding release.jks
base64 -w0 release.jks | gh secret set ANDROID_KEYSTORE_BASE64 -R Mukul-svg/Mew
gh secret set ANDROID_KEYSTORE_PASSWORD -R Mukul-svg/Mew
gh secret set ANDROID_KEY_ALIAS -R Mukul-svg/Mew --body agent
gh secret set ANDROID_KEY_PASSWORD -R Mukul-svg/Mew
```

```powershell
# Windows PowerShell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) | gh secret set ANDROID_KEYSTORE_BASE64 -R Mukul-svg/Mew
gh secret set ANDROID_KEYSTORE_PASSWORD -R Mukul-svg/Mew
gh secret set ANDROID_KEY_ALIAS -R Mukul-svg/Mew --body agent
gh secret set ANDROID_KEY_PASSWORD -R Mukul-svg/Mew
```

Then run it for a tag:

```bash
gh workflow run release.yml -R Mukul-svg/Mew -f tag=v0.16.0
gh run watch -R Mukul-svg/Mew
```

or push a tag: `git tag v0.16.0 && git push origin v0.16.0`. The workflow refuses to start without all four secrets.
Future rotations still need the v3 lineage procedure above, starting from this release key.

The first release, `v0.16.0`, was published on 2026-11-21 as `Agent-0.16.0.apk` (87,170,416 bytes, SHA-256
`5a297e670957386b0bf9e98cfdee605c9e0d029068b03dd072196d566d98e24b`), signed with the new key. Always verify the
attached `.sha256` and the certificate before installing.

## Guard: a wrong-key APK can be neither built nor published

- `app/build.gradle.kts` refuses a release build that has no release key, instead of letting Android fall back to the
  machine's debug key. Message names the published fingerprint and points at the checker.
- `tools/check-apk-signature.sh <apk>` compares the APK's certificate with the published one and exits non-zero on
  anything else, so it cannot be uploaded by accident. After a rotation it also accepts a key that carries a lineage back
  to the published one, and says so.
- Both are cheap enough to be habit: the checker runs on the built file, before `gh release upload`, every time.

## What would still go wrong, and the answer

- **The lineage file is lost.** Keep the copy in `tools/signing/` and one with the keys. Without it, the next key change
  needs a reinstall by every user.
- **A build signs with the wrong key again.** The build now fails; if someone bypasses it, the checker refuses the file.
- **A device older than Android 14.** Not supported by the app (minSdk 34), so it cannot happen today.
