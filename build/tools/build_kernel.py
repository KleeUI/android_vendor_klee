#!/usr/bin/env python3
#
# Copyright (C) 2026 The KleeUI Project
#
# SPDX-License-Identifier: Apache-2.0

"""Build a conventional GKI kernel tree inside an Android source checkout."""

import argparse
import os
import pathlib
import re
import shutil
import subprocess
import sys
import time


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=pathlib.Path)
    parser.add_argument("--out", required=True, type=pathlib.Path)
    parser.add_argument("--dist", required=True, type=pathlib.Path)
    parser.add_argument("--arch", required=True)
    parser.add_argument("--image", required=True)
    parser.add_argument("--config", action="append", default=[])
    parser.add_argument("--make-arg", action="append", default=[])
    parser.add_argument("--dtbo-target")
    parser.add_argument("--platform-root", type=pathlib.Path)
    parser.add_argument("--build-config")
    parser.add_argument("--skip-platform-dtbo", action="store_true")
    parser.add_argument("--dtb-base", action="append", default=[])
    parser.add_argument("--dtb-overlay", action="append", default=[])
    parser.add_argument("--dtb-output", type=pathlib.Path)
    parser.add_argument("--dtb-source-root", type=pathlib.Path)
    parser.add_argument("--dtb-source-marker", action="append", default=[])
    parser.add_argument("--dtbo-max-size", type=lambda value: int(value, 0))
    parser.add_argument("--external-module-root", type=pathlib.Path)
    parser.add_argument("--external-module", action="append", default=[])
    return parser.parse_args()


def run(command, env, cwd=None):
    print("+", " ".join(str(item) for item in command), flush=True)
    subprocess.run(command, cwd=cwd, env=env, check=True)


def inherited_kernel_path(top, value):
    """Return the host PATH without Android's restricted-tool interposer."""
    android_interposer = (top / "out" / ".path").resolve()
    entries = []
    for entry in value.split(os.pathsep):
        if not entry:
            continue
        if pathlib.Path(entry).resolve() == android_interposer:
            continue
        entries.append(entry)
    return entries


def resolve_clang_prebuilt(top):
    """Locate the compiler selected by this Android checkout."""
    clang_root = top / "prebuilts" / "clang" / "host" / "linux-x86"
    requested = os.environ.get("LLVM_AOSP_PREBUILTS_VERSION")
    if requested:
        candidate = clang_root / requested
        if (candidate / "bin" / "clang").is_file():
            return candidate
        raise FileNotFoundError(
            "requested Android clang prebuilt is unusable: " + str(candidate)
        )

    # Keep the kernel compiler aligned with the platform compiler instead of
    # assuming that the lightweight clang-stable utility directory is a full
    # toolchain.  This also makes a fresh checkout work without an envsetup
    # shell exporting LLVM_AOSP_PREBUILTS_VERSION.
    global_go = top / "build" / "soong" / "cc" / "config" / "global.go"
    if global_go.is_file():
        match = re.search(
            r'ClangDefaultVersion\s*=\s*"([^"]+)"',
            global_go.read_text(encoding="utf-8"),
        )
        if match:
            candidate = clang_root / match.group(1)
            if (candidate / "bin" / "clang").is_file():
                return candidate

    candidates = sorted(
        path
        for path in clang_root.glob("clang-*")
        if (path / "bin" / "clang").is_file()
    )
    if not candidates:
        raise FileNotFoundError("no usable Android clang prebuilt under " + str(clang_root))
    return candidates[-1]


def create_host_tool_shims(out):
    """Provide legacy host-tool names expected by Qualcomm's 5.10 scripts."""
    shim_dir = out / ".klee-host-tools"
    shim_dir.mkdir(parents=True, exist_ok=True)
    python_shim = shim_dir / "python"
    python_target = pathlib.Path(sys.executable).resolve()
    if python_shim.is_symlink():
        if python_shim.resolve() != python_target:
            python_shim.unlink()
    elif python_shim.exists():
        raise FileExistsError("host-tool shim is not a symlink: " + str(python_shim))
    if not python_shim.exists():
        python_shim.symlink_to(python_target)
    return shim_dir


def resolve_config(source, arch, value):
    candidate = pathlib.Path(value)
    if candidate.is_file():
        return candidate.resolve()
    candidate = source / "arch" / arch / "configs" / value
    if candidate.is_file():
        return candidate.resolve()
    raise FileNotFoundError(f"kernel config not found: {value}")


