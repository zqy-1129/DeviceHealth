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
├── patches/
│   ├── 0001-frameworks-base.patch
│   ├── 0002-hardware-interfaces.patch
│   ├── 0003-system-sepolicy.patch
│   ├── 0004-cuttlefish.patch
│   └── 0005-devicehealth-module.patch       # daemon/module, app, e2e script
├── screenshots/
│   ├── 01-overview-normal.png
│   ├── 02-events-critical.png
│   ├── 03-diagnostics-normal.png
│   └── 04-diagnostics-degraded.png
└── scripts/
    ├── e2e_devicehealth.sh
    └── run_policy_junit.sh
```

## Tested environment

- AOSP `android-14.0.0_r75`-style tree, `aosp_cf_x86_64_phone-trunk_staging-userdebug`
- Cuttlefish `vsoc_x86_64`, SELinux enforcing
- `MANAGE_DEVICE_HEALTH` granted to the platform-signed privileged app

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
`packages/apps/DeviceHealthCenter` and `tools/e2e_devicehealth.sh`.

### APEX signing keys

The delivery intentionally excludes the private signing keys
(`packages/modules/DeviceHealth/apex/com.android.devicehealth.pem` and
`com.android.devicehealth.pk8`). Generate test keys before building:

```bash
cd packages/modules/DeviceHealth/apex
$ANDROID_BUILD_TOP/development/tools/make_key com.android.devicehealth \
    '/C=US/ST=CA/L=Mountain View/O=Android/OU=Android/CN=Android/emailAddress=android@android.com'
# Then create the AVB public key used by the apex_key module:
avbtool extract_public_key --key com.android.devicehealth.pem \
    --output com.android.devicehealth.avbpubkey
```

Alternatively copy the test keys from the original build environment.

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
- Fast E2E: 27/27 without the permission app, 33/33 with it.
- Slow E2E: 37/37 without the permission app, 43/43 with it.
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
| `screenshots/01-overview-normal.png` | Overview: normal state, temperature, memory pressure, trends |
| `screenshots/02-events-critical.png` | Event timeline after a simulated critical transition |
| `screenshots/03-diagnostics-normal.png` | Diagnostics: HAL/daemon/framework/app status, APEX, self-test |
| `screenshots/04-diagnostics-degraded.png` | Degraded mode shown in the app |

## Known environment issue

The Cuttlefish `networkstack` process can hit Android's "Too many receivers" limit and
restart `system_server`. Use `--stabilize` (airplane mode + Wi-Fi/data off) for long test
runs, and retry a check if a system_server restart interrupts it. This is unrelated to
DHC.
