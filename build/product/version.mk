#
# Copyright (C) 2026 Klee-UI
#
# SPDX-License-Identifier: Apache-2.0
#

KLEE_Version := 1.0
KLEE_DEVICE=$(shell echo "$(TARGET_PRODUCT)" | cut -d'_' -f 2,3)

ifeq ($(KLEE_RELEASE), true)
KLEE_BUILD_VARIANT := release
else
KLEE_BUILD_VARIANT := experimental
endif

KLEE_VERSION := Klee-UI-$(KLEE_Version)-$(KLEE_DEVICE)-$(shell date -u +%Y%m%d)-$(KLEE_BUILD_VARIANT)

PRODUCT_SYSTEM_DEFAULT_PROPERTIES += \
    ro.klee.version=$(KLEE_VERSION)

PRODUCT_SYSTEM_DEFAULT_PROPERTIES += \
    ro.klee.version=$(KLEE_Version) \
    ro.klee.build.variant=$(KLEE_BUILD_VARIANT) \
    ro.klee.device=$(KLEE_DEVICE)