def configure_kernel(args, make, base_command, env):
    if not args.config:
        raise RuntimeError("at least one kernel config is required")

    configs = [resolve_config(args.source, args.arch, item) for item in args.config]
    base = configs[0]
    standard_dir = (args.source / "arch" / args.arch / "configs").resolve()

    if base.parent == standard_dir:
        run(base_command + [base.name], env)
    else:
        args.out.mkdir(parents=True, exist_ok=True)
        shutil.copy2(base, args.out / ".config")
        run(base_command + ["olddefconfig"], env)

    if len(configs) > 1:
        merge = args.source / "scripts" / "kconfig" / "merge_config.sh"
        run(
            [
                str(merge),
                "-m",
                "-O",
                str(args.out),
                str(args.out / ".config"),
                *(str(config) for config in configs[1:]),
            ],
            env,
            cwd=args.source,
        )
        run(base_command + ["olddefconfig"], env)


def install_external_modules(args, make, jobs, env):
    if not args.external_module:
        return
    if not args.external_module_root:
        raise RuntimeError("external modules require --external-module-root")

    module_root_relative_to_kernel = pathlib.PurePosixPath(
        os.path.relpath(args.external_module_root, args.source)
    )

    for relative in args.external_module:
        module = (args.external_module_root / relative).resolve()
        if not module.is_dir():
            raise FileNotFoundError(f"external module directory not found: {module}")

        # Qualcomm's module wrappers derive their source path from M. M must
        # remain relative to the kernel tree; an absolute value would turn
        # expressions such as $(KERNEL_SRC)/$(M) into an invalid path.
        module_kernel_path = module_root_relative_to_kernel / relative
        command = [
            str(make),
            f"-j{jobs}",
            "-C",
            str(module),
            f"M={module_kernel_path.as_posix()}",
            f"KERNEL_SRC={args.source.resolve()}",
            # Qualcomm wrappers locate sibling generated Module.symvers files
            # as $(OUT_DIR)/../sm8450-modules.  External Kbuild output mirrors
            # that layout beside the kernel output directory, not in source.
            f"OUT_DIR={args.out.resolve()}",
            f"O={args.out.resolve()}",
            f"ARCH={args.arch}",
        ]
        # Use each wrapper's default build target.  Qualcomm trees are not
        # uniform here: most expose `modules`, while datarmnet exposes only an
        # `all` target that delegates to the kernel's modules target.
        run(command, env)
        run(
            command
            + [
                "modules_install",
                f"INSTALL_MOD_PATH={args.dist.resolve()}",
                "INSTALL_MOD_STRIP=1",
            ],
            env,
        )


def reset_module_install_tree(dist):
    """Remove stale module-install outputs while preserving the build cache."""
    for relative in ("lib/modules", "modules"):
        path = dist / relative
        if path.is_symlink() or path.is_file():
            path.unlink()
        elif path.is_dir():
            shutil.rmtree(path)


def stage_kernel_modules(dist):
    """Expose installed modules through stable basename-only build outputs."""
    staging = dist / "modules"
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)

    modules = sorted(dist.rglob("*.ko"))
    if not modules:
        raise RuntimeError("kernel build did not install any modules")

    seen = {}
    staged = []
    for module in modules:
        name = module.name
        previous = seen.get(name)
        if previous is not None:
            raise RuntimeError(
                "duplicate installed kernel module basename "
                f"{name}: {previous} and {module}"
            )
        destination = staging / name
        shutil.copy2(module, destination)
        seen[name] = module
        staged.append(name)

    (dist / "modules.list").write_text(
        "".join(f"{name}\n" for name in staged), encoding="utf-8"
    )


def prepare_platform_external_output_alias(args, platform):
    """Expose Qualcomm's historical sibling module output layout."""
    if not args.external_module_root:
        return

    kernel_out = args.out / args.source.relative_to(platform)
    external_relative = pathlib.PurePosixPath(
        os.path.relpath(args.external_module_root, args.source)
    )
    actual_output = (kernel_out / external_relative).resolve()
    alias = args.out / args.external_module_root.name
    if alias.resolve() == actual_output:
        return

    if alias.is_symlink():
        if alias.resolve() == actual_output:
            return
        alias.unlink()
    elif alias.exists():
        if alias.is_dir():
            shutil.rmtree(alias)
        else:
            alias.unlink()

    actual_output.mkdir(parents=True, exist_ok=True)
    alias.symlink_to(os.path.relpath(actual_output, alias.parent))


