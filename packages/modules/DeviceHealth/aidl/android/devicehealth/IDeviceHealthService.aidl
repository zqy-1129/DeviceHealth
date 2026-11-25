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

import android.devicehealth.DaemonInfo;
import android.devicehealth.Snapshot;

/**
 * System-side interface exposed by devicehealthd.
 *
 * <p>The daemon is responsible for sampling, caching and recovering from HAL
 * failures. The framework is responsible for policy, events, notifications and
 * permissions.
 */
interface IDeviceHealthService {
    /**
     * Returns the latest snapshot.
     */
    Snapshot getSnapshot();

    /**
     * Returns diagnostic information about the daemon.
     */
    DaemonInfo getDaemonInfo();

    /**
     * Updates the sampling interval. The daemon clamps the value to a supported range
     * and returns the interval that is actually in use, in milliseconds.
     *
     * <p>Added in interface version 2.
     */
    int setSamplingIntervalMs(int intervalMs);
}
