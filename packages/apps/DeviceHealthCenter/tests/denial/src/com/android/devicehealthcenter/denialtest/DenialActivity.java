/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.devicehealthcenter.denialtest;

import android.app.Activity;
import android.devicehealth.DeviceHealthEvent;
import android.devicehealth.DeviceHealthListener;
import android.devicehealth.DeviceHealthManager;
import android.devicehealth.DeviceHealthSnapshot;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

/**
 * Calls every DeviceHealthManager API from a non-privileged, non-platform-signed app.
 * All calls must fail with SecurityException.
 */
public final class DenialActivity extends Activity {
    private static final String TAG = "DHC_DENIAL_TEST";

    private final StringBuilder mReport = new StringBuilder();
    private TextView mText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mText = new TextView(this);
        mText.setPadding(48, 48, 48, 48);
        setContentView(mText);
        new Thread(this::runChecks, "dhc-denial-test").start();
    }

    private void runChecks() {
        DeviceHealthManager resolved;
        try {
            resolved = getSystemService(DeviceHealthManager.class);
            if (resolved == null) {
                // Class-key lookup can be unavailable to non-platform apps; fall back to
                // the registered service name.
                Object raw = getSystemService("devicehealth");
                record("getSystemService", raw == null ? "ERROR" : "FALLBACK",
                        raw == null ? "null" : raw.getClass().getName());
                if (raw instanceof DeviceHealthManager) {
                    resolved = (DeviceHealthManager) raw;
                }
            }
        } catch (RuntimeException e) {
            record("getSystemService", "ERROR", e.getClass().getSimpleName());
            finishReport();
            return;
        }
        if (resolved == null) {
            record("getSystemService", "ERROR", "null");
            finishReport();
            return;
        }
        final DeviceHealthManager manager = resolved;

        DeviceHealthListener listener = new DeviceHealthListener() {
            @Override
            public void onSnapshotChanged(DeviceHealthSnapshot snapshot) {
            }

            @Override
            public void onEvent(DeviceHealthEvent event) {
            }
        };

        check("getSnapshot", manager::getSnapshot);
        check("getRecentEvents", () -> manager.getRecentEvents(1));
        check("getDaemonInfo", manager::getDaemonInfo);
        check("registerListener", () -> {
            manager.registerListener(getMainExecutor(), listener);
            return null;
        });
        check("simulateCritical", () -> {
            manager.simulateCritical("thermal.cpu");
            return null;
        });
        check("clearSimulated", () -> {
            manager.clearSimulated();
            return null;
        });
        check("clearEvents", () -> {
            manager.clearEvents();
            return null;
        });
        finishReport();
    }

    private interface Call {
        Object run();
    }

    private void check(String name, Call call) {
        try {
            Object result = call.run();
            record(name, "ALLOWED", "result=" + (result != null ? "non-null" : "null"));
        } catch (SecurityException e) {
            record(name, "DENIED", e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            record(name, "ERROR", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void record(String name, String outcome, String detail) {
        String line = name + "=" + outcome + " (" + detail + ")";
        Log.i(TAG, line);
        synchronized (mReport) {
            mReport.append(line).append('\n');
        }
    }

    private void finishReport() {
        String report;
        boolean passed;
        synchronized (mReport) {
            passed = mReport.indexOf("ALLOWED") < 0 && mReport.indexOf("=ERROR") < 0;
            mReport.append(passed ? "RESULT=PASS" : "RESULT=FAIL");
            report = mReport.toString();
        }
        Log.i(TAG, report);
        runOnUiThread(() -> mText.setText(report));
    }
}
