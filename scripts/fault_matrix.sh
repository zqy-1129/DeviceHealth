#!/usr/bin/env bash
# Deterministic fault matrix for the Device Health Center stack.
#
# Every scenario injects a real fault and asserts the framework recovers:
#   1. daemon SIGSTOP          framework -> daemon binder call wedges
#   2. HAL SIGSTOP             daemon -> HAL binder call wedges, cached data ages out
#   3. framework lock hold     real monitor contention on the evaluation thread
#   4. NEWER daemon version    forward-compatibility skew
#   5. TOO_OLD daemon version  degraded mode disables simulation
#   6. HAL death               init stop/start, daemon stays up
#   7. daemon death             kill -9, init restart, framework reconnect
#
# Usage:
#   tools/fault_matrix.sh                  # all non-destructive scenarios
#   tools/fault_matrix.sh --permission     # + permission-denial checks
#   tools/fault_matrix.sh --persistence    # + threshold persistence across reboots
#   tools/fault_matrix.sh --apex-missing   # + real APEX removal/restore (reboots)
#
# The APEX scenario removes /system_ext/apex/com.android.devicehealth.apex through
# overlayfs, reboots, asserts the degraded state, then restores the APEX and reboots
# again. An EXIT trap restores the APEX if the script is interrupted.
#
# Artifacts (dumpsys/logcat) are written to $OUT_DIR (default /tmp/devicehealth-faults-*).
set -u

ADB="${ADB:-adb}"
OUT_DIR="${OUT_DIR:-/tmp/devicehealth-faults-$(date +%Y%m%d-%H%M%S)}"
PERMISSION=0
PERSISTENCE=0
APEX_MISSING=0
APEX_REMOVED=0
APEX_BACKUP=/data/local/tmp/com.android.devicehealth.apex.bak
DAEMON_STOPPED_PID=""
HAL_STOPPED_PID=""

usage() {
    echo "Usage: $0 [--permission] [--persistence] [--apex-missing]"
}

for arg in "$@"; do
    case "$arg" in
        --permission) PERMISSION=1 ;;
        --persistence) PERSISTENCE=1 ;;
        --apex-missing) APEX_MISSING=1 ;;
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

wait_for_boot() {
    "$ADB" wait-for-device
    sh 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done' >/dev/null
    sleep 3
}

# Polls `cmd devicehealth status` until it reports the requested state.
wait_for_state() {
    local want="$1" timeout="${2:-25}"
    local deadline=$((SECONDS + timeout))
    while [ "$SECONDS" -lt "$deadline" ]; do
        STATUS_OUT=$(sh cmd devicehealth status)
        if printf '%s' "$STATUS_OUT" | grep -q "state: $want"; then
            return 0
        fi
        sleep 1
    done
    return 1
}

wait_for_text() {
    local needle="$1" timeout="${2:-20}"
    local deadline=$((SECONDS + timeout))
    while [ "$SECONDS" -lt "$deadline" ]; do
        TEXT_OUT=$(sh logcat -d -s DeviceHealthService devicehealthd)
        if printf '%s' "$TEXT_OUT" | grep -q "$needle"; then
            return 0
        fi
        sleep 1
    done
    return 1
}

daemon_pid() { sh pidof devicehealthd | tr -d '\r'; }
hal_pid() { sh pidof com.android.hardware.devicehealth | tr -d '\r'; }
daemon_timeouts() {
    sh cmd devicehealth status | grep -o 'daemon timeouts [0-9]*' | awk '{print $3}'
}

restore_apex() {
    [ "$APEX_REMOVED" = 1 ] || return 0
    say "Restoring the devicehealth APEX"
    "$ADB" root >/dev/null 2>&1
    "$ADB" wait-for-device
    sh remount >/dev/null 2>&1
    sh cp "$APEX_BACKUP" /system_ext/apex/com.android.devicehealth.apex >/dev/null 2>&1
    sh reboot >/dev/null 2>&1
    wait_for_boot
    APEX_REMOVED=0
}

cleanup() {
    # Never leave a SIGSTOPped process behind, even when a check aborts the run.
    if [ -n "$DAEMON_STOPPED_PID" ]; then
        sh kill -CONT "$DAEMON_STOPPED_PID" >/dev/null 2>&1
    fi
    if [ -n "$HAL_STOPPED_PID" ]; then
        sh kill -CONT "$HAL_STOPPED_PID" >/dev/null 2>&1
    fi
    restore_apex
}
trap cleanup EXIT

"$ADB" root >/dev/null 2>&1
"$ADB" wait-for-device
sleep 2
sh cmd devicehealth debug set-daemon-version reset >/dev/null 2>&1
sh cmd devicehealth debug set-daemon-capabilities reset >/dev/null 2>&1
sh cmd devicehealth debug reset-watchdog >/dev/null 2>&1
sh cmd devicehealth thresholds reset >/dev/null 2>&1
sh cmd devicehealth clear-simulated >/dev/null 2>&1

