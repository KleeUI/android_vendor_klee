#
# Copyright (C) 2026 The KleeUI Project
#
# SPDX-License-Identifier: Apache-2.0
#

ifeq ($(KLEE_BUILD_KERNEL_FROM_SOURCE),true)

KLEE_KERNEL_BUILDER := vendor/klee/build/tools/build_kernel.py

# Klee owns the complete source-kernel transaction. Follow links while
# enumerating source trees and also retain the link paths and their resolved
# targets. A retargeted device DTS or external-module link must invalidate the
# bundle instead of silently reusing artifacts from the previous source tree.
define klee-kernel-tracked-inputs
$(sort \
    $(shell find $(1) \( -type f -o -type l \) \
        -not -path '*/.git/*' 2>/dev/null) \
    $(shell find -L $(1) -type f \
        -not -path '*/.git/*' 2>/dev/null) \
    $(shell find -L $(1) -type f \
        -not -path '*/.git/*' -exec realpath {} + 2>/dev/null) \
    $(shell find $(1) -type l \
        -not -path '*/.git/*' -exec realpath {} + 2>/dev/null))
endef

ifneq ($(KLEE_KERNEL_BUILD_CONFIG),)
KLEE_KERNEL_CONFIG_INPUTS := \
    $(KLEE_KERNEL_PLATFORM_PATH)/$(KLEE_KERNEL_BUILD_CONFIG) \
    $(wildcard $(KLEE_KERNEL_PLATFORM_PATH)/common/build.config*)
KLEE_KERNEL_SOURCE_INPUTS := \
    $(call klee-kernel-tracked-inputs,$(TARGET_KERNEL_SOURCE)) \
    $(call klee-kernel-tracked-inputs,$(KLEE_KERNEL_PLATFORM_PATH)/build) \
    $(KLEE_KERNEL_CONFIG_INPUTS)
else
KLEE_KERNEL_CONFIG_INPUTS := $(foreach config,$(TARGET_KERNEL_CONFIG), \
    $(wildcard $(TARGET_KERNEL_SOURCE)/arch/$(KLEE_KERNEL_ARCH)/configs/$(config)))
KLEE_KERNEL_CONFIG_INPUTS += $(TARGET_KERNEL_CONFIG_EXT)
KLEE_KERNEL_SOURCE_INPUTS := \
    $(call klee-kernel-tracked-inputs,$(TARGET_KERNEL_SOURCE)) \
    $(KLEE_KERNEL_CONFIG_INPUTS)
endif

ifneq ($(strip $(KLEE_SOURCE_DTB_REQUIRED)),)
KLEE_KERNEL_SOURCE_INPUTS += \
    $(call klee-kernel-tracked-inputs,$(KLEE_KERNEL_SOURCE_DTB_ROOT))
endif

# External Qualcomm modules are part of the same kernel bundle. Track every
# selected tree, including links and their real targets, so incremental builds
# cannot publish a stale module beside a newly built Image.
ifneq ($(strip $(TARGET_KERNEL_EXT_MODULE_ROOT)),)
KLEE_KERNEL_EXTERNAL_MODULE_INPUTS := \
    $(foreach module,$(TARGET_KERNEL_EXT_MODULES), \
        $(call klee-kernel-tracked-inputs, \
            $(TARGET_KERNEL_EXT_MODULE_ROOT)/$(module)))
KLEE_KERNEL_SOURCE_INPUTS += $(KLEE_KERNEL_EXTERNAL_MODULE_INPUTS)
endif

ifneq ($(KLEE_KERNEL_DT_LAYOUT),)
KLEE_KERNEL_DT_TOOL_INPUTS := \
    $(call klee-kernel-tracked-inputs,kernel_platform/external/dtc) \
    prebuilts/misc/linux-x86/libufdt/mkdtimg
KLEE_KERNEL_SOURCE_INPUTS += \
    $(KLEE_KERNEL_DT_LAYOUT) \
    $(call klee-kernel-tracked-inputs,$(KLEE_KERNEL_DT_LAYOUT)) \
    $(KLEE_KERNEL_DT_TOOL_INPUTS)
endif

KLEE_KERNEL_SOURCE_INPUTS := $(sort $(KLEE_KERNEL_SOURCE_INPUTS))

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
else
KLEE_KERNEL_UAPI_SOURCE := $(KLEE_KERNEL_OUT)/usr
KLEE_KERNEL_BUILD_ARGUMENTS += \
    $(foreach config,$(TARGET_KERNEL_CONFIG),--config $(config)) \
    $(foreach config,$(TARGET_KERNEL_CONFIG_EXT),--config $(config)) \
    $(foreach flag,$(TARGET_KERNEL_ADDITIONAL_FLAGS),--make-arg $(flag))
endif

ifneq ($(strip $(TARGET_KERNEL_EXT_MODULE_ROOT)),)
KLEE_KERNEL_BUILD_ARGUMENTS += \
    --external-module-root $(TARGET_KERNEL_EXT_MODULE_ROOT) \
    $(foreach module,$(TARGET_KERNEL_EXT_MODULES),--external-module $(module))
endif

ifneq ($(strip $(CUPID_KERNEL_MODULE_PROVENANCE_FILE)),)
KLEE_KERNEL_BUILD_ARGUMENTS += \
    --retained-provenance $(CUPID_KERNEL_MODULE_PROVENANCE_FILE)
endif

ifneq ($(strip $(CUPID_QUALCOMM_SOURCE_MANIFEST)),)
KLEE_KERNEL_BUILD_ARGUMENTS += \
    --source-manifest $(CUPID_QUALCOMM_SOURCE_MANIFEST)
endif

