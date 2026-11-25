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

#pragma once

#include <aidl/android/devicehealth/BnDeviceHealthService.h>

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "ThermalProvider.h"

namespace aidl::android::devicehealth {

class DeviceHealthService : public BnDeviceHealthService {
  public:
    DeviceHealthService();
    ~DeviceHealthService() override;

    ndk::ScopedAStatus getSnapshot(Snapshot* out) override;
    ndk::ScopedAStatus getDaemonInfo(DaemonInfo* out) override;
    ndk::ScopedAStatus setSamplingIntervalMs(int32_t intervalMs,
                                             int32_t* _aidl_return) override;

  private:
    struct SampleBatch {
        std::vector<MetricSample> metrics;
        int64_t timestampMs = 0;
        bool stale = true;
    };

    void samplingLoop();
    void sampleOnce();

    std::mutex mutex_;
    std::condition_variable samplingCv_;
    std::atomic<bool> running_{true};
    std::thread sampler_;

    int32_t samplingIntervalMs_;
    int64_t sampleCount_ = 0;
    std::deque<SampleBatch> history_;

    std::vector<MetricSample> thermalCache_;
    std::vector<MetricSample> memoryCache_;
    bool hasThermalCache_ = false;
    bool hasMemoryCache_ = false;

    std::vector<MetricSample> cachedMetrics_;
    int64_t lastSampleMs_ = 0;
    bool stale_ = true;
    std::string lastError_;

    ThermalProvider thermalProvider_;
};

}  // namespace aidl::android::devicehealth
