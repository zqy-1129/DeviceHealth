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

package com.android.server.devicehealth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.devicehealth.DeviceHealthMetric;
import android.devicehealth.DeviceHealthSnapshot;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

/** Host-side unit tests for the production {@link PolicyEngine}. */
public final class PolicyEngineTest {
    private static final long START_MS = 100_000;
    private static final long SAMPLE_INTERVAL_MS = 1_000;

    @Test
    public void firstNormalSampleIsQuiet() {
        PolicyEngine engine = new PolicyEngine();
        PolicyEngine.Result result = engine.evaluate(thermal(42.0f, START_MS), START_MS);
        assertTrue(result.transitions.isEmpty());
        assertEquals(DeviceHealthSnapshot.SEVERITY_NORMAL, result.overallSeverity);
    }

    @Test
    public void debounceHoldsWarningUntilThirdSample() {
        PolicyEngine engine = new PolicyEngine();
        long now = START_MS;
        engine.evaluate(thermal(42.0f, now), now);

        int transitions = 0;
        for (int i = 1; i <= 2; i++) {
            now += SAMPLE_INTERVAL_MS;
            transitions += engine.evaluate(thermal(66.0f, now), now).transitions.size();
        }
        assertEquals(0, transitions);

        now += SAMPLE_INTERVAL_MS;
        PolicyEngine.Result warning = engine.evaluate(thermal(66.0f, now), now);
        assertEquals(1, warning.transitions.size());
        assertEquals(DeviceHealthSnapshot.SEVERITY_WARNING,
                warning.transitions.get(0).toSeverity);
        assertEquals(DeviceHealthSnapshot.SEVERITY_WARNING, warning.overallSeverity);
    }

    @Test
    public void hysteresisClearsWarningBelowWarningClear() {
        PolicyEngine engine = new PolicyEngine();
        long now = START_MS;
        engine.evaluate(thermal(42.0f, now), now);
        for (int i = 0; i < 3; i++) {
            now += SAMPLE_INTERVAL_MS;
            engine.evaluate(thermal(66.0f, now), now);
        }

        int transitions = 0;
        for (int i = 0; i < 2; i++) {
            now += SAMPLE_INTERVAL_MS;
            transitions += engine.evaluate(thermal(58.0f, now), now).transitions.size();
        }
        assertEquals(0, transitions);

        now += SAMPLE_INTERVAL_MS;
        PolicyEngine.Result recovered = engine.evaluate(thermal(58.0f, now), now);
        assertEquals(1, recovered.transitions.size());
        assertEquals(DeviceHealthSnapshot.SEVERITY_NORMAL,
                recovered.transitions.get(0).toSeverity);
    }

    @Test
    public void escalatesToCriticalOneStepAtATime() {
        PolicyEngine engine = new PolicyEngine();
        long now = START_MS;
        engine.evaluate(thermal(42.0f, now), now);

        PolicyEngine.Result warning = null;
        for (int i = 0; i < 3; i++) {
            now += SAMPLE_INTERVAL_MS;
            warning = engine.evaluate(thermal(76.0f, now), now);
        }
        assertNotNull(warning);
        assertEquals(1, warning.transitions.size());
        assertEquals(DeviceHealthSnapshot.SEVERITY_WARNING,
                warning.transitions.get(0).toSeverity);

        PolicyEngine.Result critical = null;
        for (int i = 0; i < 3; i++) {
            now += SAMPLE_INTERVAL_MS;
            critical = engine.evaluate(thermal(76.0f, now), now);
        }
        assertNotNull(critical);
        assertEquals(1, critical.transitions.size());
        assertEquals(DeviceHealthSnapshot.SEVERITY_WARNING,
                critical.transitions.get(0).fromSeverity);
        assertEquals(DeviceHealthSnapshot.SEVERITY_CRITICAL,
                critical.transitions.get(0).toSeverity);
    }

    @Test
    public void sampleAgesIntoStaleAndUnknown() {
        PolicyEngine engine = new PolicyEngine();
        List<DeviceHealthMetric> metrics = thermal(42.0f, START_MS);
        engine.evaluate(metrics, START_MS);

        PolicyEngine.Result stale = engine.evaluate(metrics, START_MS + 11_000);
        assertEquals(1, stale.transitions.size());
        assertEquals(DeviceHealthSnapshot.SEVERITY_STALE,
                stale.transitions.get(0).toSeverity);

        PolicyEngine.Result unknown = engine.evaluate(metrics, START_MS + 31_000);
        assertEquals(1, unknown.transitions.size());
        assertEquals(DeviceHealthSnapshot.SEVERITY_UNKNOWN,
                unknown.transitions.get(0).toSeverity);
    }

