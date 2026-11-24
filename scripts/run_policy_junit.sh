#!/usr/bin/env bash
# Build and run the host-side JUnit tests for the production PolicyEngine.
set -eo pipefail

AOSP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$AOSP_DIR"

source build/envsetup.sh >/dev/null
lunch aosp_cf_x86_64_phone-trunk_staging-userdebug >/dev/null

m devicehealth-policy-tests

JAR="out/host/linux-x86/testcases/devicehealth-policy-tests/devicehealth-policy-tests.jar"
JDK="prebuilts/jdk/jdk17/linux-x86/bin/java"
JUNIT="prebuilts/devtools/tools/lib/junit-4.12.jar"
HAMCREST="prebuilts/devtools/tools/lib/hamcrest-core-1.3.jar"

exec "$JDK" -cp "$JAR:$JUNIT:$HAMCREST" \
    org.junit.runner.JUnitCore com.android.server.devicehealth.PolicyEngineTest
