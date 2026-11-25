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

package android.devicehealth;

import android.devicehealth.MetricSample;

/**
 * A point-in-time snapshot returned by devicehealthd.
 */
@JavaDerive(toString=true)
parcelable Snapshot {
    /** Latest samples from all providers. */
    MetricSample[] metrics;

    /** Timestamp in milliseconds since boot when the snapshot was assembled. */
    long timestampMs;

    /** True when the returned data is cached and the source is unavailable. */
    boolean stale;

    /** Interface version reported by the daemon. */
    int daemonVersion;

    /** Capability bitmask reported by the daemon. */
    int capabilities;
}
