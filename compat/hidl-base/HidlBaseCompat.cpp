/*
 * Copyright (C) 2026 The Klee Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

namespace klee::compat {

// Keep a translation unit so the linker emits the legacy shared-object name.
__attribute__((visibility("hidden"))) void preserveHidlBaseSoname() {}

}  // namespace klee::compat
