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

#include <aidl/android/devicehealth/MetricSample.h>
#include <android/binder_auto_utils.h>
#include <android/binder_ibinder.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace aidl::android::hardware::devicehealth {
class IDeviceHealth;
}

namespace aidl::android::devicehealth {

class ThermalProvider {
  public:
    ThermalProvider();
    ~ThermalProvider();

    ThermalProvider(const ThermalProvider&) = delete;
    ThermalProvider& operator=(const ThermalProvider&) = delete;

    /**
     * Samples the thermal HAL. When the HAL dies or is unavailable the call is skipped
     * until the reconnect backoff expires.
     */
    std::vector<MetricSample> getMetrics(std::string* error);

  private:
    static void onBinderDied(void* cookie);
    void dropConnectionLocked();

    std::mutex mutex_;
    ndk::SpAIBinder binder_;
    std::shared_ptr<aidl::android::hardware::devicehealth::IDeviceHealth> hal_;
    AIBinder_DeathRecipient* deathRecipient_ = nullptr;
    bool linked_ = false;
    std::atomic<bool> halDied_{false};
    int64_t nextRetryMs_ = 0;
    int64_t backoffMs_;
    int64_t failures_ = 0;
    bool unavailable_ = false;
};

}  // namespace aidl::android::devicehealth
