#
# Copyright (C) 2026 The KleeUI Project
#
# SPDX-License-Identifier: Apache-2.0
#

ifndef KLEE_BOARD_CONFIG_INCLUDED
KLEE_BOARD_CONFIG_INCLUDED := true

# Projects may provide release signing and AVB configuration without carrying
# private keys in the public platform repository.
-include vendor/klee/keys/BoardConfig.mk

include vendor/klee/build/kernel/BoardConfigKernel.mk

endif