def package_merged_dtb(args, env):
    """Merge device-specific overlays into the selected DTB variants."""
    if not args.dtb_base:
        if args.dtb_overlay or args.dtb_output:
            raise RuntimeError("DTB overlays require at least one --dtb-base")
        return
    if not args.dtb_output:
        raise RuntimeError("DTB bases require --dtb-output")

    fdtoverlay = None
    if args.dtb_overlay:
        fdtoverlay = shutil.which("fdtoverlay")
        if not fdtoverlay:
            raise RuntimeError("fdtoverlay is required to assemble source-built DTBs")

    dts_root = args.out / "arch" / args.arch / "boot" / "dts" / "vendor"
    overlays = [dts_root / overlay for overlay in args.dtb_overlay]
    missing_overlays = [str(path) for path in overlays if not path.is_file()]
    if missing_overlays:
        raise FileNotFoundError(
            "kernel build did not produce requested DTB overlays: "
            + ", ".join(missing_overlays)
        )

    output = args.dtb_output
    output.parent.mkdir(parents=True, exist_ok=True)
    variants_dir = output.parent / "dtb"
    if variants_dir.exists():
        shutil.rmtree(variants_dir)
    variants_dir.mkdir(parents=True)

    variants = []
    for relative in args.dtb_base:
        base = dts_root / relative
        if not base.is_file():
            raise FileNotFoundError(f"kernel build did not produce DTB base: {base}")
        variant = variants_dir / pathlib.PurePosixPath(relative).name
        if overlays:
            run(
                [
                    fdtoverlay,
                    "-i",
                    str(base),
                    "-o",
                    str(variant),
                    *(str(path) for path in overlays),
                ],
                env,
            )
        else:
            shutil.copy2(base, variant)
        variants.append(variant)

    with output.open("wb") as image:
        for variant in variants:
            with variant.open("rb") as source:
                shutil.copyfileobj(source, image)


def validate_source_dtb_tree(args, platform):
    """Require the platform build to see the tracked device DTS tree."""
    source_root = args.dtb_source_root
    if source_root is None:
        source_root = (
            platform
            / "msm-kernel"
            / "arch"
            / args.arch
            / "boot"
            / "dts"
            / "vendor"
        )
    source_root = source_root.resolve()
    required_files = [source_root / "Makefile", source_root / "qcom" / "Makefile"]
    markers = [source_root / marker for marker in args.dtb_source_marker]
    # A binding directory may be represented by a tracked symlink (for
    # example bindings/media/camera -> ../../qcom/camera/bindings).  Follow
    # that link when validating source markers, while still requiring the
    # root and qcom Makefiles to be regular files.
    missing = [str(path) for path in required_files if not path.is_file()]
    missing.extend(str(path) for path in markers if not path.exists())
    if missing:
        raise FileNotFoundError(
            "source DT tree is incomplete; refusing stock DT fallback: "
            + ", ".join(missing)
        )
    return source_root


def platform_dtb_root(args):
    return args.out / "arch" / args.arch / "boot" / "dts" / "vendor"


def package_platform_dtb(args):
    """Package selected base DTBs while leaving overlays in dtbo.img."""
    if not args.dtb_base:
        if args.dtb_output or args.dtb_overlay:
            raise RuntimeError(
                "platform source DT packaging requires at least one --dtb-base"
            )
        return
    if not args.dtb_output:
        raise RuntimeError("DTB bases require --dtb-output")

    dts_root = platform_dtb_root(args)
    bases = [dts_root / base for base in args.dtb_base]
    overlays = [dts_root / overlay for overlay in args.dtb_overlay]
    missing_bases = [str(path) for path in bases if not path.is_file()]
    missing_overlays = [str(path) for path in overlays if not path.is_file()]
    if missing_bases:
        raise FileNotFoundError(
            "platform build did not produce requested source DTB bases: "
            + ", ".join(missing_bases)
        )
    if missing_overlays:
        raise FileNotFoundError(
            "platform build did not produce requested source DTBO overlays: "
            + ", ".join(missing_overlays)
        )

    output = args.dtb_output
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("wb") as image:
        for base in bases:
            with base.open("rb") as source:
                shutil.copyfileobj(source, image)
    if output.stat().st_size == 0:
        raise RuntimeError("source DTB packaging produced an empty dtb.img")