section "Baseline"
if wait_for_state NORMAL 40; then
    pass "baseline reaches NORMAL"
else
    fail "baseline reaches NORMAL (state: ${STATUS_OUT%%$'\n'*})"
fi
out=$(sh cmd devicehealth status)
contains "baseline state NORMAL" "$out" "state: NORMAL"
contains "baseline daemon connected" "$out" "daemon: connected version=2"
contains "baseline compatibility OK" "$out" "compatibility: OK"

section "Fault: daemon SIGSTOP (framework -> daemon binder wedge)"
sh cmd devicehealth reset >/dev/null
sh logcat -c
dpid=$(daemon_pid)
if [ -z "$dpid" ]; then
    fail "devicehealthd pid available"
else
    before=$(daemon_timeouts)
    sh kill -STOP "$dpid"
    DAEMON_STOPPED_PID="$dpid"
    if wait_for_state STALE 25; then
        pass "daemon SIGSTOP drives the framework to STALE"
    else
        fail "daemon SIGSTOP drives the framework to STALE (${STATUS_OUT%%$'\n'*})"
    fi
    out=$(sh cmd devicehealth status)
    contains "daemon reported unavailable while stopped" "$out" "daemon: unavailable"
    after=$(daemon_timeouts)
    if [ -n "$before" ] && [ -n "$after" ] && [ "$after" -gt "$before" ]; then
        pass "daemon timeouts increased ($before -> $after)"
    else
        fail "daemon timeouts increased ($before -> $after)"
    fi
    out=$(sh logcat -d -s DeviceHealthService)
    contains "framework logs the bounded binder timeout" "$out" "timed out after 1500ms"
    sh kill -CONT "$dpid"
    DAEMON_STOPPED_PID=""
    if wait_for_state NORMAL 25; then
        pass "daemon SIGCONT restores NORMAL"
    else
        fail "daemon SIGCONT restores NORMAL"
    fi
fi

section "Fault: HAL SIGSTOP (daemon -> HAL binder wedge)"
sh cmd devicehealth reset >/dev/null
sh logcat -c
hpid=$(hal_pid)
if [ -z "$hpid" ]; then
    fail "device health HAL pid available"
else
    before=$(daemon_timeouts)
    sh kill -STOP "$hpid"
    HAL_STOPPED_PID="$hpid"
    if wait_for_state STALE 30; then
        pass "HAL SIGSTOP ages the cached snapshot into STALE"
    else
        fail "HAL SIGSTOP ages the cached snapshot into STALE (${STATUS_OUT%%$'\n'*})"
    fi
    out=$(sh cmd devicehealth status)
    contains "daemon still connected during HAL wedge" "$out" "daemon: connected version=2"
    after=$(daemon_timeouts)
    if [ -n "$before" ] && [ -n "$after" ] && [ "$after" -eq "$before" ]; then
        pass "no framework-level timeout during HAL wedge ($after)"
    else
        fail "no framework-level timeout during HAL wedge ($before -> $after)"
    fi
    sh kill -CONT "$hpid"
    HAL_STOPPED_PID=""
    if wait_for_state NORMAL 30; then
        pass "HAL SIGCONT restores NORMAL"
    else
        fail "HAL SIGCONT restores NORMAL"
    fi
fi

section "Fault: framework lock contention"
sh logcat -c
sh cmd devicehealth debug reset-watchdog >/dev/null
sh cmd devicehealth debug hold-lock 22000 >/dev/null
sleep 23
if wait_for_text "evaluation loop stalled" 10; then
    pass "watchdog detects the stalled evaluation loop"
else
    fail "watchdog detects the stalled evaluation loop"
fi
out=$(sh logcat -d -s DeviceHealthService)
contains "watchdog restarts the evaluation loop" "$out" "restarted evaluation loop"
if wait_for_text "evaluation loop recovered" 15; then
    pass "watchdog logs recovery"
else
    fail "watchdog logs recovery"
fi
dump=$(sh dumpsys devicehealth)
matches "watchdog trips recorded" "$dump" "trips [1-9]"
matches "watchdog recoveries recorded" "$dump" "recoveries [1-9]"
if wait_for_state NORMAL 20; then
    pass "state NORMAL after lock contention"
else
    fail "state NORMAL after lock contention"
fi

section "Fault: NEWER daemon version"
sh cmd devicehealth debug set-daemon-version 3 >/dev/null
out=$(sh cmd devicehealth status)
contains "NEWER compatibility reported" "$out" "compatibility: NEWER"
contains "version override visible" "$out" "daemon version override: 3 (debug)"
sh cmd devicehealth debug set-daemon-version reset >/dev/null
out=$(sh cmd devicehealth status)
contains "NEWER override cleared" "$out" "compatibility: OK"
if printf '%s' "$out" | grep -q "daemon version override"; then
    fail "version override removed"
