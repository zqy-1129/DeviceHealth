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

/**
 * A single health metric sample delivered by devicehealthd.
 */
@JavaDerive(toString=true)
parcelable MetricSample {
    /** Stable identifier, for example "thermal.zone0" or "memory.pressure". */
    String id;

    /** Human-readable name, for example "CPU". */
    String name;

    /** Numeric value expressed in {@link #unit}. */
    float value;

    /** Unit of the value, for example "C" or "percent". */
    String unit;

    /** Timestamp in milliseconds since boot. */
    long timestampMs;

    /** True when the value is simulated rather than read from hardware. */
    boolean simulated;
}
