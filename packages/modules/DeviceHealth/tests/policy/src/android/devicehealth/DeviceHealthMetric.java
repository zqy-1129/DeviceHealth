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

/** Host-test stand-in for the framework metric class. */
public final class DeviceHealthMetric {
    private final String mId;
    private final String mName;
    private final float mValue;
    private final String mUnit;
    private final long mTimestampMs;
    private final boolean mSimulated;

    public DeviceHealthMetric(String id, String name, float value, String unit,
            long timestampMs, boolean simulated) {
        mId = id;
        mName = name;
        mValue = value;
        mUnit = unit;
        mTimestampMs = timestampMs;
        mSimulated = simulated;
    }

    public String getId() {
        return mId;
    }

    public String getName() {
        return mName;
    }

    public float getValue() {
        return mValue;
    }

    public String getUnit() {
        return mUnit;
    }

    public long getTimestampMs() {
        return mTimestampMs;
    }

    public boolean isSimulated() {
        return mSimulated;
    }
}
