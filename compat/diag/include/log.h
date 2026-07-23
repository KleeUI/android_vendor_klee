/*
 * Copyright (C) 2026 The KleeUI Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#ifndef KLEE_DIAG_COMPAT_LOG_H
#define KLEE_DIAG_COMPAT_LOG_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

void *log_alloc(uint16_t code, unsigned int length);
void log_commit(void *packet);
void log_free(void *packet);
bool log_status(uint16_t code);

#ifdef __cplusplus
}
#endif

#endif  /* KLEE_DIAG_COMPAT_LOG_H */
