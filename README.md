# Klee platform configuration

This repository contains the distribution-owned product and build configuration
for Klee 1.0 on Android 17. Hardware support remains in the corresponding device,
kernel, and proprietary-vendor repositories.

## Device integration

The device product should use the device codename as `PRODUCT_NAME` and inherit
the Klee common product configuration:

```make
$(call inherit-product, vendor/klee/build/product/common.mk)

PRODUCT_NAME := cupid
PRODUCT_DEVICE := cupid
```

The device `BoardConfig.mk` may include the optional Klee board configuration:

```make
include vendor/klee/build/board/BoardConfigKlee.mk
```

## Inline GKI build

Klee's kernel integration is implemented independently and builds a standard
GKI-style kernel tree inside the Android invocation. A device enables it by
setting its source, image, and configuration:

```make
TARGET_KERNEL_SOURCE := kernel/xiaomi/sm8450
BOARD_KERNEL_IMAGE_NAME := Image
TARGET_KERNEL_CONFIG := \
    gki_defconfig \
    vendor/waipio_GKI.config \
    vendor/xiaomi_GKI.config \
    vendor/$(PRODUCT_DEVICE)_GKI.config
```

External modules use `TARGET_KERNEL_EXT_MODULE_ROOT` and
`TARGET_KERNEL_EXT_MODULES`. Generated modules and `modules.list` are staged in
`$(PRODUCT_OUT)/obj/KLEE_KERNEL_DIST` for the device packaging rules.

After sourcing the Android build environment, Klee accepts the compact lunch
form requested by the project:

```bash
source build/envsetup.sh
lunch cupid_userdebug
klee_build -j20
```

`lunch cupid_userdebug` is equivalent to selecting product `cupid`, release
`cp2a`, and variant `userdebug`. Set `KLEE_DEFAULT_RELEASE` before sourcing the
environment to use another Android release configuration.

Klee does not impose a maximum build parallelism. Pass a job count directly to
override any configured default. Inline kernel compilation uses the same
selected value:

```bash
klee_build -j32
mka bacon -j32
```

Set `KLEE_BUILD_JOBS` to provide a machine-specific default when the command
does not contain `-j` or `--jobs`. An explicit command-line value always wins:

```bash
export KLEE_BUILD_JOBS=20
klee_build
```

For memory-constrained hosts, `klee_build` disables Soong incremental action analysis by default to keep the configuration graph within RAM. Set `KLEE_SOONG_INCREMENTAL_ANALYSIS=true` to opt into the faster incremental-analysis mode when the host has sufficient memory.

If neither form supplies a job count, the Android build system chooses its own
parallelism. `klee_build` uses `droid` when no build goal is specified, while
`bacon` is a convenient alias for the complete `droid` build target.