else
    pass "version override removed"
fi

section "Fault: unknown capability bits"
sh logcat -c
sh cmd devicehealth debug set-daemon-capabilities 0x7 >/dev/null
sleep 2
out=$(sh cmd devicehealth status)
contains "unknown capability bits reported" "$out" "unknown capabilities: 0x4"
contains "capability override visible" "$out" "daemon capabilities override: 0x7 (debug)"
if wait_for_text "reports unknown capabilities 0x4" 10; then
    pass "framework logs the unknown capability bits"
else
    fail "framework logs the unknown capability bits"
fi
sh cmd devicehealth debug set-daemon-capabilities reset >/dev/null
sleep 2
out=$(sh cmd devicehealth status)
contains "unknown capabilities cleared" "$out" "unknown capabilities: none"

section "Fault: TOO_OLD daemon version"
sh cmd devicehealth debug set-daemon-version 0 >/dev/null
out=$(sh cmd devicehealth status)
contains "TOO_OLD compatibility reported" "$out" "compatibility: TOO_OLD"
out=$(sh 'cmd devicehealth simulate critical thermal.cpu; echo exit=$?')
contains "simulation disabled in degraded mode" "$out" "disabled"
contains "degraded simulation exit code" "$out" "exit=255"
sh cmd devicehealth debug set-daemon-version reset >/dev/null
out=$(sh cmd devicehealth status)
contains "TOO_OLD override cleared" "$out" "compatibility: OK"

section "Fault: HAL death (init stop)"
sh cmd devicehealth reset >/dev/null
sh logcat -c
out=$(sh stop vendor.devicehealth-default)
if printf '%s' "$out" | grep -qi "does not exist\|unknown service"; then
    fail "HAL init service stopped ($out)"
else
    pass "HAL init service stopped"
fi
if wait_for_text "HAL binder died" 15; then
    pass "daemon death recipient reports the HAL death"
else
    fail "daemon death recipient reports the HAL death"
fi
out=$(sh cmd devicehealth status)
contains "daemon stays connected after HAL death" "$out" "daemon: connected version=2"
if wait_for_state STALE 30; then
    pass "framework reports STALE after HAL death"
else
    fail "framework reports STALE after HAL death"
fi
sh start vendor.devicehealth-default >/dev/null
if wait_for_state NORMAL 30; then
    pass "framework recovers after HAL restart"
else
    fail "framework recovers after HAL restart"
fi

section "Fault: daemon death and reconnect"
sh logcat -c
dpid=$(daemon_pid)
if [ -z "$dpid" ]; then
    fail "devicehealthd pid available before kill"
else
    sh kill -9 "$dpid" >/dev/null 2>&1
    if wait_for_text "devicehealthd died" 10; then
        pass "framework death recipient reports the daemon death"
    else
        fail "framework death recipient reports the daemon death"
    fi
    deadline=$((SECONDS + 30))
    newpid=""
    while [ "$SECONDS" -lt "$deadline" ]; do
        newpid=$(daemon_pid)
        if [ -n "$newpid" ] && [ "$newpid" != "$dpid" ]; then
            break
        fi
        sleep 1
    done
    if [ -n "$newpid" ] && [ "$newpid" != "$dpid" ]; then
        pass "init restarted devicehealthd ($dpid -> $newpid)"
    else
        fail "init restarted devicehealthd"
    fi
    deadline=$((SECONDS + 30))
    while [ "$SECONDS" -lt "$deadline" ]; do
        out=$(sh cmd devicehealth status)
        if printf '%s' "$out" | grep -q "daemon: connected version=2"; then
            break
        fi
        sleep 1
    done
    contains "framework reconnected to the new daemon" "$out" "daemon: connected version=2"
    if wait_for_state NORMAL 30; then
        pass "state NORMAL after daemon restart"
    else
        fail "state NORMAL after daemon restart"
    fi
fi

if [ "$PERMISSION" = 1 ]; then
    section "Fault: permission denial"
    out=$(sh pm path com.android.devicehealthcenter.denialtest)
    contains "denial test app installed" "$out" "priv-app/DeviceHealthDenialTest"
    granted=$(sh dumpsys package com.android.devicehealthcenter.denialtest | grep -c \
            "MANAGE_DEVICE_HEALTH")
    if [ "$granted" = "0" ]; then
        pass "MANAGE_DEVICE_HEALTH is not granted"
    else
        fail "MANAGE_DEVICE_HEALTH is not granted (found $granted references)"
    fi
    sh logcat -c
    sh am force-stop com.android.devicehealthcenter.denialtest >/dev/null 2>&1
    sh am start -n com.android.devicehealthcenter.denialtest/.DenialActivity >/dev/null 2>&1
    sleep 3
    out=$(sh logcat -d -s DHC_DENIAL_TEST)
    contains "unprivileged API calls denied" "$out" "RESULT=PASS"
    matches "getSnapshot denied" "$out" "getSnapshot=DENIED"
    matches "registerListener denied" "$out" "registerListener=DENIED"
    matches "clearEvents denied" "$out" "clearEvents=DENIED"
    sh am force-stop com.android.devicehealthcenter.denialtest >/dev/null 2>&1
