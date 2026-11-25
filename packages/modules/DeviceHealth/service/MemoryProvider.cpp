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

#include "MemoryProvider.h"

#include <time.h>

#include <cstdint>
#include <cstdio>
#include <fstream>
#include <string>

namespace aidl::android::devicehealth {

namespace {

int64_t nowMs() {
    timespec ts{};
    clock_gettime(CLOCK_BOOTTIME, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000 + ts.tv_nsec / 1000000;
}

}  // namespace

std::vector<MetricSample> MemoryProvider::getMetrics() {
    std::ifstream meminfo("/proc/meminfo");
    if (!meminfo) {
        return {};
    }

    long long totalKb = 0;
    long long availableKb = -1;
    std::string line;
    while (std::getline(meminfo, line)) {
        if (sscanf(line.c_str(), "MemTotal: %lld kB", &totalKb) == 1) {
            continue;
        }
        if (sscanf(line.c_str(), "MemAvailable: %lld kB", &availableKb) == 1) {
            continue;
        }
    }

    if (totalKb <= 0 || availableKb < 0) {
        return {};
    }

    float pressure = 100.0f * static_cast<float>(totalKb - availableKb) /
                     static_cast<float>(totalKb);
    if (pressure < 0.0f) {
        pressure = 0.0f;
    } else if (pressure > 100.0f) {
        pressure = 100.0f;
    }

    MetricSample sample;
    sample.id = "memory.pressure";
    sample.name = "Memory";
    sample.value = pressure;
    sample.unit = "percent";
    sample.timestampMs = nowMs();
    sample.simulated = false;
    return {sample};
}

}  // namespace aidl::android::devicehealth
