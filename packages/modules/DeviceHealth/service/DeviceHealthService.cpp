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

#include "DeviceHealthService.h"

#include "MemoryProvider.h"

#include <android-base/logging.h>

#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <utility>

#define ATRACE_TAG ATRACE_TAG_AIDL
#include <cutils/trace.h>

namespace aidl::android::devicehealth {

namespace {

constexpr int32_t kInterfaceVersion = 2;
constexpr int32_t kCapabilities = 0x3;  // thermal + memory
constexpr int32_t kDefaultSamplingIntervalMs = 2000;
constexpr int32_t kMinSamplingIntervalMs = 500;
constexpr int32_t kMaxSamplingIntervalMs = 30000;
constexpr size_t kHistorySize = 60;

int64_t nowMs() {
    timespec ts{};
    clock_gettime(CLOCK_BOOTTIME, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000 + ts.tv_nsec / 1000000;
}

}  // namespace

DeviceHealthService::DeviceHealthService()
    : samplingIntervalMs_(kDefaultSamplingIntervalMs) {
    // Populate the cache before the service is registered so the first query has data.
    sampleOnce();
    sampler_ = std::thread([this] { samplingLoop(); });
}

DeviceHealthService::~DeviceHealthService() {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        running_ = false;
    }
    samplingCv_.notify_all();
    if (sampler_.joinable()) {
        sampler_.join();
    }
}

void DeviceHealthService::samplingLoop() {
    std::unique_lock<std::mutex> lock(mutex_);
    while (running_) {
        const auto interval = std::chrono::milliseconds(samplingIntervalMs_);
        if (samplingCv_.wait_for(lock, interval, [this] { return !running_.load(); })) {
            break;
        }
        lock.unlock();
        sampleOnce();
        lock.lock();
    }
}

void DeviceHealthService::sampleOnce() {
    ATRACE_BEGIN("devicehealth.sample");

    std::string thermalError;
    ATRACE_BEGIN("devicehealth.sample.thermal");
    auto thermal = thermalProvider_.getMetrics(&thermalError);
    ATRACE_END();

    ATRACE_BEGIN("devicehealth.sample.memory");
    auto memory = MemoryProvider::getMetrics();
    ATRACE_END();

    std::lock_guard<std::mutex> lock(mutex_);
    bool stale = false;

    if (thermal.empty()) {
        stale = true;
        if (hasThermalCache_) {
            thermal = thermalCache_;
        }
    } else {
        thermalCache_ = thermal;
        hasThermalCache_ = true;
    }

    if (memory.empty()) {
        stale = true;
        if (hasMemoryCache_) {
            memory = memoryCache_;
        }
    } else {
        memoryCache_ = memory;
        hasMemoryCache_ = true;
    }

    auto metrics = std::move(thermal);
    metrics.insert(metrics.end(), memory.begin(), memory.end());
    cachedMetrics_ = std::move(metrics);

    // Report the newest sample timestamp so callers can detect stale cached data.
    int64_t newestSampleMs = 0;
    for (const auto& metric : cachedMetrics_) {
        if (metric.timestampMs > newestSampleMs) {
            newestSampleMs = metric.timestampMs;
        }
    }
    lastSampleMs_ = newestSampleMs > 0 ? newestSampleMs : nowMs();
    stale_ = stale || cachedMetrics_.empty();
    if (stale_) {
        lastError_ = thermalError.empty() ? "Some metrics are stale" : thermalError;
    } else {
        lastError_.clear();
    }

    SampleBatch batch;
    batch.metrics = cachedMetrics_;
    batch.timestampMs = lastSampleMs_;
    batch.stale = stale_;
    history_.push_back(std::move(batch));
    while (history_.size() > kHistorySize) {
        history_.pop_front();
    }
    sampleCount_++;
    if (sampleCount_ % 30 == 0) {
        LOG(INFO) << "Sampled " << sampleCount_ << " batch(es), history " << history_.size()
                  << "/" << kHistorySize << ", interval " << samplingIntervalMs_ << "ms";
    }

    ATRACE_END();
}

ndk::ScopedAStatus DeviceHealthService::getSnapshot(Snapshot* out) {
    std::lock_guard<std::mutex> lock(mutex_);

    out->metrics = cachedMetrics_;
    out->timestampMs = lastSampleMs_;
    out->stale = stale_;
    if (out->metrics.empty()) {
        // Fall back to the newest non-empty batch retained in the ring buffer.
        for (auto it = history_.rbegin(); it != history_.rend(); ++it) {
            if (!it->metrics.empty()) {
                out->metrics = it->metrics;
                out->timestampMs = it->timestampMs;
                out->stale = true;
                break;
            }
        }
    }
    out->daemonVersion = kInterfaceVersion;
    out->capabilities = kCapabilities;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus DeviceHealthService::getDaemonInfo(DaemonInfo* out) {
    std::lock_guard<std::mutex> lock(mutex_);

    out->interfaceVersion = kInterfaceVersion;
    out->capabilities = kCapabilities;
    out->pid = static_cast<int32_t>(getpid());
    out->lastError = lastError_;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus DeviceHealthService::setSamplingIntervalMs(int32_t intervalMs,
                                                              int32_t* _aidl_return) {
    const int32_t applied =
            std::clamp(intervalMs, kMinSamplingIntervalMs, kMaxSamplingIntervalMs);
    {
        std::lock_guard<std::mutex> lock(mutex_);
        samplingIntervalMs_ = applied;
    }
    samplingCv_.notify_all();
    LOG(INFO) << "Sampling interval set to " << applied << "ms (requested " << intervalMs << "ms)";
    if (_aidl_return != nullptr) {
        *_aidl_return = applied;
    }
    return ndk::ScopedAStatus::ok();
}

}  // namespace aidl::android::devicehealth
