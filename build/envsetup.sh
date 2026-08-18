#!/bin/bash
#
# Copyright (C) 2026 The KleeUI Project
#
# SPDX-License-Identifier: Apache-2.0
#

function m()
{
    local selected_jobs="${KLEE_KERNEL_JOBS:-}"
    local takes_job_value=
    local parse_options=true
    local argument

    # Soong consumes -j before it creates the command environment.  Capture
    # the same value here so source-built kernels and external modules use the
    # parallelism requested for the Android build.
    for argument in "$@"; do
        if [[ -n "$takes_job_value" ]]; then
            selected_jobs="$argument"
            takes_job_value=
            continue
        fi

        if [[ -n "$parse_options" && "$argument" == -- ]]; then
            parse_options=
            continue
        fi
        if [[ -z "$parse_options" ]]; then
            continue
        fi

        case "$argument" in
            -j|--jobs)
                selected_jobs=
                takes_job_value=true
                ;;
            -j*)
                selected_jobs="${argument#-j}"
                ;;
            --jobs=*)
                selected_jobs="${argument#--jobs=}"
                ;;
        esac
    done

    if [[ -n "$takes_job_value" ]]; then
        echo "A numeric job count must follow -j or --jobs" 1>&2
        return 1
    fi

    if [[ -n "$selected_jobs" ]] &&
            { ! [[ "$selected_jobs" =~ ^[0-9]+$ ]] ||
                [[ "$selected_jobs" -lt 1 ]]; }; then
        echo "Build jobs must be a positive integer" 1>&2
        return 1
    fi

    KLEE_KERNEL_JOBS="$selected_jobs" command m "$@"
}

function klee_build()
{
    local jobs="${KLEE_BUILD_JOBS:-}"
    local selected_jobs="$jobs"
    local has_explicit_jobs=
    local has_goal=
    local takes_job_value=
    local parse_options=true

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
            selected_jobs="$argument"
            takes_job_value=
            continue
        fi

        if [[ -n "$parse_options" && "$argument" == -- ]]; then
            parse_options=
            continue
        fi
        if [[ -z "$parse_options" ]]; then
            has_goal=true
            continue
        fi

        case "$argument" in
            -j|--jobs)
                has_explicit_jobs=true
                selected_jobs=
                takes_job_value=true
                ;;
            -j*)
                has_explicit_jobs=true
                selected_jobs="${argument#-j}"
                ;;
            --jobs=*)
                has_explicit_jobs=true
                selected_jobs="${argument#--jobs=}"
                ;;
            -*)
                ;;
            *)
                has_goal=true
                ;;
        esac
    done

    if [[ -n "$takes_job_value" ]]; then
        echo "A numeric job count must follow -j or --jobs" 1>&2
        return 1
    fi

    if [[ -n "$selected_jobs" ]] &&
            { ! [[ "$selected_jobs" =~ ^[0-9]+$ ]] ||
                [[ "$selected_jobs" -lt 1 ]]; }; then
        echo "Build jobs must be a positive integer" 1>&2
        return 1
    fi

    if [[ -z "$has_goal" ]]; then
        set -- "$@" droid
    fi

    if [[ -n "$jobs" && -z "$has_explicit_jobs" ]]; then
        set -- -j"$jobs" "$@"
    fi

    if [[ -n "${KLEE_SOONG_INCREMENTAL_ANALYSIS:-}" ]]; then
        SOONG_INCREMENTAL_ANALYSIS="$KLEE_SOONG_INCREMENTAL_ANALYSIS" m "$@"
    else
        m "$@"
    fi
}

function mka()
{
    klee_build "$@"
}

function klee_lunch()
{
    if [[ $# -lt 1 || $# -gt 2 ]]; then
        echo "Usage: klee_lunch <device> [user|userdebug|eng]" 1>&2
        return 1
    fi

    lunch "${1}_${2:-userdebug}"
}
