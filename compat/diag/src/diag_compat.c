/*
 * Copyright (C) 2026 The KleeUI Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#include "diag_lsm.h"
#include "diagpkt.h"
#include "log.h"

#include <stdlib.h>
#include <string.h>

bool Diag_LSM_Init(uint8_t *environment) {
    (void)environment;
    return true;
}

bool Diag_LSM_DeInit(void) {
    return true;
}

void *log_alloc(uint16_t code, unsigned int length) {
    (void)code;
    return calloc(1, length);
}

void log_commit(void *packet) {
    free(packet);
}

void log_free(void *packet) {
    free(packet);
}

bool log_status(uint16_t code) {
    (void)code;
    return false;
}

void diagpkt_tbl_reg(const diagpkt_user_table_type *table) {
    (void)table;
}

void *diagpkt_subsys_alloc(diagpkt_subsys_id_type id,
                          diagpkt_subsys_cmd_code_type code,
                          unsigned int length) {
    uint8_t *packet = calloc(1, length);

    if (packet == NULL) {
        return NULL;
    }

    if (length >= 4) {
        packet[0] = 75;
        packet[1] = id;
        packet[2] = (uint8_t)(code & 0xff);
        packet[3] = (uint8_t)(code >> 8);
    }
    return packet;
}

void diagpkt_commit(void *packet) {
    free(packet);
}

diagpkt_subsys_cmd_code_type diagpkt_subsys_get_cmd_code(void *packet) {
    const uint8_t *bytes = packet;

    if (bytes == NULL) {
        return 0;
    }
    return (diagpkt_subsys_cmd_code_type)(bytes[2] | (bytes[3] << 8));
}

void *diagpkt_err_rsp(diagpkt_cmd_code_type code, void *request,
                      uint16_t request_length) {
    const size_t response_length = (size_t)request_length + 1;
    uint8_t *response = calloc(1, response_length);

    if (response == NULL) {
        return NULL;
    }

    response[0] = code;
    if (request != NULL && request_length > 0) {
        memcpy(response + 1, request, request_length);
    }
    return response;
}
