# Zenix Container

Dual spaces + microG + live debugger, built on the BlackBox virtual engine
(Bcore), forked from `FBlackBox/BlackBox` via the Android-16 maintained fork
`ZiTanIOI/BlackBox`.

Package: `top.niunaijun.blackboxa64` (arm64) / `top.niunaijun.blackboxa32` (arm32)
Version: `2.3.1-zenix.2` (versionCode 18)

---

## Fixes in v2.3.1-zenix.2 (first-device field bugs)

Real-device testing of `-zenix.1` exposed four problems. All fixed here:

1. **Payload reported MISSING on first launch / self-test.**
   `materialize()` (assets -> `filesDir/zenix_payload/`) only ever ran inside
   `installMicroG()`, so until a stack install was attempted the status bar,
   self test and new-space all read `payload: MISSING`. Now the screen stages
   the bundled payload in a background thread on launch
   (`ZenixActivity.stagePayloadInBackground()`), then re-reads status. The
   self test now also distinguishes *in-APK-but-not-staged* from
   *wrong-APK-no-asset-at-all* (`ZenixPayload.assetsAvailable()`), and
   `assetSize()` uses `AssetFileDescriptor.getLength()` instead of the
   unreliable `InputStream.available()`.

2. **"Installed" claimed success with no APKs, and Reinstall was a no-op.**
   The old loop `return false`'d on the first failed file, so one bad file
   silently left the rest uninstalled while the button still fired. The loop
   now records per-file failures, installs what it can, and only reports
   success when all three packages re-verify (`status(userId).complete()`).
   `installMicroG()` gained a `force` flag that uninstalls the three packages
   from the space first; the Reinstall path uses it, so an already-"installed"
   (but broken) stack is actually wiped and reinstalled instead of skipped.

3. **Share log crashed** (`StringIndexOutOfBoundsException` in the
   FileProvider path). `shareLog()` now falls back to sharing the log text
   directly (`ACTION_SEND` + `EXTRA_TEXT`) when the file-provider URI fails,
   and to the clipboard as a last resort.

4. **Space-row buttons were unreadable** (dark text on dark card). Fixed with
   explicit white text + white stroke in `item_zenix_space.xml`, and an
   `Install microG` button was added to the top action row so the stack can be
   installed into space 0 without opening a per-space button.

Note on *guest apps that need Play/ pairing crashing*: with a de-Googled host
there is no real Google signature to spoof, so Play Integrity / account
pairing inside the container will not pass. That is a hard limit of the
container, not a bug — microG makes *most* GMS APIs work, but not attestation.

---

## What was actually wrong before this build

Bcore's GMS Manager (`Bcore/.../core/GmsCore.java`) mirrors Google packages that
are **already installed on the host device**:

```java
BlackBoxCore.getContext().getPackageManager().getApplicationInfo(packageName, 0);
// NameNotFoundException -> continue
```

On a de-Googled phone that lookup throws for every package, so
`isSupportGms()` returns false and the GMS Manager screen reports
*"Currently, there is no Google service environment on mobile phones."*
The feature is not broken — it was designed to clone a host GMS that a
microG user does not have.

Zenix stops depending on the host: microG ships **inside the container** as a
payload and is installed into each space through the normal
`installByStorage` path (`BPackageManagerService.installPackageAsUserLocked`),
which never consults the host package manager.

---

## What Zenix adds

### Spaces
* `New space` creates the next BlackBox user (`createUser(max+1)`, hard cap 64).
* Each row installs into **that** space only. Space 0 is the base container and
  is protected from deletion (`deleteSpace` refuses id <= 0 by name in the log).
* Every space gets its own app set, data dir, and microG stack.

### microG stack
Installed in dependency order, each one verified afterwards through
`BlackBoxCore.isInstalled()`:

| file (assets)   | package                  | component              |
|-----------------|--------------------------|------------------------|
| `gmscore.apk`   | `com.google.android.gms` | microG Services        |
| `gsfproxy.apk`  | `com.google.android.gsf` | Services Framework Proxy |
| `fakestore.apk` | `com.android.vending`    | microG Companion       |

The row shows the three states individually (`gms ok, gsf missing, ...`), never
a single green "ok", because a partial stack fails in confusing ways.

Payload is fetched at build time, not committed (GmsCore is ~104 MB):

```bash
tools/fetch-microg.sh          # latest microG release, verifies package names
tools/fetch-microg.sh --verify # + detached .asc signatures (needs gpg keyring)
```

The script picks assets by exact name pattern, so the Huawei (`-hw`) and dev
(`org.microg.gms-*`) flavours can never be silently shipped in place of the
AOSP build, and it fails loudly if upstream renames anything.

