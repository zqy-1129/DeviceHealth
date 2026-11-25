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

#include <android-base/logging.h>
#include <android/binder_interface_utils.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>

#include <cstdlib>

using aidl::android::devicehealth::DeviceHealthService;

int main() {
    ABinderProcess_setThreadPoolMaxThreadCount(0);

    auto service = ndk::SharedRefBase::make<DeviceHealthService>();
    constexpr char kServiceName[] = "devicehealthd";
    const binder_status_t status =
            AServiceManager_addService(service->asBinder().get(), kServiceName);
    if (status != STATUS_OK) {
        LOG(ERROR) << "Failed to register " << kServiceName << ": " << status;
        return EXIT_FAILURE;
    }
    LOG(INFO) << "Registered " << kServiceName;

    ABinderProcess_joinThreadPool();
    return EXIT_FAILURE;
}
