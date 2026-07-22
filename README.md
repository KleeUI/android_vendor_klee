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

After sourcing the Android build environment, Klee accepts the compact lunch
form requested by the project:

```bash
source build/envsetup.sh
lunch cupid_userdebug
klee_build
```

`lunch cupid_userdebug` is equivalent to selecting product `cupid`, release
`cp2a`, and variant `userdebug`. Set `KLEE_DEFAULT_RELEASE` before sourcing the
environment to use another Android release configuration.

`klee_build` defaults to 20 jobs and never exceeds 20 jobs.