### Install button
`Install APK` (global, and per space) opens the system document picker and
installs the picked APK into the selected space via
`installPackageAsUser(Uri, userId)`. This is the manual fallback for any app
that is not part of the payload — the thing you press when auto-install does
not do what you want.

The ABI is checked **before** the copy: an arm64-only APK is declined by name in
the 32-bit box instead of being pushed through the parser first.

### Live debugger
`ZenixLog`: 600-line ring buffer, fed to the console on the main thread with one
coalesced post per burst, every line also going to:

* logcat, tag `Zenix` — `adb logcat -s Zenix`
* `filesDir/zenix.log` — append-only, 512 KiB cap, session banner carries the
  build stamp so a pasted log says which build produced it
* clipboard (`Copy`) and share sheet (`Share log`)

Filters: `ALL / INFO+ / WARN+ / ERROR`. Colors: red = error, amber = warn,
green = space/microG gates.

`Self test` measures, without installing anything: container ABI, host package,
space count, payload presence, and an `getPackageArchiveInfo` probe against a
staged payload APK — the same parse the container performs before an install, so
a failure there predicts a failed guest install.

---

## Honest limits

1. **Signature spoofing is still required.** microG passes for GMS only if the
   host framework returns Google's signing certificate for
   `com.google.android.gms`. A container cannot grant that. Apps that verify the
   GMS signature will still refuse microG inside a space. The container measures
   and reports; it cannot fix this from inside.
2. **32-bit and 64-bit are separate APKs.** Bcore rejects an APK whose native
   libs do not match the box. microG ships both arm64 and armeabi libs, so both
   builds can host it — but a guest app is still bound by its own ABIs.
3. **APKs are copied, not bind-mounted.** Each space holds its own full copy of
   microG; two spaces with the stack cost ~2x the disk.
4. **Play Integrity / SafetyNet will not pass** inside a virtual container, with
   or without microG.

---

## Build

```bash
export ANDROID_SDK_ROOT=/path/to/sdk      # build-tools 29.0.2, NDK 21.4.7075529
tools/fetch-microg.sh                     # optional: enables auto-install
tools/make-keystore.sh                    # creates ~/.zenix-keys/zenix-release.jks
./gradlew :app:assembleBlackBox64Release  # 109 MB, includes the payload
./gradlew :app:assembleBlackBox32Release
```

Signing: v1 + v2 schemes, RSA 2048. `keystore.properties` and `*.jks` are
gitignored — **never** commit them.

Toolchain pin: AGP 4.0.1 defaults to NDK 21.0.6113669, which is not installed
everywhere. `build.gradle` pins every module to `21.4.7075529` (r21e LTS).

---

## Log lines to expect on a clean run

```
I/ZENIX    console opened, host=top.niunaijun.blackboxa64 abi=64 lines=1
I/PAYLOAD  staged 3 file(s) in /data/user/0/<pkg>/files/zenix_payload
I/MICROG   install start: space=1 stack=microG
I/MICROG   gmscore.apk installed as com.google.android.gms in 8421ms
I/MICROG   gsfproxy.apk installed as com.google.android.gsf in 96ms
I/MICROG   fakestore.apk installed as com.android.vending in 214ms
I/MICROG   verify space=1 :: gms ok, gsf ok, vending ok
I/MICROG   stack READY in space 1 (signature spoofing still required by the host ROM)
```

Failure shapes are equally explicit:

```
E/MICROG   gmscore.apk FAILED in 137ms :: The box does not support 32-bit Application
E/MICROG   gmscore.apk DECLINED: APK ships arm64 only, this is the 32-bit box
E/PAYLOAD  build did not ship [gmscore.apk] -- run tools/fetch-microg.sh before building
```

---

## Files added

```
app/src/main/java/top/niunaijun/blackboxa/zenix/
  ZenixActivity.kt        screen: status, spaces, console, actions
  ZenixSpaceManager.java  spaces + microG install/verify/uninstall + self test
  ZenixPayload.java       asset -> filesDir staging (atomic, size-checked)
  ZenixLog.java           ring buffer + file sink + logcat + main-thread dispatch
  ZenixLogAdapter.kt      live console list
  ZenixSpaceAdapter.kt    space rows with per-package stack state
app/src/main/res/layout/{activity_zenix,item_zenix_space,item_zenix_log}.xml
app/src/main/res/xml/zenix_file_paths.xml     FileProvider scope (log only)
app/src/main/assets/zenix_payload/            payload drop zone (README only in git)
tools/fetch-microg.sh                         payload fetcher + identity check
tools/make-keystore.sh                        signing identity generator
```

Entry points: Settings → *Zenix Container*, and the main screen overflow menu →
*Zenix Container*.