def validate_platform_dtbo(args):
    if not args.dtbo_target:
        return
    image = args.dist / args.dtbo_target
    if not image.is_file() or image.stat().st_size == 0:
        raise RuntimeError(
            "platform build did not produce a non-empty source-built DTBO image: "
            + str(image)
        )
    if args.dtbo_max_size is not None and image.stat().st_size > args.dtbo_max_size:
        raise RuntimeError(
            f"source-built DTBO image exceeds partition capacity: "
            f"{image.stat().st_size} > {args.dtbo_max_size}"
        )


def build_kernel_platform(args, jobs, env):
    if not args.platform_root or not args.build_config:
        raise RuntimeError(
            "--platform-root and --build-config must be specified together"
        )

    platform = args.platform_root.resolve()
    build_config = pathlib.Path(args.build_config)
    if build_config.is_absolute():
        try:
            build_config = build_config.relative_to(platform)
        except ValueError as error:
            raise ValueError("build config must be inside the kernel platform") from error

    config_path = platform / build_config
    build_script = platform / "build" / "build.sh"
    if not config_path.is_file():
        raise FileNotFoundError(f"kernel platform config not found: {config_path}")
    if not build_script.is_file():
        raise FileNotFoundError(f"kernel platform builder not found: {build_script}")

    if args.dtb_source_root or args.dtb_source_marker or args.dtb_base:
        validate_source_dtb_tree(args, platform)

    args.out.mkdir(parents=True, exist_ok=True)
    args.dist.mkdir(parents=True, exist_ok=True)
    # Never accept an image left by a previous build.  The Android makefiles
    # intentionally keep the kernel image and DT artifacts in one dist tree,
    # so an old DTBO can otherwise satisfy a target even when this invocation
    # did not compile the linked device DTS inputs.
    stale_outputs = [args.dist / args.image]
    if args.dtbo_target:
        stale_outputs.append(args.dist / args.dtbo_target)
    if args.dtb_output:
        stale_outputs.append(args.dtb_output)
    for output in stale_outputs:
        if output.is_file() or output.is_symlink():
            output.unlink()
    for relative in args.dtb_base + args.dtb_overlay:
        generated = platform_dtb_root(args) / relative
        if generated.is_file() or generated.is_symlink():
            generated.unlink()
    build_started_ns = time.time_ns()
    platform_env = env.copy()
    platform_env["BUILD_CONFIG"] = build_config.as_posix()
    platform_env["OUT_DIR"] = str(args.out)
    platform_env["DIST_DIR"] = str(args.dist)
    if args.external_module:
        if not args.external_module_root:
            raise RuntimeError("external modules require --external-module-root")

        # kernel_platform/build.sh owns the platform output layout.  Passing
        # EXT_MODULES to it keeps external modules on the exact same
        # .config, Module.symvers, compiler and staging path as the in-tree
        # modules instead of attempting a second, incompatible Kbuild.
        external_modules = []
        for relative in args.external_module:
            module = (args.external_module_root / relative).resolve()
            if not module.is_dir():
                raise FileNotFoundError(
                    f"external module directory not found: {module}"
                )
            external_modules.append(
                pathlib.PurePosixPath(
                    os.path.relpath(module, platform)
                ).as_posix()
            )
        platform_env["EXT_MODULES"] = " ".join(external_modules)
        # A stale shell environment must not silently suppress the requested
        # source modules.
        platform_env.pop("SKIP_EXT_MODULES", None)
        prepare_platform_external_output_alias(args, platform)
    if args.skip_platform_dtbo:
        platform_env["DT_OVERLAY_SUPPORT"] = "0"
        # Public Qualcomm kernel releases can omit the retail board DT
        # repositories. In that configuration Android supplies the matching
        # stock DTB/DTBO inputs and owns vendor_boot assembly, so the kernel
        # platform build must stop after producing its kernel kit.
        platform_env["SKIP_VENDOR_BOOT"] = "1"
    host_tools = platform_env.get("ADDITIONAL_HOST_TOOLS", "").split()
    if "printf" not in host_tools:
        host_tools.append("printf")
    platform_env["ADDITIONAL_HOST_TOOLS"] = " ".join(host_tools)
    run(
        [str(build_script), f"-j{jobs}", *args.make_arg],
        platform_env,
        cwd=platform,
    )

    required = [
        args.dist / args.image,
        args.dist / ".config",
        args.dist / "Module.symvers",
    ]
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        raise RuntimeError(
            "kernel platform did not produce a complete KERNEL_KIT: "
            + ", ".join(missing)
        )
    # build.sh installs both in-tree and external modules in its staging
    # directory and copies the resulting .ko files into DIST_DIR.  Android's
    # Klee packaging graph consumes a stable basename-only directory, so
    # normalize that output only after the platform build has completed.
    reset_module_install_tree(args.dist)
    stage_kernel_modules(args.dist)
    validate_platform_dtbo(args)
    package_platform_dtb(args)
    generated_outputs = [args.dist / args.image]
    if args.dtbo_target:
        generated_outputs.append(args.dist / args.dtbo_target)
    if args.dtb_output:
        generated_outputs.append(args.dtb_output)
    stale = [
        str(path)
        for path in generated_outputs
        if path.is_file() and path.stat().st_mtime_ns < build_started_ns
    ]
    if stale:
        raise RuntimeError(
            "platform build reused stale source artifacts: " + ", ".join(stale)
        )


