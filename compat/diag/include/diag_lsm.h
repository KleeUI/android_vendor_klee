/*
 * Copyright (C) 2026 The KleeUI Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#ifndef KLEE_DIAG_COMPAT_DIAG_LSM_H
#define KLEE_DIAG_COMPAT_DIAG_LSM_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

bool Diag_LSM_Init(uint8_t *environment);
bool Diag_LSM_DeInit(void);

#ifdef __cplusplus
}
#endif

#endif  /* KLEE_DIAG_COMPAT_DIAG_LSM_H */
