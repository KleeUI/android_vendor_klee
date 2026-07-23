/*
 * Copyright (C) 2026 The KleeUI Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#ifndef KLEE_DIAG_COMPAT_DIAGPKT_H
#define KLEE_DIAG_COMPAT_DIAGPKT_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef uint8_t diagpkt_cmd_code_type;
typedef uint8_t diagpkt_subsys_id_type;
typedef uint16_t diagpkt_subsys_cmd_code_type;

typedef struct {
    uint32_t cmd_code_lo;
    uint32_t cmd_code_hi;
    void *(*func_ptr)(void *request, uint16_t length);
} diagpkt_user_table_entry_type;

typedef struct {
    uint16_t delay_flag;
    uint16_t cmd_code;
    uint32_t subsysid;
    uint32_t count;
    uint16_t proc_id;
    const diagpkt_user_table_entry_type *user_table;
} diagpkt_user_table_type;

void diagpkt_tbl_reg(const diagpkt_user_table_type *table);
void *diagpkt_subsys_alloc(diagpkt_subsys_id_type id,
                          diagpkt_subsys_cmd_code_type code,
                          unsigned int length);
void diagpkt_commit(void *packet);
diagpkt_subsys_cmd_code_type diagpkt_subsys_get_cmd_code(void *packet);
void *diagpkt_err_rsp(diagpkt_cmd_code_type code, void *request,
                      uint16_t request_length);

#define DIAGPKT_DISPATCH_TABLE_REGISTER(subsystem, entries)                \
    do {                                                                   \
        static const diagpkt_user_table_type entries##_table = {           \
            0, 0xff, (subsystem),                                          \
            sizeof(entries) / sizeof((entries)[0]), 0, (entries),          \
        };                                                                 \
        diagpkt_tbl_reg(&entries##_table);                                 \
    } while (0)

#ifdef __cplusplus
}
#endif

#endif  /* KLEE_DIAG_COMPAT_DIAGPKT_H */