    @Test
    public void unavailableDataAgesKnownMetrics() {
        PolicyEngine engine = new PolicyEngine();
        engine.evaluate(thermal(42.0f, START_MS), START_MS);

        PolicyEngine.Result stale = engine.onDataUnavailable(START_MS + 11_000);
        assertEquals(DeviceHealthSnapshot.SEVERITY_STALE, stale.overallSeverity);

        PolicyEngine.Result unknown = engine.onDataUnavailable(START_MS + 31_000);
        assertEquals(DeviceHealthSnapshot.SEVERITY_UNKNOWN, unknown.overallSeverity);
    }

    @Test
    public void thresholdValidationRejectsInvalidConfigurations() {
        assertNull(PolicyEngine.validateThresholds(65.0f, 60.0f, 75.0f, 70.0f, 3));
        assertNotNull(PolicyEngine.validateThresholds(60.0f, 65.0f, 75.0f, 70.0f, 3));
        assertNotNull(PolicyEngine.validateThresholds(65.0f, 60.0f, 70.0f, 75.0f, 3));
        assertNotNull(PolicyEngine.validateThresholds(70.0f, 65.0f, 60.0f, 55.0f, 3));
        assertNotNull(PolicyEngine.validateThresholds(65.0f, 60.0f, 75.0f, 70.0f, 0));
        assertNotNull(PolicyEngine.validateThresholds(65.0f, 60.0f, 75.0f, 70.0f, 21));
        assertNotNull(PolicyEngine.validateThresholds(Float.NaN, 60.0f, 75.0f, 70.0f, 3));
    }

    @Test
    public void fastTransitionsAreRateLimited() {
        PolicyEngine engine = new PolicyEngine();
        engine.setThresholds("thermal.",
                new PolicyEngine.Thresholds(65.0f, 60.0f, 75.0f, 70.0f, 1));
        long now = START_MS;
        engine.evaluate(thermal(42.0f, now), now);

        now += SAMPLE_INTERVAL_MS;
        PolicyEngine.Result warning = engine.evaluate(thermal(66.0f, now), now);
        assertEquals(1, warning.transitions.size());

        now += SAMPLE_INTERVAL_MS;
        PolicyEngine.Result suppressed = engine.evaluate(thermal(76.0f, now), now);
        assertTrue(suppressed.transitions.isEmpty());
    }

    @Test
    public void thresholdsCanBeChangedAndReset() {
        PolicyEngine engine = new PolicyEngine();
        assertEquals("thermal.", engine.thresholdKeyFor("thermal.cpu"));
        assertEquals("memory.pressure", engine.thresholdKeyFor("memory.pressure"));
        assertNull(engine.thresholdKeyFor("unknown.metric"));

        PolicyEngine.Thresholds custom =
                new PolicyEngine.Thresholds(70.0f, 65.0f, 85.0f, 80.0f, 2);
        engine.setThresholds("memory.pressure", custom);
        PolicyEngine.Thresholds current = engine.thresholdsFor("memory.pressure");
        assertNotNull(current);
        assertEquals(70.0f, current.warningEnter, 0.001f);
        assertEquals(2, current.debounceSamples);

        engine.resetThresholds();
        PolicyEngine.Thresholds defaults = engine.thresholdsFor("memory.pressure");
        assertNotNull(defaults);
        assertEquals(80.0f, defaults.warningEnter, 0.001f);
        assertEquals(3, defaults.debounceSamples);
    }

    @Test
    public void severityNamesMatchStateMachine() {
        assertEquals("NORMAL", PolicyEngine.severityName(DeviceHealthSnapshot.SEVERITY_NORMAL));
        assertEquals("WARNING", PolicyEngine.severityName(DeviceHealthSnapshot.SEVERITY_WARNING));
        assertEquals("CRITICAL", PolicyEngine.severityName(DeviceHealthSnapshot.SEVERITY_CRITICAL));
        assertEquals("STALE", PolicyEngine.severityName(DeviceHealthSnapshot.SEVERITY_STALE));
        assertEquals("UNKNOWN", PolicyEngine.severityName(DeviceHealthSnapshot.SEVERITY_UNKNOWN));
    }

    private static List<DeviceHealthMetric> thermal(float value, long timestampMs) {
        return Collections.singletonList(new DeviceHealthMetric("thermal.cpu", "CPU", value, "C",
                timestampMs, /*simulated=*/false));
    }
}
