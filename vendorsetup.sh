#!/bin/bash
#
# Copyright (C) 2026 The KleeUI Project
#
# SPDX-License-Identifier: Apache-2.0
#

export KLEE_DEFAULT_RELEASE="${KLEE_DEFAULT_RELEASE:-cp2a}"

if [ -f vendor/klee/build/envsetup.sh ]; then
    . vendor/klee/build/envsetup.sh
fi
