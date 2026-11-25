# Device Health Center delivery

Self-contained delivery for the Device Health Center (DHC) AOSP project: patch series,
APEX v2 upgrade artifact, demo screenshots and end-to-end tests.

## Contents

```
devicehealth-delivery/
├── README.md
├── artifacts/
│   └── com.android.devicehealth-v2.apex     # upgrade exercise artifact
├── docs/
│   ├── design.md                            # design document
│   ├── demo.md                              # demo walkthrough
│   └── README.md                            # module README
├── packages/
│   ├── apps/DeviceHealthCenter/             # actual app source (Java + resources)
│   └── modules/DeviceHealth/                # actual AIDL, daemon, APEX, tests
├── patches/
│   ├── 0001-frameworks-base.patch
│   ├── 0002-hardware-interfaces.patch
│   ├── 0003-system-sepolicy.patch
│   ├── 0004-cuttlefish.patch
│   └── 0005-devicehealth-module.patch       # daemon/module, app, e2e script
├── screenshots/
│   ├── 01-overview-light.png
│   ├── 02-events-light.png
│   ├── 03-diagnostics-light.png
│   ├── 04-overview-dark.png
│   ├── 05-events-dark.png
│   ├── 06-diagnostics-dark.png
│   ├── 07-selftest-light.png
│   ├── 08-selftest-dark.png
│   └── 09-theme-toggle.mp4
└── scripts/
    ├── e2e_devicehealth.sh
    ├── fault_matrix.sh
    └── run_policy_junit.sh
```

## Source layout

`packages/` mirrors the new AOSP projects and contains the actual implementation:

- `packages/apps/DeviceHealthCenter/` — Java app, layouts, drawables, theme resources.
- `packages/modules/DeviceHealth/` — AIDL, native `devicehealthd`, APEX config, policy tests.

Changes to existing AOSP projects (`frameworks/base`, `hardware/interfaces`,
`system/sepolicy`, `device/google/cuttlefish`) remain in `patches/` because they modify
existing trees rather than add standalone projects. The APEX private signing keys are
intentionally excluded; see "APEX signing keys" below.

## Tested environment

- AOSP `android-14.0.0_r75`-style tree, `aosp_cf_x86_64_phone-trunk_staging-userdebug`
- Cuttlefish `vsoc_x86_64`, SELinux enforcing
- `MANAGE_DEVICE_HEALTH` granted to the platform-signed privileged app

## App UI

The app uses framework-only widgets and resources. The fixed header exposes a one-tap
"深色 / 浅色" pill that switches between the light "morning" and dark "deep space" themes;
the pill plays a short press and icon-rotation animation before switching, and the choice is
persisted per app through `UiModeManager.setApplicationNightMode` and re-applied on launch.
The overview uses a circular gauge and gradient sparklines, events use severity rails and
status icons, and diagnostics uses status rings plus a compact information grid.

## Apply the patches

Start from a clean AOSP checkout at the same lunch target. Apply each patch from the
matching git project:

```bash
cd frameworks/base
git apply /path/to/devicehealth-delivery/patches/0001-frameworks-base.patch

cd hardware/interfaces
git apply /path/to/devicehealth-delivery/patches/0002-hardware-interfaces.patch

cd system/sepolicy
git apply /path/to/devicehealth-delivery/patches/0003-system-sepolicy.patch

cd device/google/cuttlefish
git apply /path/to/devicehealth-delivery/patches/0004-cuttlefish.patch

cd /path/to/AOSP
git apply -p1 /path/to/devicehealth-delivery/patches/0005-devicehealth-module.patch
```

Patch 0005 creates the new projects `packages/modules/DeviceHealth`,
`packages/apps/DeviceHealthCenter` and the `tools/` test scripts.

### APEX signing keys

The APEX test key pair is generated in the local AOSP build tree and is intentionally not
committed to this delivery repository:

- `packages/modules/DeviceHealth/apex/com.android.devicehealth.pem`
- `packages/modules/DeviceHealth/apex/com.android.devicehealth.pk8`

