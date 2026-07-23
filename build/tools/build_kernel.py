#!/usr/bin/env python3
#
# Copyright (C) 2026 The KleeUI Project
#
# SPDX-License-Identifier: Apache-2.0

"""Build a conventional GKI kernel tree inside an Android source checkout."""

import argparse
import os
import pathlib
import shutil
import subprocess


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
    parser.add_argument("--external-module-root", type=pathlib.Path)
    parser.add_argument("--external-module", action="append", default=[])
    return parser.parse_args()


def run(command, env, cwd=None):
    print("+", " ".join(str(item) for item in command), flush=True)
    subprocess.run(command, cwd=cwd, env=env, check=True)


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


def install_external_modules(args, make, env):
    if not args.external_module:
        return
    if not args.external_module_root:
        raise RuntimeError("external modules require --external-module-root")

    for relative in args.external_module:
        module = (args.external_module_root / relative).resolve()
        command = [
            str(make),
            "-C",
            str(module),
            f"KERNEL_SRC={args.source.resolve()}",
            f"OUT_DIR={args.out.resolve()}",
            f"O={args.out.resolve()}",
            f"ARCH={args.arch}",
        ]
        run(command + ["modules"], env)
        run(
            command
            + [
                "modules_install",
                f"INSTALL_MOD_PATH={args.dist.resolve()}",
                "INSTALL_MOD_STRIP=1",
            ],
            env,
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

    args.out.mkdir(parents=True, exist_ok=True)
    args.dist.mkdir(parents=True, exist_ok=True)
    platform_env = env.copy()
    platform_env["BUILD_CONFIG"] = build_config.as_posix()
    platform_env["OUT_DIR"] = str(args.out)
    platform_env["DIST_DIR"] = str(args.dist)
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


def main():
    args = parse_args()
    top = pathlib.Path.cwd().resolve()
    args.source = args.source.resolve()
    args.out = args.out.resolve()
    args.dist = args.dist.resolve()
    if args.external_module_root:
        args.external_module_root = args.external_module_root.resolve()
    if args.platform_root:
        args.platform_root = args.platform_root.resolve()

    jobs = os.environ.get("KLEE_KERNEL_JOBS") or str(os.cpu_count() or 1)
    if not jobs.isdigit() or int(jobs) < 1:
        raise ValueError("KLEE_KERNEL_JOBS must be a positive integer")

    host_tag = "linux-x86"
    clang_version = os.environ.get("LLVM_AOSP_PREBUILTS_VERSION", "clang-stable")
    clang = top / "prebuilts" / "clang" / "host" / host_tag / clang_version
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
            str(build_tools),
            str(kernel_tools),
            env.get("PATH", ""),
        ]
    )
    env["LD_LIBRARY_PATH"] = os.pathsep.join(
        [str(clang / "lib64"), env.get("LD_LIBRARY_PATH", "")]
    )

    if args.platform_root or args.build_config:
        if args.config or args.external_module or args.dtbo_target:
            raise ValueError(
                "kernel platform builds cannot use conventional config, module, "
                "or DTBO arguments"
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
    run(
        base_command
        + [
            "modules_install",
            f"INSTALL_MOD_PATH={args.dist}",
            "INSTALL_MOD_STRIP=1",
        ],
        env,
    )
    install_external_modules(args, make, env)

    modules = sorted(args.dist.rglob("*.ko"))
    (args.dist / "modules.list").write_text(
        "".join(f"{module.relative_to(args.dist)}\n" for module in modules),
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
