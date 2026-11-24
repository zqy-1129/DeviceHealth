#!/usr/bin/env bash
# End-to-end checks for the Device Health Center stack.
#
# Usage:
#   tools/e2e_devicehealth.sh                # fast checks
#   tools/e2e_devicehealth.sh --slow         # adds watchdog, HAL, daemon-death and App checks
#   tools/e2e_devicehealth.sh --stabilize    # disables Wi-Fi/data first (Cuttlefish stability)
#
# Artifacts (dumpsys/logcat) are written to $OUT_DIR (default /tmp/devicehealth-e2e-*).
set -u

ADB="${ADB:-adb}"
OUT_DIR="${OUT_DIR:-/tmp/devicehealth-e2e-$(date +%Y%m%d-%H%M%S)}"
SLOW=0
STABILIZE=0

usage() {
    echo "Usage: $0 [--slow] [--stabilize]"
}

for arg in "$@"; do
    case "$arg" in
        --slow) SLOW=1 ;;
        --stabilize) STABILIZE=1 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $arg"; usage; exit 2 ;;
    esac
done

mkdir -p "$OUT_DIR"
CHECKS=0
FAILURES=0

say() { printf '%s\n' "$*"; }
pass() { CHECKS=$((CHECKS + 1)); say "PASS $*"; }
fail() { CHECKS=$((CHECKS + 1)); FAILURES=$((FAILURES + 1)); say "FAIL $*"; }
section() { say ""; say "== $* =="; }

contains() {
    local name="$1" text="$2" needle="$3"
    if [[ "$text" == *"$needle"* ]]; then
        pass "$name"
    else
        fail "$name (missing: $needle)"
    fi
}

matches() {
    local name="$1" text="$2" regex="$3"
    if printf '%s' "$text" | grep -qE "$regex"; then
        pass "$name"
    else
        fail "$name (no match: $regex)"
    fi
}

sh() { "$ADB" shell "$@" 2>&1; }

if [ "$STABILIZE" = 1 ]; then
    "$ADB" shell cmd connectivity airplane-mode enable >/dev/null 2>&1 || true
    "$ADB" shell svc wifi disable >/dev/null 2>&1 || true
    "$ADB" shell svc data disable >/dev/null 2>&1 || true
fi

section "Services and APEX"
out=$(sh service check devicehealth)
contains "framework service registered" "$out" "found"
out=$(sh service check devicehealthd)
contains "daemon service registered" "$out" "found"
out=$(sh service check android.hardware.devicehealth.IDeviceHealth/default)
contains "HAL service registered" "$out" "found"
out=$(sh ls /system_ext/apex)
contains "devicehealth APEX preinstalled" "$out" "com.android.devicehealth.apex"
out=$(sh pidof devicehealthd)
if [ -n "$(printf '%s' "$out" | tr -d '\r')" ]; then
    pass "devicehealthd process running"
else
    fail "devicehealthd process running"
fi

section "Baseline diagnostics"
dump=$(sh dumpsys devicehealth)
contains "daemon connected" "$dump" "daemon: connected"
contains "daemon compatibility OK" "$dump" "compatibility: OK"
contains "watchdog healthy" "$dump" "watchdog: ok"
out=$(sh cmd devicehealth self-test)
contains "service self-test passes" "$out" "SELF-TEST PASS"
out=$(sh cmd devicehealth test-policy)
contains "policy self-tests pass" "$out" "POLICY TESTS PASS"

section "Runtime thresholds"
out=$(sh cmd devicehealth thresholds)
contains "default thermal thresholds" "$out" "thermal.: warn 65.0/60.0 crit 75.0/70.0"
out=$(sh cmd devicehealth thresholds set memory.pressure 70 65 85 80 2)
contains "threshold update applied" "$out" "Updated memory.pressure"
out=$(sh 'cmd devicehealth thresholds set memory.pressure 60 65 85 80 2; echo exit=$?')
contains "inverted thresholds rejected" "$out" "warningClear"
contains "inverted thresholds exit code" "$out" "exit=255"
out=$(sh cmd devicehealth thresholds set thermal.cpu 65 60 200 70 3)
contains "out-of-range thresholds rejected" "$out" "outside"
sh cmd devicehealth thresholds reset >/dev/null
out=$(sh cmd devicehealth thresholds)
contains "thresholds restored" "$out" "thermal.: warn 65.0/60.0 crit 75.0/70.0"

