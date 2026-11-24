# Demo walkthrough

A five-minute walkthrough that shows the full HAL → daemon → framework → app chain.
Run it on a userdebug Cuttlefish instance with Device Health Center installed.

```bash
adb root
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell am start -n com.android.devicehealthcenter/.MainActivity
```

## 1. Baseline

```bash
adb shell cmd devicehealth status
adb shell dumpsys devicehealth
```

Expected: `state: NORMAL`, `daemon: connected version=2`, `compatibility: OK`,
`watchdog: ok`. In the app, the diagnostics page shows HAL, daemon, framework and app
status, plus the APEX version.

## 2. Warning and critical

```bash
adb shell cmd devicehealth simulate critical thermal.cpu
```

Wait about 10 seconds. The policy engine steps `NORMAL → WARNING → CRITICAL`; the event
timeline gains two entries and a heads-up notification appears. Tap the notification to
open the event detail with the deep link highlighted.

```bash
adb shell cmd devicehealth history
```

Expected:

```
#2 ... thermal.cpu: WARNING -> CRITICAL (80.0 C, threshold 75.0)
#1 ... thermal.cpu: NORMAL -> WARNING (80.0 C, threshold 65.0)
```

## 3. Recovery

```bash
adb shell dumpsys devicehealth clear-simulated
```

Within a few samples the metric returns to `NORMAL` and a recovery event is appended.
No notification is posted for recovery.

## 4. Diagnostics and policy tests

```bash
adb shell cmd devicehealth self-test
adb shell cmd devicehealth test-policy
```

Both should end with `SELF-TEST PASS` / `POLICY TESTS PASS`. `cmd devicehealth stats`
shows binder call counters, including the app's listener registration.

## 5. Degraded mode (daemon compatibility)

```bash
adb shell cmd devicehealth debug set-daemon-version 0
```

The diagnostics page turns the daemon row red: the interface is below the minimum
supported version, events and runtime settings are disabled, and
`cmd devicehealth simulate critical thermal.cpu` is rejected.

```bash
adb shell cmd devicehealth debug set-daemon-version reset
```

## 6. Fault injection

```bash
# HAL disappears: framework moves to STALE, daemon starts reconnect backoff.
adb shell stop vendor.devicehealth-default
adb shell cmd devicehealth status
adb shell start vendor.devicehealth-default

# Daemon dies: init restarts it, framework reconnects through its death recipient.
adb shell kill -9 $(adb shell pidof devicehealthd)
adb shell cmd devicehealth status
```

`dumpsys devicehealth` keeps reporting watchdog status, the last rejected sample and
per-call binder statistics throughout.

## 7. APEX upgrade

See `delivery/README.md` for the v2 upgrade and rollback commands. The app's APEX version
line changes from `versionCode 1` to `versionCode 2` after the upgrade and reverts on
rollback.

## Automated verification

```bash
tools/e2e_devicehealth.sh --slow --stabilize
```

The script performs all of the above and stores `dumpsys` and `logcat` artifacts in
`/tmp/devicehealth-e2e-*`.
