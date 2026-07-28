/*
 * Copyright (C) 2026 The Klee Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#include <stdint.h>

static intptr_t megviiUnavailable(void) {
    return -1;
}

static char* megviiUnavailableText(void) {
    return "unsupported";
}

// Cupid's camera provider imports this legacy function table. Keep every
// algorithm entry callable while reporting that the optional engine is absent.
void* mg_facepp[] = {
        (void*)megviiUnavailable,
        (void*)megviiUnavailable,
        (void*)megviiUnavailable,
        (void*)megviiUnavailableText,
        (void*)megviiUnavailableText,
        (void*)megviiUnavailable,
        (void*)megviiUnavailable,
        (void*)megviiUnavailable,
        (void*)megviiUnavailable,
        (void*)megviiUnavailable,
        (void*)megviiUnavailable,
        (void*)megviiUnavailable,
        (void*)megviiUnavailable,
        (void*)megviiUnavailable,
        (void*)megviiUnavailable,
        (void*)megviiUnavailable,
        (void*)megviiUnavailable,
        (void*)megviiUnavailable,
        (void*)megviiUnavailable,
        (void*)megviiUnavailable,
        (void*)megviiUnavailable,
        (void*)megviiUnavailable,
        (void*)megviiUnavailable,
        (void*)megviiUnavailable,
        (void*)megviiUnavailable,
        (void*)megviiUnavailable,
        (void*)megviiUnavailable,
};