fi

if [ "$PERSISTENCE" = 1 ]; then
    section "Fault: threshold persistence across reboot"
    sh cmd devicehealth thresholds set thermal.cpu 61 56 71 66 4 >/dev/null
    out=$(sh settings get global devicehealth_thresholds)
    contains "threshold override written to settings" "$out" "61"
    out=$(sh cmd devicehealth thresholds)
    contains "override active before reboot" "$out" \
            "thermal.: warn 61.0/56.0 crit 71.0/66.0 debounce 4"
    # SettingsProvider persists asynchronously; give it time before rebooting.
    sleep 5
    sh reboot >/dev/null
    wait_for_boot
    out=$(sh cmd devicehealth thresholds)
    contains "override restored after reboot" "$out" \
            "thermal.: warn 61.0/56.0 crit 71.0/66.0 debounce 4"
    if wait_for_text "Restored 2 persisted threshold override" 15; then
        pass "framework logs threshold restoration"
    else
        fail "framework logs threshold restoration"
    fi
    sh cmd devicehealth thresholds reset >/dev/null
    out=$(sh settings get global devicehealth_thresholds)
    contains "reset clears persisted override" "$out" "null"
    sleep 5
    sh reboot >/dev/null
    wait_for_boot
    out=$(sh cmd devicehealth thresholds)
    contains "defaults restored after second reboot" "$out" \
            "thermal.: warn 65.0/60.0 crit 75.0/70.0"
fi

if [ "$APEX_MISSING" = 1 ]; then
    section "Fault: APEX missing (real removal + reboot)"
    # Earlier reboot scenarios can leave adbd unrooted; remount needs root.
    "$ADB" root >/dev/null 2>&1
    "$ADB" wait-for-device
    sleep 2
    sh remount >/dev/null 2>&1
    sh cp /system_ext/apex/com.android.devicehealth.apex "$APEX_BACKUP" >/dev/null
    sh rm /system_ext/apex/com.android.devicehealth.apex
    if [ -z "$(sh 'ls /system_ext/apex/com.android.devicehealth.apex 2>/dev/null')" ]; then
        pass "devicehealth APEX removed"
    else
        fail "devicehealth APEX removed"
    fi
    APEX_REMOVED=1
    sh reboot >/dev/null
    wait_for_boot
    out=$(sh pm list packages --apex-only --show-versioncode)
    if printf '%s' "$out" | grep -q "com.android.devicehealth"; then
        fail "APEX absent from package list"
    else
        pass "APEX absent from package list"
    fi
    out=$(sh cmd devicehealth status)
    contains "framework degrades to UNKNOWN" "$out" "state: UNKNOWN"
    contains "daemon reported unavailable" "$out" "daemon: unavailable"
    contains "compatibility UNAVAILABLE" "$out" "compatibility: UNAVAILABLE"
    out=$(sh cmd devicehealth self-test)
    contains "framework self-test fails without the daemon" "$out" "SELF-TEST FAIL"
    restore_apex
    deadline=$((SECONDS + 20))
    while [ "$SECONDS" -lt "$deadline" ]; do
        out=$(sh pm list packages --apex-only --show-versioncode)
        if printf '%s' "$out" | grep -q "com.android.devicehealth versionCode:1"; then
            break
        fi
        sleep 1
    done
    contains "APEX restored" "$out" "com.android.devicehealth versionCode:1"
    if wait_for_state NORMAL 30; then
        pass "state NORMAL after APEX restore"
    else
        fail "state NORMAL after APEX restore"
    fi
fi

sh cmd devicehealth reset >/dev/null 2>&1
sh cmd devicehealth debug set-daemon-version reset >/dev/null 2>&1
sh cmd devicehealth debug reset-watchdog >/dev/null 2>&1
sh dumpsys devicehealth >"$OUT_DIR/dumpsys.txt" 2>&1
sh logcat -d -s DeviceHealthService devicehealthd >"$OUT_DIR/logcat.txt" 2>&1

say ""
say "Artifacts: $OUT_DIR"
if [ "$FAILURES" -eq 0 ]; then
    say "FAULT MATRIX PASS ($CHECKS checks)"
    exit 0
fi
say "FAULT MATRIX FAIL ($FAILURES failed / $CHECKS checks)"
exit 1