It is a 4096-bit RSA test key. The matching public certificate and AVB public key are
committed here:

- `packages/modules/DeviceHealth/apex/com.android.devicehealth.x509.pem`
- `packages/modules/DeviceHealth/apex/com.android.devicehealth.avbpubkey`

The X.509 certificate SHA-256 fingerprint is:

```
A9:0F:87:5B:2F:A5:96:25:A7:DC:D1:0B:8A:10:F8:4E:8D:40:36:77:EA:65:86:CC:C7:96:3A:21:83:AB:14:DA
```

The private key, certificate and AVB public key were verified to match, and the prebuilt
`artifacts/com.android.devicehealth-v2.apex` is signed with the same key.

> These are test keys only. Private signing keys are never committed to this repository;
> never use them for production APEX signing.

When applying `patches/0005-devicehealth-module.patch` to a clean AOSP tree, generate the
key pair before building:

```bash
cd packages/modules/DeviceHealth/apex
$ANDROID_BUILD_TOP/development/tools/make_key com.android.devicehealth \
    '/C=US/ST=CA/L=Mountain View/O=Android/OU=Android/CN=Android/emailAddress=android@android.com'
# Then create the AVB public key used by the apex_key module:
avbtool extract_public_key --key com.android.devicehealth.pem \
    --output com.android.devicehealth.avbpubkey
```

The generated `com.android.devicehealth.x509.pem` and `com.android.devicehealth.avbpubkey`
must match the committed public files if you want to reuse the prebuilt v2 APEX. If you
regenerate the key pair, rebuild and re-sign the v2 artifact with the new key.

## Build

```bash
source build/envsetup.sh
lunch aosp_cf_x86_64_phone-trunk_staging-userdebug
m superimage systemimage productimage systemextimage vendorimage bootimage \
    vbmetaimage vbmetasystemimage
```

When `system.img` is rebuilt incrementally, rebuild `vbmeta` and `vbmeta_system` too;
otherwise the guest fails AVB verification and reboots during first-stage init.

Incremental targets:

```bash
m devicehealthd com.android.devicehealth services DeviceHealthCenter
```

## Deploy

Cold boot from the freshly built images:

```bash
tools/stop_cvd_ws.sh --wait_for_launcher=5 --clear_instance_dirs
tools/launch_cvd_ws.sh --daemon
```

Overlay deployment while iterating:

```bash
adb root && adb remount
adb push out/target/product/vsoc_x86_64/system/framework/services.jar /system/framework/
adb push out/target/product/vsoc_x86_64/system_ext/apex/com.android.devicehealth.apex \
    /system_ext/apex/com.android.devicehealth.apex
adb reboot
```

## Verify

```bash
# Host-side JUnit tests for the production PolicyEngine.
tools/run_policy_junit.sh

# Fast checks: services, baseline, thresholds, events, notification, degraded mode.
tools/e2e_devicehealth.sh --stabilize

# Adds watchdog, HAL stop/start, daemon kill/restart and app listener lifecycle.
tools/e2e_devicehealth.sh --slow --stabilize

# Fault matrix: real binder wedges, lock contention, version/capability skew,
# component death.
tools/fault_matrix.sh

# Adds threshold persistence across reboots and the permission-denial checks.
tools/fault_matrix.sh --persistence --permission

# Adds the real APEX removal/restore scenario (reboots the device twice).
tools/fault_matrix.sh --apex-missing
```

The permission-denial test uses a privileged but non-platform-signed app that is not
granted `MANAGE_DEVICE_HEALTH`. Install it once, then run E2E with `--permission`:

```bash
m DeviceHealthDenialTest
adb root && adb remount
adb shell mkdir -p /system/priv-app/DeviceHealthDenialTest
adb push out/target/product/vsoc_x86_64/system/priv-app/DeviceHealthDenialTest/\
DeviceHealthDenialTest.apk /system/priv-app/DeviceHealthDenialTest/
adb reboot

tools/e2e_devicehealth.sh --slow --stabilize --permission
```

