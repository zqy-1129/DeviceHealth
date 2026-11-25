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

#include "ThermalProvider.h"

#include <aidl/android/hardware/devicehealth/IDeviceHealth.h>
#include <android-base/logging.h>
#include <android/binder_manager.h>

#include <time.h>

#include <algorithm>
#include <utility>

namespace aidl::android::devicehealth {

namespace {

constexpr char kHalInstance[] = "android.hardware.devicehealth.IDeviceHealth/default";
constexpr int64_t kInitialBackoffMs = 1000;
constexpr int64_t kMaxBackoffMs = 30000;

int64_t nowMs() {
    timespec ts{};
    clock_gettime(CLOCK_BOOTTIME, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000 + ts.tv_nsec / 1000000;
}

}  // namespace

ThermalProvider::ThermalProvider()
    : deathRecipient_(AIBinder_DeathRecipient_new(&ThermalProvider::onBinderDied)),
      backoffMs_(kInitialBackoffMs) {
    if (deathRecipient_ == nullptr) {
        LOG(ERROR) << "Unable to allocate HAL death recipient";
    }
}

ThermalProvider::~ThermalProvider() {
    std::lock_guard<std::mutex> lock(mutex_);
    dropConnectionLocked();
    if (deathRecipient_ != nullptr) {
        AIBinder_DeathRecipient_delete(deathRecipient_);
        deathRecipient_ = nullptr;
    }
}

void ThermalProvider::onBinderDied(void* cookie) {
    auto* provider = static_cast<ThermalProvider*>(cookie);
    provider->halDied_.store(true);
    LOG(WARNING) << "Device health HAL binder died; reconnecting";
}

void ThermalProvider::dropConnectionLocked() {
    // Unlinking a dead binder logs a DEAD_OBJECT error; the death notification already
    // removed the recipient in that case.
    if (binder_.get() != nullptr && linked_ && deathRecipient_ != nullptr &&
        AIBinder_isAlive(binder_.get())) {
        AIBinder_unlinkToDeath(binder_.get(), deathRecipient_, this);
    }
    linked_ = false;
    hal_.reset();
    binder_.set(nullptr);
}

std::vector<MetricSample> ThermalProvider::getMetrics(std::string* error) {
    if (halDied_.exchange(false)) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (binder_.get() != nullptr && AIBinder_isAlive(binder_.get())) {
            // A death notification raced with a reconnect; keep the live connection.
            LOG(INFO) << "Ignoring stale HAL death notification for a live connection";
        } else {
            dropConnectionLocked();
            if (!unavailable_) {
                unavailable_ = true;
                LOG(WARNING) << "Device health HAL is gone; scheduling reconnect";
            }
            // Retry immediately so a restarted HAL is picked up without extra delay.
            nextRetryMs_ = nowMs();
            backoffMs_ = kInitialBackoffMs;
        }
    }

    {
        std::lock_guard<std::mutex> lock(mutex_);
        const int64_t now = nowMs();
        if (now < nextRetryMs_) {
            if (error != nullptr) {
                *error = "HAL reconnect backoff, retry in " + std::to_string(nextRetryMs_ - now) +
                         "ms";
            }
            return {};
        }
    }

    auto fail = [&](const std::string& reason) {
        std::lock_guard<std::mutex> lock(mutex_);
        dropConnectionLocked();
        failures_++;
        const int64_t now = nowMs();
        nextRetryMs_ = now + backoffMs_;
        if (!unavailable_) {
            unavailable_ = true;
            LOG(WARNING) << "Device health HAL unavailable (" << reason << "), retry in "
                         << backoffMs_ << "ms";
        } else {
            LOG(DEBUG) << "Device health HAL still unavailable (" << reason << "), retry in "
                       << backoffMs_ << "ms";
        }
        backoffMs_ = std::min(backoffMs_ * 2, kMaxBackoffMs);
        if (error != nullptr) {
            *error = reason;
        }
        return std::vector<MetricSample>{};
    };

    std::shared_ptr<aidl::android::hardware::devicehealth::IDeviceHealth> hal;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        hal = hal_;
    }

    if (hal == nullptr) {
        ndk::SpAIBinder binder(AServiceManager_getService(kHalInstance));
        if (binder.get() == nullptr) {
            return fail("HAL service is not available");
        }
        auto connected = aidl::android::hardware::devicehealth::IDeviceHealth::fromBinder(binder);
        if (connected == nullptr) {
            return fail("Unable to create HAL proxy");
        }
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (hal_ != nullptr) {
                hal = hal_;
            } else {
                binder_ = binder;
                hal_ = connected;
                hal = connected;
                if (deathRecipient_ != nullptr) {
                    const binder_status_t linkStatus =
                            AIBinder_linkToDeath(binder_.get(), deathRecipient_, this);
                    linked_ = linkStatus == STATUS_OK;
                    if (linked_) {
                        LOG(INFO) << "Linked to device health HAL death recipient";
                    } else {
                        LOG(WARNING) << "Unable to link to HAL death recipient: " << linkStatus;
                    }
                }
            }
        }
    }

    std::vector<aidl::android::hardware::devicehealth::MetricSample> halMetrics;
    const ndk::ScopedAStatus status = hal->getMetrics(&halMetrics);
    if (!status.isOk()) {
        return fail(status.getDescription());
    }

    std::vector<MetricSample> metrics;
    metrics.reserve(halMetrics.size());
    for (const auto& halSample : halMetrics) {
        MetricSample sample;
        sample.id = halSample.id;
        sample.name = halSample.name;
        sample.value = halSample.value;
        sample.unit = halSample.unit;
        sample.timestampMs = halSample.timestampMs;
        sample.simulated = halSample.simulated;
        metrics.push_back(std::move(sample));
    }

    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (unavailable_) {
            LOG(INFO) << "Device health HAL recovered after " << failures_ << " failure(s)";
        }
        unavailable_ = false;
        failures_ = 0;
        backoffMs_ = kInitialBackoffMs;
        nextRetryMs_ = 0;
    }
    if (error != nullptr) {
        error->clear();
    }
    return metrics;
}

}  // namespace aidl::android::devicehealth
