#
# Copyright (C) 2026 The KleeUI Project
#
# SPDX-License-Identifier: Apache-2.0
#

KLEE_PLATFORM_VERSION := 1.0
KLEE_BUILD_TYPE ?= UNOFFICIAL
KLEE_BUILD_DATE := $(shell date -u +%Y%m%d)
KLEE_BUILD_DEVICE := $(if $(TARGET_DEVICE),$(TARGET_DEVICE),$(TARGET_PRODUCT))
KLEE_BUILD_VERSION := Klee-$(KLEE_PLATFORM_VERSION)-$(KLEE_BUILD_DEVICE)-$(KLEE_BUILD_DATE)-$(KLEE_BUILD_TYPE)

PRODUCT_SYSTEM_PROPERTIES += \
    ro.klee.version=$(KLEE_PLATFORM_VERSION) \
    ro.klee.build.version=$(KLEE_BUILD_VERSION) \
    ro.klee.build.type=$(KLEE_BUILD_TYPE) \
    ro.klee.build.date=$(KLEE_BUILD_DATE) \
    ro.klee.device=$(KLEE_BUILD_DEVICE)