ifneq ($(strip $(KLEE_SOURCE_DTB_REQUIRED)),)
KLEE_KERNEL_BUILD_ARGUMENTS += \
    --dtb-source-root $(KLEE_KERNEL_SOURCE_DTB_ROOT) \
    $(foreach marker,$(KLEE_KERNEL_DTB_SOURCE_MARKERS),--dtb-source-marker $(marker))
endif

KLEE_KERNEL_BUILDS_DTB :=
KLEE_KERNEL_BUILDS_DTBO :=
ifneq ($(KLEE_KERNEL_DT_LAYOUT),)
KLEE_KERNEL_BUILDS_DTB := true
KLEE_KERNEL_BUILDS_DTBO := true
KLEE_KERNEL_BUILD_ARGUMENTS += \
    --dt-layout $(KLEE_KERNEL_DT_LAYOUT) \
    --dtb-output $(KLEE_KERNEL_DTB_IMAGE) \
    --dtbo-output $(KLEE_KERNEL_DTBO_IMAGE)
ifneq ($(strip $(BOARD_DTBOIMG_PARTITION_SIZE)),)
KLEE_KERNEL_BUILD_ARGUMENTS += --dtbo-max-size $(BOARD_DTBOIMG_PARTITION_SIZE)
endif
else
ifneq ($(strip $(TARGET_KERNEL_DTB_BASES)),)
KLEE_KERNEL_BUILDS_DTB := true
KLEE_KERNEL_BUILD_ARGUMENTS += \
    $(foreach base,$(TARGET_KERNEL_DTB_BASES),--dtb-base $(base)) \
    $(foreach overlay,$(TARGET_KERNEL_DTB_OVERLAYS),--dtb-overlay $(overlay)) \
    --dtb-output $(KLEE_KERNEL_DTB_IMAGE)
endif

ifeq ($(TARGET_NEEDS_DTBOIMAGE),true)
ifeq ($(BOARD_PREBUILT_DTBOIMAGE),$(KLEE_KERNEL_DTBO_IMAGE))
KLEE_KERNEL_BUILDS_DTBO := true
KLEE_KERNEL_BUILD_ARGUMENTS += --dtbo-target $(KLEE_KERNEL_DTBO_TARGET)
ifneq ($(strip $(BOARD_DTBOIMG_PARTITION_SIZE)),)
KLEE_KERNEL_BUILD_ARGUMENTS += --dtbo-max-size $(BOARD_DTBOIMG_PARTITION_SIZE)
endif
endif
endif
endif

ifeq ($(KLEE_KERNEL_SKIP_PLATFORM_DTBO),true)
KLEE_KERNEL_BUILD_ARGUMENTS += --skip-platform-dtbo
endif

KLEE_KERNEL_BUILD_ARGUMENTS += \
    $(foreach module,$(KLEE_KERNEL_MODULES),--required-module $(module)) \
    --stamp $(KLEE_KERNEL_BUNDLE_STAMP)

KLEE_KERNEL_BUNDLE_OUTPUTS := \
    $(KLEE_KERNEL_IMAGE) \
    $(KLEE_KERNEL_MODULE_PATHS)
ifeq ($(KLEE_KERNEL_BUILDS_DTB),true)
KLEE_KERNEL_BUNDLE_OUTPUTS += $(KLEE_KERNEL_DTB_IMAGE)
endif
ifeq ($(KLEE_KERNEL_BUILDS_DTBO),true)
KLEE_KERNEL_BUNDLE_OUTPUTS += $(KLEE_KERNEL_DTBO_IMAGE)
endif
KLEE_KERNEL_BUNDLE_OUTPUTS := $(sort $(KLEE_KERNEL_BUNDLE_OUTPUTS))

# build_kernel.py validates every declared output and publishes the stamp only
# after the complete bundle is fresh. Kati therefore sees one producer for
# Image, every requested module, dtb.img and dtbo.img; parallel packaging can
# neither start a second kernel build nor consume a half-published dist tree.
$(KLEE_KERNEL_BUNDLE_STAMP): .KATI_IMPLICIT_OUTPUTS := $(KLEE_KERNEL_BUNDLE_OUTPUTS)
$(KLEE_KERNEL_BUNDLE_STAMP): $(KLEE_KERNEL_BUILDER) $(KLEE_KERNEL_SOURCE_INPUTS)
ifneq ($(KLEE_KERNEL_BUILD_CONFIG),)
	@echo "Building Klee kernel platform from $(KLEE_KERNEL_BUILD_CONFIG)"
else
	@echo "Building Klee kernel from $(TARGET_KERNEL_SOURCE)"
endif
	$(hide) KLEE_KERNEL_JOBS="$(KLEE_KERNEL_JOBS)" \
	    LLVM_AOSP_PREBUILTS_VERSION="$(LLVM_AOSP_PREBUILTS_VERSION)" \
	    python3 $(KLEE_KERNEL_BUILDER) $(KLEE_KERNEL_BUILD_ARGUMENTS)

KLEE_LEGACY_KERNEL_UAPI := $(TARGET_OUT_INTERMEDIATES)/KERNEL_OBJ/usr
$(KLEE_LEGACY_KERNEL_UAPI): $(KLEE_KERNEL_BUNDLE_STAMP)
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

# Keep the merged source DTB dependency explicit so vendor_boot and recovery
# cannot package a stale image after the device layout changes.
ifeq ($(KLEE_KERNEL_BUILDS_DTB),true)
KLEE_INSTALLED_DTBIMAGE_TARGET := $(PRODUCT_OUT)/dtb.img
$(KLEE_INSTALLED_DTBIMAGE_TARGET): $(KLEE_KERNEL_DTB_IMAGE)
	@echo "Installing Klee DTB image: $@"
	$(copy-file-to-target)
endif

.PHONY: klee-kernel
klee-kernel: $(KLEE_KERNEL_BUNDLE_STAMP)

endif
