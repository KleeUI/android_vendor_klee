#
# Copyright (C) 2026 The KleeUI Project
#
# SPDX-License-Identifier: Apache-2.0
#

ifeq ($(KLEE_BUILD_KERNEL_FROM_SOURCE),true)

KLEE_KERNEL_BUILDER := vendor/klee/build/tools/build_kernel.py
ifneq ($(KLEE_KERNEL_BUILD_CONFIG),)
KLEE_KERNEL_CONFIG_INPUTS := \
    $(KLEE_KERNEL_PLATFORM_PATH)/$(KLEE_KERNEL_BUILD_CONFIG)
KLEE_KERNEL_SOURCE_INPUTS := \
    $(KLEE_KERNEL_PLATFORM_PATH)/build/build.sh \
    $(TARGET_KERNEL_SOURCE)/Makefile \
    $(KLEE_KERNEL_CONFIG_INPUTS)
else
KLEE_KERNEL_CONFIG_INPUTS := $(foreach config,$(TARGET_KERNEL_CONFIG), \
    $(wildcard $(TARGET_KERNEL_SOURCE)/arch/$(KLEE_KERNEL_ARCH)/configs/$(config)))
KLEE_KERNEL_CONFIG_INPUTS += $(TARGET_KERNEL_CONFIG_EXT)
KLEE_KERNEL_SOURCE_INPUTS := \
    $(TARGET_KERNEL_SOURCE)/Makefile \
    $(KLEE_KERNEL_CONFIG_INPUTS)
endif

.PHONY: klee-kernel-force
klee-kernel-force:

KLEE_KERNEL_BUILD_ARGUMENTS := \
    --source $(TARGET_KERNEL_SOURCE) \
    --out $(KLEE_KERNEL_OUT) \
    --dist $(KLEE_KERNEL_DIST) \
    --arch $(KLEE_KERNEL_ARCH) \
    --image $(KLEE_KERNEL_IMAGE_NAME)

ifneq ($(KLEE_KERNEL_BUILD_CONFIG),)
KLEE_KERNEL_BUILD_ARGUMENTS += \
    --platform-root $(KLEE_KERNEL_PLATFORM_PATH) \
    --build-config $(KLEE_KERNEL_BUILD_CONFIG) \
    $(foreach flag,$(TARGET_KERNEL_ADDITIONAL_FLAGS),--make-arg $(flag))
else
KLEE_KERNEL_BUILD_ARGUMENTS += \
    $(foreach config,$(TARGET_KERNEL_CONFIG),--config $(config)) \
    $(foreach config,$(TARGET_KERNEL_CONFIG_EXT),--config $(config)) \
    $(foreach flag,$(TARGET_KERNEL_ADDITIONAL_FLAGS),--make-arg $(flag))

ifneq ($(strip $(TARGET_KERNEL_EXT_MODULE_ROOT)),)
KLEE_KERNEL_BUILD_ARGUMENTS += \
    --external-module-root $(TARGET_KERNEL_EXT_MODULE_ROOT) \
    $(foreach module,$(TARGET_KERNEL_EXT_MODULES),--external-module $(module))
endif

ifeq ($(TARGET_NEEDS_DTBOIMAGE),true)
KLEE_KERNEL_BUILD_ARGUMENTS += --dtbo-target $(KLEE_KERNEL_DTBO_TARGET)
endif
endif

$(KLEE_KERNEL_IMAGE): klee-kernel-force $(KLEE_KERNEL_BUILDER) $(KLEE_KERNEL_SOURCE_INPUTS)
ifneq ($(KLEE_KERNEL_BUILD_CONFIG),)
	@echo "Building Klee kernel platform from $(KLEE_KERNEL_BUILD_CONFIG)"
else
	@echo "Building Klee kernel from $(TARGET_KERNEL_SOURCE)"
endif
	$(hide) KLEE_KERNEL_JOBS="$(KLEE_KERNEL_JOBS)" \
	    LLVM_AOSP_PREBUILTS_VERSION="$(LLVM_AOSP_PREBUILTS_VERSION)" \
	    python3 $(KLEE_KERNEL_BUILDER) $(KLEE_KERNEL_BUILD_ARGUMENTS)

ifneq ($(strip $(BOARD_PREBUILT_DTBOIMAGE)),)
$(BOARD_PREBUILT_DTBOIMAGE): $(KLEE_KERNEL_IMAGE)
	@test -f "$@" || { echo "Missing generated DTBO image: $@"; exit 1; }
endif

.PHONY: klee-kernel
klee-kernel: $(KLEE_KERNEL_IMAGE)

endif
