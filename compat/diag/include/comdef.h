/*
 * Copyright (C) 2026 The KleeUI Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#ifndef KLEE_DIAG_COMPAT_COMDEF_H
#define KLEE_DIAG_COMPAT_COMDEF_H

#include <stdint.h>

typedef uint8_t boolean;
typedef uint8_t byte;
typedef uint16_t word;
typedef uint32_t dword;

#ifndef FALSE
#define FALSE 0
#endif

#ifndef TRUE
#define TRUE 1
#endif

#endif  /* KLEE_DIAG_COMPAT_COMDEF_H */
