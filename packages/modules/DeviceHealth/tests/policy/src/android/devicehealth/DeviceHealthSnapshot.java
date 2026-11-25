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

/** Host-test stand-in for the framework snapshot severity constants. */
public final class DeviceHealthSnapshot {
    public static final int SEVERITY_NORMAL = 0;
    public static final int SEVERITY_WARNING = 1;
    public static final int SEVERITY_CRITICAL = 2;
    public static final int SEVERITY_STALE = 3;
    public static final int SEVERITY_UNKNOWN = 4;

    private DeviceHealthSnapshot() {
    }
}
