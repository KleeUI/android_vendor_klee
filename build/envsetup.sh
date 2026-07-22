#!/bin/bash
#
# Copyright (C) 2026 The KleeUI Project
#
# SPDX-License-Identifier: Apache-2.0
#

function klee_build()
{
    local jobs="${KLEE_BUILD_JOBS:-20}"

    if ! [[ "$jobs" =~ ^[0-9]+$ ]] || [[ "$jobs" -lt 1 ]]; then
        echo "KLEE_BUILD_JOBS must be a positive integer" 1>&2
        return 1
    fi

    if [[ "$jobs" -gt 20 ]]; then
        echo "Klee limits build parallelism to 20 jobs; using -j20." 1>&2
        jobs=20
    fi

    if [[ -z "$TARGET_PRODUCT" ]]; then
        echo "No product selected. Run lunch <device>_<variant> first." 1>&2
        return 1
    fi

    if [[ $# -eq 0 ]]; then
        set -- droid
    fi

    m -j"$jobs" "$@"
}

function klee_lunch()
{
    if [[ $# -lt 1 || $# -gt 2 ]]; then
        echo "Usage: klee_lunch <device> [user|userdebug|eng]" 1>&2
        return 1
    fi

    lunch "${1}_${2:-userdebug}"
}
