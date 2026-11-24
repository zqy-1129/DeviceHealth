# Device Health Center

Device Health Center is an end-to-end AOSP sample that connects a vendor AIDL HAL to an
updatable APEX daemon, a `system_server` policy service, and a three-page system app.

```
Vendor HAL            APEX daemon                 Framework                    App
IDeviceHealth  --->   devicehealthd   --->   DeviceHealthManagerService  --->  DeviceHealthCenter
(thermal sysfs)       (sampling/cache)        (policy/events/notify)            (overview/events/diagnostics)
```

The design rationale lives in [docs/design.md](docs/design.md).

## Components

| Layer | Path | Responsibility |
|---|---|---|
| HAL | `hardware/interfaces/devicehealth/` | Reads thermal sysfs, falls back to simulated samples |
| Daemon | `packages/modules/DeviceHealth/` | 2s sampling, ring buffer, HAL reconnect backoff, AIDL v2 interval control |
| Framework | `frameworks/base/services/core/java/com/android/server/devicehealth/` | Policy state machine, events, notifications, watchdog, `dumpsys`/`cmd` |
| System API | `frameworks/base/core/java/android/devicehealth/` | Snapshot/event queries, listener push API, diagnostics |
| App | `packages/apps/DeviceHealthCenter/` | Overview, event timeline, diagnostics and self-test |
| Sepolicy | `system/sepolicy/private/devicehealthd.te` | `devicehealthd` domain and HAL access |
| Cuttlefish | `device/google/cuttlefish/shared/devicehealth/` | Product integration |

## Build

```bash
source build/envsetup.sh
lunch aosp_cf_x86_64_phone-trunk_staging-userdebug
m superimage systemimage productimage systemextimage vendorimage bootimage \
    vbmetaimage vbmetasystemimage
```

When `system.img` is rebuilt incrementally, rebuild `vbmeta` and `vbmeta_system` as
well; otherwise the guest fails AVB verification and reboots during first-stage init.

The daemon and app can also be built incrementally:

```bash
m devicehealthd com.android.devicehealth services DeviceHealthCenter
```

## Deploy (userdebug Cuttlefish)

```bash
adb root
adb remount
adb push out/target/product/vsoc_x86_64/system/framework/services.jar /system/framework/
adb push out/target/product/vsoc_x86_64/system_ext/apex/com.android.devicehealth.apex \
    /system_ext/apex/com.android.devicehealth.apex
adb reboot
```

When `framework.jar` is rebuilt, push `framework.jar`, `system/framework/x86_64/`,
`system/framework/x86/` and `system/framework/boot-*.vdex` as well.

Cold boot from a full image build:

```bash
tools/stop_cvd_ws.sh --wait_for_launcher=5 --clear_instance_dirs
tools/launch_cvd_ws.sh --daemon
```

## Diagnostics

```bash
adb shell dumpsys devicehealth
adb shell cmd devicehealth status
adb shell cmd devicehealth history --limit 20
adb shell cmd devicehealth thresholds
adb shell cmd devicehealth thresholds set thermal.cpu 65 60 75 70 3
adb shell cmd devicehealth thresholds reset
adb shell cmd devicehealth simulate critical thermal.cpu
adb shell cmd devicehealth simulate clear
adb shell cmd devicehealth daemon-interval 1000
adb shell cmd devicehealth daemon-interval reset
adb shell cmd devicehealth stats
adb shell cmd devicehealth self-test
adb shell cmd devicehealth test-policy
adb shell cmd devicehealth debug stall 20000
adb shell cmd devicehealth debug hold-lock 22000
adb shell cmd devicehealth debug reset-watchdog
adb shell cmd devicehealth debug set-daemon-version 3
adb shell cmd devicehealth debug set-daemon-version 0
adb shell cmd devicehealth debug set-daemon-version reset
adb shell cmd devicehealth reset
```

`dumpsys devicehealth` reports the evaluated state, daemon compatibility, watchdog status,
runtime thresholds, recent events, rejected samples and binder call statistics.

## Behavior highlights

- **Policy**: per-metric state machine with warning/critical hysteresis, 3-sample debounce
  and 2s event rate limiting. Event history keeps the newest 100 entries.
- **Staleness**: no fresh sample for 10s becomes `STALE`; 30s becomes `UNKNOWN`.
- **Notifications**: only transitions into `CRITICAL` notify. Tapping the notification
  opens the matching event in the app.
- **Listener push**: the app registers `DeviceHealthListener` while visible and falls back
  to polling on failure.
- **Compatibility**: framework supports daemon interface versions 1..2. Older daemons run
  in degraded mode (no events, no settings); newer daemons are accepted with unknown
  capability bits ignored.
- **Daemon resilience**: 60-batch ring buffer, configurable 500..30000ms sampling interval,
  HAL binder death recipient with immediate reconnect plus 1s..30s backoff, and stale
  cached samples while the HAL is gone.
- **Observability**: atrace slices `devicehealth.sample`, `devicehealth.sample.thermal`,
  `devicehealth.sample.memory`, `devicehealth.binder` and `devicehealth.policy`.
- **Watchdog**: the framework detects a stalled evaluation loop, restarts it on a fresh
  handler thread, and reports trips/recoveries in `dumpsys`.

## Tests

```bash
# Host-side JUnit tests for the production PolicyEngine.
tools/run_policy_junit.sh

# Fast checks (services, baseline, thresholds, events, notifications, degraded mode).
tools/e2e_devicehealth.sh --stabilize

# Adds watchdog, HAL stop/start, daemon kill/restart and app listener lifecycle.
tools/e2e_devicehealth.sh --slow --stabilize

# Deterministic fault matrix: real SIGSTOP binder wedges, lock contention,
# NEWER/TOO_OLD version skew, HAL death and daemon death.
tools/fault_matrix.sh

# Adds permission denial; --apex-missing removes the APEX across two reboots.
tools/fault_matrix.sh --permission --apex-missing
```

The permission-denial test uses a privileged but non-platform-signed app that is **not**
granted `MANAGE_DEVICE_HEALTH`. Install it once:

```bash
m DeviceHealthDenialTest
adb root && adb remount
adb shell mkdir -p /system/priv-app/DeviceHealthDenialTest
adb push out/target/product/vsoc_x86_64/system/priv-app/DeviceHealthDenialTest/\
DeviceHealthDenialTest.apk /system/priv-app/DeviceHealthDenialTest/
adb reboot
```

Then run:

```bash
tools/e2e_devicehealth.sh --slow --stabilize --permission
```

In-service policy checks also run on device:

```bash
adb shell cmd devicehealth test-policy
```

## Notes

- `MANAGE_DEVICE_HEALTH` is a `signature|privileged` permission. The demo app is a
  platform-signed privileged app.
- Simulation commands are restricted to debuggable builds.
- Thresholds and simulated values are runtime-only; they are reset by a reboot.
