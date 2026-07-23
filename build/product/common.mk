#
# Copyright (C) 2026 The KleeUI Project
#
# SPDX-License-Identifier: Apache-2.0
#

ifndef KLEE_COMMON_PRODUCT_INCLUDED
KLEE_COMMON_PRODUCT_INCLUDED := true

KLEE_BUILD := true

PRODUCT_SOONG_NAMESPACES += \
    vendor/klee

$(call inherit-product, vendor/klee/build/product/version.mk)
$(call inherit-product-if-exists, vendor/klee/keys/keys.mk)

PRODUCT_PRODUCT_PROPERTIES += \
    ro.klee.name=Klee \
    ro.klee.platform.version=$(KLEE_PLATFORM_VERSION)

endif