def main():
    args = parse_args()
    top = pathlib.Path.cwd().resolve()
    args.source = args.source.resolve()
    args.out = args.out.resolve()
    args.dist = args.dist.resolve()
    if args.external_module_root:
        args.external_module_root = args.external_module_root.resolve()
    if args.dtb_output:
        args.dtb_output = args.dtb_output.resolve()
    if args.dtb_source_root:
        args.dtb_source_root = args.dtb_source_root.resolve()
    if args.platform_root:
        args.platform_root = args.platform_root.resolve()

    host_tool_shims = create_host_tool_shims(args.out)

    jobs = os.environ.get("KLEE_KERNEL_JOBS") or str(os.cpu_count() or 1)
    if not jobs.isdigit() or int(jobs) < 1:
        raise ValueError("KLEE_KERNEL_JOBS must be a positive integer")

    host_tag = "linux-x86"
    clang = resolve_clang_prebuilt(top)
    build_tools = top / "prebuilts" / "build-tools" / host_tag / "bin"
    kernel_tools = top / "prebuilts" / "kernel-build-tools" / host_tag / "bin"
    make = build_tools / "make"
    if not make.is_file():
        make = pathlib.Path(shutil.which("make") or "make")

    env = os.environ.copy()
    env["ARCH"] = args.arch
    env["LLVM"] = "1"
    env["LLVM_IAS"] = "1"
    env["PATH"] = os.pathsep.join(
        [
            str(clang / "bin"),
            str(host_tool_shims),
            str(build_tools),
            str(kernel_tools),
            *os.defpath.split(os.pathsep),
            *inherited_kernel_path(top, env.get("PATH", "")),
        ]
    )
    env["LD_LIBRARY_PATH"] = os.pathsep.join(
        [str(clang / "lib64"), env.get("LD_LIBRARY_PATH", "")]
    )

    if args.platform_root or args.build_config:
        if args.config:
            raise ValueError(
                "kernel platform builds cannot use conventional config arguments"
            )
        build_kernel_platform(args, jobs, env)
        return

    args.out.mkdir(parents=True, exist_ok=True)
    args.dist.mkdir(parents=True, exist_ok=True)
    base_command = [
        str(make),
        "-C",
        str(args.source),
        f"O={args.out}",
        f"ARCH={args.arch}",
        f"-j{jobs}",
        *args.make_arg,
    ]

    configure_kernel(args, make, base_command, env)
    targets = [args.image, "modules", "dtbs"]
    if args.dtbo_target:
        targets.append(args.dtbo_target)
    run(base_command + targets, env)
    reset_module_install_tree(args.dist)
    run(
        base_command
        + [
            "modules_install",
            f"INSTALL_MOD_PATH={args.dist}",
            "INSTALL_MOD_STRIP=1",
        ],
        env,
    )
    install_external_modules(args, make, jobs, env)
    stage_kernel_modules(args.dist)
    package_merged_dtb(args, env)


if __name__ == "__main__":
    main()