section "Policy events and notification"
sh cmd devicehealth reset >/dev/null
sh cmd devicehealth simulate critical thermal.cpu >/dev/null
sleep 14
out=$(sh cmd devicehealth history --limit 10)
contains "warning event recorded" "$out" "NORMAL -> WARNING"
contains "critical event recorded" "$out" "WARNING -> CRITICAL"
out=$(sh dumpsys notification --noredact)
contains "critical notification posted" "$out" "Device health critical"
sh dumpsys devicehealth clear-simulated >/dev/null
sh cmd devicehealth reset >/dev/null
out=$(sh cmd devicehealth history)
contains "events cleared" "$out" "No events"

section "Daemon compatibility and degraded mode"
sh cmd devicehealth debug set-daemon-version 0 >/dev/null
out=$(sh cmd devicehealth status)
contains "degraded compatibility reported" "$out" "TOO_OLD"
out=$(sh 'cmd devicehealth simulate critical thermal.cpu; echo exit=$?')
contains "simulation disabled in degraded mode" "$out" "disabled"
contains "degraded simulation exit code" "$out" "exit=255"
sh cmd devicehealth debug set-daemon-version reset >/dev/null
out=$(sh cmd devicehealth status)
contains "compatibility restored" "$out" "OK"

section "Sampling interval (daemon v2)"
out=$(sh cmd devicehealth daemon-interval 1000)
contains "sampling interval applied" "$out" "set to 1000ms"
out=$(sh 'cmd devicehealth daemon-interval 100; echo exit=$?')
contains "invalid sampling interval rejected" "$out" "between 500 and 30000"
contains "invalid sampling interval exit code" "$out" "exit=255"
sh cmd devicehealth daemon-interval reset >/dev/null

if [ "$SLOW" = 1 ]; then
    section "Watchdog"
    sh logcat -c
    sh cmd devicehealth debug stall 22000 >/dev/null
    sleep 21
    out=$(sh logcat -d -s DeviceHealthService)
    contains "watchdog detects the stall" "$out" "Watchdog: evaluation loop stalled"
    sleep 8
    dump=$(sh dumpsys devicehealth)
    matches "watchdog trips recorded" "$dump" "trips [1-9]"
    out=$(sh logcat -d -s DeviceHealthService)
    contains "watchdog logs recovery" "$out" "Watchdog: evaluation loop recovered"

    section "HAL failure and recovery"
    "$ADB" root >/dev/null 2>&1
    sleep 2
    sh stop vendor.devicehealth-default >/dev/null
    sleep 14
    out=$(sh cmd devicehealth status)
    contains "framework reports STALE without the HAL" "$out" "state: STALE"
    sh start vendor.devicehealth-default >/dev/null
    sleep 10
    out=$(sh cmd devicehealth status)
    contains "framework recovers with the HAL" "$out" "state: NORMAL"

    section "Daemon death and reconnect"
    pid=$(sh pidof devicehealthd | tr -d '\r')
    if [ -n "$pid" ]; then
        sh kill -9 "$pid" >/dev/null 2>&1
        sleep 8
        out=$(sh service check devicehealthd)
        contains "daemon restarted by init" "$out" "found"
        out=$(sh cmd devicehealth status)
        contains "framework reconnected to the daemon" "$out" "daemon: connected version=2"
    else
        fail "devicehealthd pid available"
    fi

    section "App listener lifecycle"
    sh input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
    sh wm dismiss-keyguard >/dev/null 2>&1
    sh am start -n com.android.devicehealthcenter/.MainActivity >/dev/null 2>&1
    sleep 5
    dump=$(sh dumpsys devicehealth)
    contains "app registers a listener" "$dump" "listeners: 1"
    sh input keyevent KEYCODE_HOME >/dev/null 2>&1
    sleep 4
    dump=$(sh dumpsys devicehealth)
    contains "app unregisters on stop" "$dump" "listeners: 0"
fi

sh cmd devicehealth reset >/dev/null
sh cmd devicehealth daemon-interval reset >/dev/null 2>&1
sh cmd devicehealth debug set-daemon-version reset >/dev/null 2>&1

sh dumpsys devicehealth >"$OUT_DIR/dumpsys.txt" 2>&1
sh logcat -d -s DeviceHealthService devicehealthd >"$OUT_DIR/logcat.txt" 2>&1

say ""
say "Artifacts: $OUT_DIR"
if [ "$FAILURES" -eq 0 ]; then
    say "E2E PASS ($CHECKS checks)"
    exit 0
fi
say "E2E FAIL ($FAILURES failed / $CHECKS checks)"
exit 1
