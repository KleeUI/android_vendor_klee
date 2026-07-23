#!/bin/bash
#
# Copyright (C) 2026 The KleeUI Project
#
# SPDX-License-Identifier: Apache-2.0
#

function klee_build()
{
    local jobs="${KLEE_BUILD_JOBS:-}"
    local has_explicit_jobs=
    local has_goal=
    local takes_job_value=

    if [[ -n "$jobs" ]] &&
            { ! [[ "$jobs" =~ ^[0-9]+$ ]] || [[ "$jobs" -lt 1 ]]; }; then
        echo "KLEE_BUILD_JOBS must be a positive integer" 1>&2
        return 1
    fi

    if [[ -z "$TARGET_PRODUCT" ]]; then
        echo "No product selected. Run lunch <device>_<variant> first." 1>&2
        return 1
    fi

    for argument in "$@"; do
        if [[ -n "$takes_job_value" ]]; then
            takes_job_value=
            continue
        fi

        case "$argument" in
            -j|--jobs)
                has_explicit_jobs=true
                takes_job_value=true
                ;;
            -j*|--jobs=*)
                has_explicit_jobs=true
                ;;
            -*)
                ;;
            *)
                has_goal=true
                ;;
        esac
    done

    if [[ -z "$has_goal" ]]; then
        set -- "$@" droid
    fi

    if [[ -n "$jobs" && -z "$has_explicit_jobs" ]]; then
        set -- -j"$jobs" "$@"
    fi

    m "$@"
}

function mka()
{
    m "$@"
}

function klee_lunch()
{
    if [[ $# -lt 1 || $# -gt 2 ]]; then
        echo "Usage: klee_lunch <device> [user|userdebug|eng]" 1>&2
        return 1
    fi

    lunch "${1}_${2:-userdebug}"
}
