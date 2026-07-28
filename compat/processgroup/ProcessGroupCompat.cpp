/*
 * Copyright (C) 2026 The Klee Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#include <sys/types.h>

#include <string>
#include <vector>

// Some SM8450 camera binaries use the former unmangled entry point. Android 17
// keeps the implementation in libprocessgroup with C++ linkage, so export only
// the legacy ABI here and forward it to the platform implementation.
extern "C" bool callPlatformSetTaskProfiles(
        pid_t tid, const std::vector<std::string>& profiles, bool useFdCache)
        __asm__("_Z15SetTaskProfilesiRKNSt3__16vectorINS_12basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEEENS4_IS6_EEEEb");

extern "C" bool SetTaskProfiles(pid_t tid, const std::vector<std::string>& profiles,
                                  bool useFdCache) {
    return callPlatformSetTaskProfiles(tid, profiles, useFdCache);
}