In-service policy checks also run on device:

```bash
adb shell cmd devicehealth self-test
adb shell cmd devicehealth test-policy
```

Verified results for this delivery:

- Host JUnit: 10/10 policy tests pass.
- In-service policy checks: 18/18 pass.
- Fast E2E: 30/30 without the permission app, 36/36 with it.
- Slow E2E: 44/44 without the permission app, 50/50 with it.
- Fault matrix: 40/40 core checks pass; 52/52 with `--persistence --permission`.
- Fault matrix with `--apex-missing --permission --persistence`: 60/60 checks pass after
  removing and restoring `/system_ext/apex/com.android.devicehealth.apex` across two
  reboots. With the APEX removed, the app diagnostics page reports `daemon：不可用` /
  `APEX 版本：不可用` and the in-app self-test reports `SELF-TEST FAIL`.
- A real `SIGSTOP` of `devicehealthd` drives the framework to `STALE` with
  `daemon: unavailable`, bounded `getSnapshot` timeouts and a `SIGCONT` recovery. A real
  `SIGSTOP` of the HAL keeps the daemon connected, ages cached samples into `STALE`, and
  recovers on `SIGCONT`.
- Holding the service lock for 22s trips the watchdog: the loop is restarted on a fresh
  handler thread and `dumpsys` reports `trips 1` / `recoveries 1`.
- Unknown daemon capability bits are exposed in `dumpsys` / `cmd status` (`none` normally),
  logged on change, and flagged by `self-test`; injecting `0x7` reports `0x4`, and reset
  clears both the override and the warning.
- Threshold overrides persist in `Settings.Global` (`devicehealth_thresholds`) and are
  restored before the evaluation loop starts; `thresholds reset` clears the entry and the
  defaults survive a further reboot.
- HAL binder death is reported immediately by the death recipient, the framework moves to
  `STALE`, and the daemon reconnects after the HAL restarts.
- The watchdog restarts a stalled evaluation loop on a fresh handler thread; `dumpsys`
  reports `trips 1` / `recoveries 1`, and sampling continues during the stalled thread.
- The privileged-but-ungranted test app receives `SecurityException` for every
  `DeviceHealthManager` API.
- APEX v1 → v2 upgrade and rollback verified (see below).

## APEX v2 upgrade and rollback

The preinstalled APEX is v1. The artifact in `artifacts/` is a v2 build with the same
interface and daemon code:

```bash
adb install --apex artifacts/com.android.devicehealth-v2.apex
adb reboot
adb shell pm list packages --apex-only --show-versioncode | grep devicehealth
# package:com.android.devicehealth versionCode:2

# The app diagnostics page shows "APEX 版本：versionCode 2".

# Roll back to the preinstalled v1:
adb shell pm uninstall com.android.devicehealth
adb reboot
adb shell pm list packages --apex-only --show-versioncode | grep devicehealth
# package:com.android.devicehealth versionCode:1
```

## Screenshots

| File | Content |
|---|---|
| `screenshots/01-overview-light.png` | Light overview: normal state, gauge, memory pressure, trends |
| `screenshots/02-events-light.png` | Light event timeline: recovery, warning and critical transitions |
| `screenshots/03-diagnostics-light.png` | Light diagnostics: component status and runtime information |
| `screenshots/04-overview-dark.png` | Dark overview: normal state |
| `screenshots/05-events-dark.png` | Dark event timeline |
| `screenshots/06-diagnostics-dark.png` | Dark diagnostics |
| `screenshots/07-selftest-light.png` | Light self-test result (PASS) |
| `screenshots/08-selftest-dark.png` | Dark self-test result (PASS) |
| `screenshots/09-theme-toggle.mp4` | Theme toggle click animation (light → dark → light) |

## Known environment issue

The Cuttlefish `networkstack` process can hit Android's "Too many receivers" limit and
restart `system_server`. Use `--stabilize` (airplane mode + Wi-Fi/data off) for long test
runs, and retry a check if a system_server restart interrupts it. This is unrelated to
DHC.
