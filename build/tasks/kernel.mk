#
# Copyright (C) 2026 The KleeUI Project
#
# SPDX-License-Identifier: Apache-2.0
#

ifeq ($(KLEE_BUILD_KERNEL_FROM_SOURCE),true)

KLEE_KERNEL_BUILDER := vendor/klee/build/tools/build_kernel.py
ifneq ($(KLEE_KERNEL_BUILD_CONFIG),)
KLEE_KERNEL_CONFIG_INPUTS := \
    $(KLEE_KERNEL_PLATFORM_PATH)/$(KLEE_KERNEL_BUILD_CONFIG) \
    $(wildcard $(KLEE_KERNEL_PLATFORM_PATH)/common/build.config*)
KLEE_KERNEL_SOURCE_INPUTS := \
    $(sort \
        $(shell find $(TARGET_KERNEL_SOURCE) -type f \
            -not -path '*/.git/*' 2>/dev/null) \
        $(shell find $(KLEE_KERNEL_PLATFORM_PATH)/build -type f \
            -not -path '*/.git/*' 2>/dev/null) \
        $(KLEE_KERNEL_CONFIG_INPUTS))
else
KLEE_KERNEL_CONFIG_INPUTS := $(foreach config,$(TARGET_KERNEL_CONFIG), \
    $(wildcard $(TARGET_KERNEL_SOURCE)/arch/$(KLEE_KERNEL_ARCH)/configs/$(config)))
KLEE_KERNEL_CONFIG_INPUTS += $(TARGET_KERNEL_CONFIG_EXT)
KLEE_KERNEL_SOURCE_INPUTS := \
    $(TARGET_KERNEL_SOURCE)/Makefile \
    $(KLEE_KERNEL_CONFIG_INPUTS)
endif

KLEE_KERNEL_BUILD_ARGUMENTS := \
    --source $(TARGET_KERNEL_SOURCE) \
    --out $(KLEE_KERNEL_OUT) \
    --dist $(KLEE_KERNEL_DIST) \
    --arch $(KLEE_KERNEL_ARCH) \
    --image $(KLEE_KERNEL_IMAGE_NAME)

ifneq ($(KLEE_KERNEL_BUILD_CONFIG),)
KLEE_KERNEL_UAPI_SOURCE := $(KLEE_KERNEL_OUT)/kernel_uapi_headers/usr
KLEE_KERNEL_BUILD_ARGUMENTS += \
    --platform-root $(KLEE_KERNEL_PLATFORM_PATH) \
    --build-config $(KLEE_KERNEL_BUILD_CONFIG) \
    $(foreach flag,$(TARGET_KERNEL_ADDITIONAL_FLAGS),--make-arg $(flag))
ifeq ($(KLEE_KERNEL_SKIP_PLATFORM_DTBO),true)
KLEE_KERNEL_BUILD_ARGUMENTS += --skip-platform-dtbo
endif
else
KLEE_KERNEL_UAPI_SOURCE := $(KLEE_KERNEL_OUT)/usr
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

$(KLEE_KERNEL_IMAGE): $(KLEE_KERNEL_BUILDER) $(KLEE_KERNEL_SOURCE_INPUTS)
ifneq ($(KLEE_KERNEL_BUILD_CONFIG),)
	@echo "Building Klee kernel platform from $(KLEE_KERNEL_BUILD_CONFIG)"
else
	@echo "Building Klee kernel from $(TARGET_KERNEL_SOURCE)"
endif
	$(hide) KLEE_KERNEL_JOBS="$(KLEE_KERNEL_JOBS)" \
	    LLVM_AOSP_PREBUILTS_VERSION="$(LLVM_AOSP_PREBUILTS_VERSION)" \
	    python3 $(KLEE_KERNEL_BUILDER) $(KLEE_KERNEL_BUILD_ARGUMENTS)

# Qualcomm's build/build.sh publishes kernel modules beside the image as
# side effects. Register those files as make targets so image packaging can
# depend on a clean kernel dist directory without requiring a second build.
ifneq ($(strip $(KLEE_KERNEL_MODULE_PATHS)),)
$(KLEE_KERNEL_MODULE_PATHS): $(KLEE_KERNEL_IMAGE)
	@test -f "$@" || { echo "Missing generated kernel module: $@"; exit 1; }
endif

KLEE_LEGACY_KERNEL_UAPI := $(TARGET_OUT_INTERMEDIATES)/KERNEL_OBJ/usr
$(KLEE_LEGACY_KERNEL_UAPI): $(KLEE_KERNEL_IMAGE)
	@echo "Installing Klee kernel UAPI headers: $@"
	@test -d "$(KLEE_KERNEL_UAPI_SOURCE)" || { \
	    echo "Missing generated kernel UAPI headers: $(KLEE_KERNEL_UAPI_SOURCE)"; \
	    exit 1; \
	}
	$(hide) rm -rf "$@"
	$(hide) mkdir -p "$(dir $@)"
	$(hide) cp -a "$(KLEE_KERNEL_UAPI_SOURCE)" "$@"

ifneq ($(strip $(INSTALLED_KERNEL_TARGET)),)
$(INSTALLED_KERNEL_TARGET): $(KLEE_KERNEL_IMAGE)
	@echo "Installing Klee kernel image: $@"
	$(copy-file-to-target)
endif

ifeq ($(BOARD_PREBUILT_DTBOIMAGE),$(KLEE_KERNEL_DTBO_IMAGE))
$(BOARD_PREBUILT_DTBOIMAGE): $(KLEE_KERNEL_IMAGE)
	@test -f "$@" || { echo "Missing generated DTBO image: $@"; exit 1; }
endif

.PHONY: klee-kernel
klee-kernel: $(KLEE_KERNEL_IMAGE)

endif
