#!/usr/bin/env python3
#
# Copyright (C) 2026 The KleeUI Project
#
# SPDX-License-Identifier: Apache-2.0

"""Build and verify a Klee kernel bundle inside an Android checkout."""

import argparse
import hashlib
import json
import os
import pathlib
import re
import shlex
import shutil
import struct
import subprocess
import sys
import tempfile
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
    parser.add_argument("--dtbo-output", type=pathlib.Path)
    parser.add_argument("--dt-layout", type=pathlib.Path)
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
    parser.add_argument("--required-module", action="append", default=[])
    parser.add_argument("--retained-provenance", type=pathlib.Path)
    parser.add_argument("--source-manifest", type=pathlib.Path)
    parser.add_argument("--stamp", type=pathlib.Path)
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


# Klee owns the external-module transaction.  Qualcomm's individual wrapper
# makefiles describe some of these relationships with Android-only
# ``LOCAL_ADDITIONAL_DEPENDENCIES`` rules, but those rules are not available to
# the standalone AOSP/Klee builder.  Keep the small, platform-neutral graph
# here so a device's module list remains declarative and the build order is
# deterministic.  The entries are intentionally Klee policy; they are not a
# copy of an upstream product makefile.
KLEE_EXTERNAL_MODULE_DEPENDENCIES = {
    "camera-kernel": ("mmrm-driver",),
    "cvp-kernel": ("mmrm-driver",),
    "display-drivers/msm": ("mmrm-driver",),
    "eva-kernel": ("mmrm-driver",),
    "dataipa/drivers/platform/msm": ("datarmnet-ext/mem",),
    "datarmnet/core": ("dataipa/drivers/platform/msm",),
    "datarmnet-ext/aps": ("datarmnet/core",),
    "datarmnet-ext/offload": ("datarmnet/core",),
    "datarmnet-ext/shs": ("datarmnet/core",),
    "datarmnet-ext/perf": ("datarmnet/core", "datarmnet-ext/shs"),
    "datarmnet-ext/perf_tether": ("datarmnet/core",),
    "datarmnet-ext/sch": ("datarmnet/core",),
    "datarmnet-ext/wlan": ("datarmnet/core",),
    "video-driver": ("mmrm-driver",),
}


def external_module_policy_key(relative):
    """Map a module path to the Klee policy key, if one is known."""
    value = pathlib.PurePosixPath(relative).as_posix()
    for key in KLEE_EXTERNAL_MODULE_DEPENDENCIES:
        if value == key or value.endswith("/" + key):
            return key
    return value


def order_external_modules(modules):
    """Return a stable topological order for the requested module paths.

    Independent modules retain the order supplied by the device tree.  A
    missing optional dependency is left untouched; this lets the same Klee
    builder serve platforms which do not select a particular Qualcomm stack,
    while selected producer/consumer pairs are always ordered correctly.
    """
    values = [pathlib.PurePosixPath(item).as_posix() for item in modules]
    if len(values) < 2:
        return values

    positions = {value: index for index, value in enumerate(values)}
    by_policy_key = {}
    for value in values:
        by_policy_key.setdefault(external_module_policy_key(value), []).append(value)

    dependencies = {value: set() for value in values}
    dependents = {value: set() for value in values}
    indegree = {value: 0 for value in values}
    for value in values:
        policy_key = external_module_policy_key(value)
        for dependency_key in KLEE_EXTERNAL_MODULE_DEPENDENCIES.get(policy_key, ()):
            candidates = by_policy_key.get(dependency_key, ())
            if not candidates:
                continue
            dependency = candidates[0]
            if dependency == value or dependency in dependencies[value]:
                continue
            dependencies[value].add(dependency)
            dependents[dependency].add(value)
            indegree[value] += 1

    ready = sorted(
        (value for value, degree in indegree.items() if degree == 0),
        key=positions.__getitem__,
    )
    ordered = []
    while ready:
        value = ready.pop(0)
        ordered.append(value)
        for dependent in sorted(dependents[value], key=positions.__getitem__):
            indegree[dependent] -= 1
            if indegree[dependent] == 0:
                ready.append(dependent)
        ready.sort(key=positions.__getitem__)

    if len(ordered) != len(values):
        cycle = [value for value, degree in indegree.items() if degree]
        raise ValueError(
            "cyclic Klee external-module dependency graph: " + ", ".join(cycle)
        )
    if ordered != values:
        print(
            "Klee external-module order: " + " ".join(ordered),
            flush=True,
        )
    return ordered


def external_module_output_dir(args, relative, output_dir):
    """Return the output directory used by build.sh for one external module."""
    module_root_relative_to_kernel = pathlib.PurePosixPath(
        os.path.relpath(args.external_module_root, args.source)
    )
    return (
        output_dir / module_root_relative_to_kernel / pathlib.PurePosixPath(relative)
    ).resolve()


def external_module_symvers(
    args, modules, output_dir, existing_only=False, include_kernel=True
):
    """Return output-only Module.symvers inputs in transaction order.

    A mixed kernel build already makes the platform ``Module.symvers``
    (including the GKI ``vmlinux.symvers``) the module-local ``Module.symvers``
    input.  Repeating that same file in ``KBUILD_EXTRA_SYMBOLS`` makes modpost
    see every vmlinux export twice.  Conventional, non-mixed builds still need
    the explicit kernel table, so callers select that behavior explicitly.
    """
    paths = []
    kernel_symvers = (output_dir / "Module.symvers").resolve()
    if include_kernel and (not existing_only or kernel_symvers.is_file()):
        paths.append(kernel_symvers)
    for relative in order_external_modules(modules):
        path = external_module_output_dir(args, relative, output_dir) / "Module.symvers"
        path = path.resolve()
        if existing_only and not path.is_file():
            continue
        if path not in paths:
            paths.append(path)
    return paths


def write_symvers_bundle(destination, tables):
    """Write one transaction-local Module.symvers input for wrapper makefiles.

    Qualcomm's wrapper recipes expand ``KBUILD_EXTRA_SYMBOLS`` without shell
    quoting.  A value containing several paths is consequently parsed as a
    list of make targets by the recursive invocation.  Concatenating the
    already-published tables into one file keeps the recursive interface
    unambiguous while preserving the exact symbol records and their order.
    """
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("wb") as output:
        for table in tables:
            table = pathlib.Path(table)
            if not table.is_file():
                raise FileNotFoundError("Module.symvers input is missing: " + str(table))
            data = table.read_bytes()
            if data:
                output.write(data)
                if not data.endswith(b"\n"):
                    output.write(b"\n")


def write_platform_external_module_script(args, make, jobs, modules, kernel_output):
    """Create the Klee-owned external-module phase for a mixed platform build.

    ``kernel_platform/build.sh`` snapshots its make arguments once, before it
    enters the external-module loop.  Qualcomm wrappers, however, publish
    ``Module.symvers`` one module at a time.  Passing the complete future
    symbol-table list therefore turns a dependency into a make prerequisite
    for a file which does not exist yet.  The generated phase is run from
    ``DIST_CMDS`` after the in-tree staging directory has been created.  It
    builds and installs each selected module in Klee's topological order.  The
    command for a consumer is emitted only after the preceding producer's
    output path has been established, so every symbol input is present when
    Kbuild evaluates its prerequisites.

    The script is deliberately generated inside the transaction output tree:
    it is not a checked-in compatibility wrapper and cannot resolve to an old
    Xiaomi or Lineage output directory.
    """
    if not modules:
        return None
    if not args.external_module_root:
        raise RuntimeError("external modules require --external-module-root")

    module_root = args.external_module_root.resolve()
    source = args.source.resolve()
    kernel_output = kernel_output.resolve()
    script = args.out / ".klee-external-modules.sh"
    script.parent.mkdir(parents=True, exist_ok=True)

    module_root_relative = pathlib.PurePosixPath(os.path.relpath(module_root, source))
    mixed_tree = (args.out / "gki_kernel" / "dist").resolve()
    published_symbols_path = args.out / ".klee-published.symvers"
    # Keep the same compiler and product selectors as the platform transaction.
    # Qualcomm's wrapper makefiles expand KBUILD_EXTRA_SYMBOLS again in a
    # recursive make recipe.  Passing several paths therefore causes the
    # wrapper to turn every path after the first into a separate make target.
    # Klee publishes one transaction-local aggregate table instead; one path
    # survives both levels of make recursion and cannot accidentally resolve
    # to a stale Xiaomi or Lineage output tree.
    make_args = [
        "LLVM=1",
        "LLVM_IAS=1",
        "DEPMOD=depmod",
        "DTC=dtc",
    ]
    make_args.extend(
        str(value)
        for value in args.make_arg
        if not str(value).startswith("KBUILD_EXTRA_SYMBOLS=")
    )

    def quoted(value):
        return shlex.quote(str(value))

    lines = [
        "#!/bin/bash",
        "set -euo pipefail",
        'staging="${1:?Klee external-module staging path is required}"',
        'mkdir -p "$staging"',
        f"published_symbols={quoted(published_symbols_path)}",
        ': > "$published_symbols"',
        "echo 'Klee external-module transaction: begin'",
    ]
    aliases = []

    def make_command(module, module_kernel_path, extras):
        assignment = [
            quoted(make),
            f"-j{int(jobs)}",
            "-C",
            quoted(module),
            f"M={quoted(module_kernel_path)}",
            f"KERNEL_SRC={quoted(source)}",
            f"OUT_DIR={quoted(kernel_output)}",
            f"O={quoted(kernel_output)}",
            f"ARCH={quoted(args.arch)}",
        ]
        assignment.extend(quoted(value) for value in make_args)
        assignment.append(f"KBUILD_MIXED_TREE={quoted(mixed_tree)}")
        for value in extras:
            # ``staging`` is a variable owned by build.sh.  Keep that one
            # assignment double-quoted so the parent shell expands it before
            # invoking make; ordinary assignments are immutable literals.
            if value == 'INSTALL_MOD_PATH="$staging"':
                assignment.append('INSTALL_MOD_PATH="$staging"')
            else:
                assignment.append(quoted(value))
        assignment.append(quoted("KBUILD_EXTRA_SYMBOLS=" + str(published_symbols_path)))
        return " ".join(assignment)

    # Install aliases and cleanup before the first build so an interrupted
    # transaction cannot leave a temporary qcacld alias in the source tree.
    for value in modules:
        candidate = (module_root / pathlib.PurePosixPath(value)).resolve()
        if candidate.name != "qcacld-3.0":
            continue
        for profile in ("qca6490", "qca6750"):
            aliases.append((candidate / f".klee-{profile}", candidate))
    if aliases:
        lines.append("klee_created_qcacld_aliases=()")
        lines.append("klee_cleanup_qcacld_aliases() {")
        lines.extend(
            [
                "  for alias in \"${klee_created_qcacld_aliases[@]}\"; do",
                '    rm -f "$alias"',
                "  done",
            ]
        )
        lines.extend(["}", "trap klee_cleanup_qcacld_aliases EXIT"])

        for alias, target in aliases:
            lines.extend(
                [
                    f"if [[ -L {quoted(alias)} ]]; then",
                    f"  test \"$(readlink -f {quoted(alias)})\" = {quoted(target)} || {{ echo 'refusing qcacld alias: {alias}' >&2; exit 1; }}",
                    f"elif [[ ! -e {quoted(alias)} ]]; then",
                    f"  ln -s {quoted(target)} {quoted(alias)}",
                    f"  klee_created_qcacld_aliases+=( {quoted(alias)} )",
                    "else",
                    f"  echo 'refusing to replace qcacld path: {alias}' >&2",
                    "  exit 1",
                    "fi",
                ]
            )

    for relative in modules:
        relative = pathlib.PurePosixPath(relative).as_posix()
        module = (module_root / pathlib.PurePosixPath(relative)).resolve()
        if not module.is_dir():
            raise FileNotFoundError(f"external module directory not found: {module}")
        module_kernel_path = (module_root_relative / relative).as_posix()
        module_name = module.name
        lines.append(f"echo 'Klee external module: {relative}'")

        if module_name == "qcacld-3.0":
            # The WLAN source exposes two profiles through the same Kbuild
            # tree.  Keep the aliases ephemeral and verify any pre-existing
            # alias before using it.
            for profile in ("qca6490", "qca6750"):
                alias = module / f".klee-{profile}"
                variant_kernel_path = f"{module_kernel_path}/.klee-{profile}"
                variant_output = kernel_output / pathlib.PurePosixPath(variant_kernel_path)
                profile_upper = profile.upper()
                extras = [
                    f"WLAN_ROOT={module}",
                    "WLAN_COMMON_ROOT=cmn",
                    f"WLAN_COMMON_INC={module / 'cmn'}",
                    f"WLAN_FW_API={module.parent / 'fw-api'}",
                    f"WLAN_PROFILE={profile}",
                    f"CONFIG_QCA_CLD_WLAN_PROFILE={profile}",
                    f"MODNAME=qca_cld3_{profile}",
                    f"DEVNAME={profile}",
                    "CONFIG_QCA_CLD_WLAN=m",
                    "CONFIG_QCA_WIFI_ISOC=0",
                    "CONFIG_QCA_WIFI_2_0=1",
                    f"CONFIG_CNSS_{profile_upper}=y",
                    f"CONFIG_{profile_upper}_HEADERS_DEF=y",
                    "WLAN_CTRL_NAME=wlan",
                ]
                lines.append(f"mkdir -p {quoted(variant_output)}")
                lines.append(
                    make_command(
                        module,
                        variant_kernel_path,
                        extras,
                    )
                )
                lines.append(
                    make_command(
                        module,
                        variant_kernel_path,
                        extras
                        + [
                            "INSTALL_MOD_PATH=\"$staging\"",
                            "INSTALL_MOD_STRIP=1",
                        ],
                    )
                    + " modules_install"
                )
                # WLAN profiles do not provide symbols consumed by another
                # selected module.  Verify their publication if present, but
                # keep them out of the consumer list because their output path
                # is intentionally profile-private.
                variant_symvers = variant_output / "Module.symvers"
                lines.append(
                    f"test -f {quoted(variant_symvers)} || {{ echo 'qcacld profile did not publish Module.symvers: {variant_symvers}' >&2; exit 1; }}"
                )
            continue

        extras = []
        if module_name == "cvp-kernel":
            extras.append("CONFIG_MSM_CVP=m")
        elif module_name == "eva-kernel":
            extras.append("CONFIG_MSM_EVA=m")

        output_symvers = external_module_output_dir(args, relative, kernel_output)
        output_symvers.parent.mkdir(parents=True, exist_ok=True)
        lines.append(make_command(module, module_kernel_path, extras))
        lines.append(
            f"test -f {quoted(output_symvers / 'Module.symvers')} || {{ echo 'external module did not publish Module.symvers: {output_symvers / 'Module.symvers'}' >&2; exit 1; }}"
        )
        lines.append(
            make_command(
                module,
                module_kernel_path,
                extras + ["INSTALL_MOD_PATH=\"$staging\"", "INSTALL_MOD_STRIP=1"],
            )
            + " modules_install"
        )
        # Append only after the producer's build and install both succeeded.
        # The next consumer then sees the complete set of tables published by
        # this Klee transaction, while no future producer is exposed early.
        lines.append(
            f"cat {quoted(output_symvers / 'Module.symvers')} >> \"$published_symbols\""
        )

    lines.extend(
        [
            "echo 'Klee external-module transaction: complete'",
        ]
    )
    script.write_text("\n".join(lines) + "\n", encoding="utf-8")
    script.chmod(0o755)
    return script


def install_external_modules(
    args, make, jobs, env, module_values=None, kernel_output=None
):
    modules = args.external_module if module_values is None else module_values
    if not modules:
        return
    if not args.external_module_root:
        raise RuntimeError("external modules require --external-module-root")

    module_root_relative_to_kernel = pathlib.PurePosixPath(
        os.path.relpath(args.external_module_root, args.source)
    )

    output_dir = args.out if kernel_output is None else kernel_output
    modules = order_external_modules(modules)
    published_modules = []
    published_symbols = output_dir / ".klee-published.symvers"
    for relative in modules:
        module = args.external_module_root.joinpath(
            *pathlib.PurePosixPath(relative).parts
        )
        module_real = module.resolve()
        if not module_real.is_dir():
            raise FileNotFoundError(f"external module directory not found: {module}")

        # The Android shell exports ANDROID_BUILD_TOP, but the standalone
        # qcacld wrapper already passes an absolute WLAN_ROOT through
        # M/KERNEL_SRC.  Its Kbuild rewrites WLAN_ROOT when ANDROID_BUILD_TOP
        # is set, which turns that absolute path into an invalid
        # ``../../..//home/...`` path.  Keep the platform environment intact
        # for every other Qualcomm wrapper (some legacy audio Kbuild files
        # still use ANDROID_BUILD_TOP), and isolate only qcacld.
        module_env = env
        if module_real.name == "qcacld-3.0":
            module_env = env.copy()
            module_env.pop("ANDROID_BUILD_TOP", None)

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
            f"OUT_DIR={output_dir.resolve()}",
            f"O={output_dir.resolve()}",
            f"ARCH={args.arch}",
        ]
        # CVP and EVA are separate Qualcomm source projects. Their Kbuild
        # wrappers intentionally leave the module selector to the caller;
        # make that ownership explicit instead of relying on a product
        # Android.mk side effect.
        if module_real.name == "cvp-kernel":
            command.append("CONFIG_MSM_CVP=m")
        elif module_real.name == "eva-kernel":
            command.append("CONFIG_MSM_EVA=m")
        # Every external module consumes the platform KMI and only the tables
        # published by an earlier step in this transaction.  Keep them in one
        # file because Qualcomm wrappers expand this variable again in a
        # recursive make recipe and cannot safely carry a space-separated
        # path list.
        symbol_tables = []
        if not args.platform_root:
            kernel_symvers = (output_dir / "Module.symvers").resolve()
            if kernel_symvers.is_file():
                symbol_tables.append(kernel_symvers)
        symbol_tables.extend(
            external_module_output_dir(args, item, output_dir) / "Module.symvers"
            for item in published_modules
        )
        write_symvers_bundle(published_symbols, symbol_tables)
        command.append("KBUILD_EXTRA_SYMBOLS=" + str(published_symbols.resolve()))
        # Use each wrapper's default build target.  Qualcomm trees are not
        # uniform here: most expose `modules`, while datarmnet exposes only an
        # `all` target that delegates to the kernel's modules target.
        run(command, module_env)
        module_symvers = (
            external_module_output_dir(args, relative, output_dir)
            / "Module.symvers"
        )
        if not module_symvers.is_file():
            raise RuntimeError(
                "external module did not publish Module.symvers: " + str(module_symvers)
            )
        run(
            command
            + [
                "modules_install",
                f"INSTALL_MOD_PATH={args.dist.resolve()}",
                "INSTALL_MOD_STRIP=1",
            ],
            module_env,
        )
        published_modules.append(relative)


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


def build_qcacld_variants(args, make, jobs, env):
    """Build both WLAN profiles from one tracked qcacld source tree.

    Qualcomm's Android.mk normally creates ``.qca6490`` and ``.qca6750``
    aliases while parsing the product.  Klee does not depend on that mutable
    parse-time side effect: each profile gets an ephemeral source alias and a
    separate Kbuild module output path, while both builds consume the same
    platform .config and Module.symvers.
    """
    if not args.external_module_root:
        return
    qcacld = None
    for relative in args.external_module:
        candidate = args.external_module_root.joinpath(
            *pathlib.PurePosixPath(relative).parts
        )
        if candidate.name == "qcacld-3.0":
            qcacld = candidate.resolve()
            break
    if qcacld is None:
        return
    kernel_relative = pathlib.PurePosixPath(
        os.path.relpath(qcacld, args.source)
    )
    kernel_output = args.out / args.source.relative_to(args.platform_root)
    aliases = []
    variant_env = env.copy()
    variant_env.pop("ANDROID_BUILD_TOP", None)
    published_symbols = kernel_output / ".klee-published.symvers"
    try:
        for profile in ("qca6490", "qca6750"):
            alias = qcacld / f".klee-{profile}"
            if alias.is_symlink():
                try:
                    alias_target = alias.resolve(strict=True)
                except FileNotFoundError as error:
                    raise RuntimeError(
                        "refusing dangling qcacld alias: " + str(alias)
                    ) from error
                if alias_target != qcacld:
                    raise RuntimeError(
                        "refusing to replace existing qcacld alias: " + str(alias)
                    )
                # A previous interrupted Klee build may have left the
                # self-alias behind. It is still ours to clean up.
                aliases.append(alias)
            elif alias.exists():
                raise RuntimeError(
                    "refusing to replace existing qcacld path: " + str(alias)
                )
            else:
                alias.symlink_to(qcacld, target_is_directory=True)
                aliases.append(alias)
            module_relative = kernel_relative / f".klee-{profile}"
            module_name = f"qca_cld3_{profile}"
            profile_upper = profile.upper()
            # Keep one transaction-local symbol bundle for both the build and
            # install pass.  Qualcomm wrappers commonly provide a
            # ``KBUILD_EXTRA_SYMBOLS ?= ...`` fallback, and an environment
            # value does not override that recursive make assignment in every
            # wrapper.  A command-line variable with one path does, while a
            # space-separated list would be split into recursive make targets.
            symbol_tables = external_module_symvers(
                args,
                args.external_module,
                kernel_output,
                existing_only=True,
                include_kernel=False,
            )
            write_symvers_bundle(published_symbols, symbol_tables)
            command = [
                str(make),
                f"-j{jobs}",
                "-C",
                str(qcacld),
                f"M={module_relative.as_posix()}",
                f"KERNEL_SRC={args.source.resolve()}",
                f"OUT_DIR={kernel_output.resolve()}",
                f"O={kernel_output.resolve()}",
                f"ARCH={args.arch}",
                f"WLAN_ROOT={qcacld}",
                "WLAN_COMMON_ROOT=cmn",
                f"WLAN_COMMON_INC={qcacld / 'cmn'}",
                f"WLAN_FW_API={qcacld.parent / 'fw-api'}",
                f"WLAN_PROFILE={profile}",
                f"CONFIG_QCA_CLD_WLAN_PROFILE={profile}",
                f"MODNAME={module_name}",
                f"DEVNAME={profile}",
                "CONFIG_QCA_CLD_WLAN=m",
                "CONFIG_QCA_WIFI_ISOC=0",
                "CONFIG_QCA_WIFI_2_0=1",
                f"CONFIG_CNSS_{profile_upper}=y",
                f"CONFIG_{profile_upper}_HEADERS_DEF=y",
                "WLAN_CTRL_NAME=wlan",
                "KBUILD_EXTRA_SYMBOLS=" + str(published_symbols.resolve()),
            ]
            run(command, variant_env)
            run(
                command
                + [
                    "modules_install",
                    f"INSTALL_MOD_PATH={args.dist.resolve()}",
                    "INSTALL_MOD_STRIP=1",
                ],
                variant_env,
            )
    finally:
        for alias in aliases:
            if alias.is_symlink():
                alias.unlink()


def prepare_platform_external_output_alias(args, platform):
    """Expose Qualcomm's historical sibling module output layout."""
    if not args.external_module_root:
        return

    # ``args.out`` is the build.sh common output root.  build.sh derives the
    # kernel output as ``COMMON_OUT_DIR/<kernel-dir>`` before appending the
    # external module path, so mirror that exact lexical layout here.  This
    # keeps the compatibility aliases inside the same Klee transaction and
    # prevents wrappers from accidentally resolving to ``product/vendor``.
    kernel_out = args.out / args.source.relative_to(platform)
    external_relative = pathlib.PurePosixPath(
        os.path.relpath(args.external_module_root, args.source)
    )
    actual_output = (kernel_out / external_relative).resolve()
    alias = args.out / args.external_module_root.name
    actual_output.mkdir(parents=True, exist_ok=True)
    if alias.is_symlink():
        if alias.resolve() != actual_output:
            alias.unlink()
            alias.symlink_to(os.path.relpath(actual_output, alias.parent))
    elif alias.exists():
        if alias.is_dir():
            shutil.rmtree(alias)
            alias.symlink_to(os.path.relpath(actual_output, alias.parent))
        else:
            alias.unlink()
            alias.symlink_to(os.path.relpath(actual_output, alias.parent))
    else:
        alias.symlink_to(os.path.relpath(actual_output, alias.parent))

    # Several Qualcomm wrappers use the historical sibling name below
    # OUT_DIR/../sm8450-modules when locating a producer's Module.symvers.
    # The suffix they append is ``qcom/opensource/<module>``.  Therefore the
    # alias must point at the *obj/vendor* root, not at the already-qualified
    # ``obj/vendor/qcom/opensource`` directory (which would duplicate that
    # suffix and make the symbol dump appear missing).  Keep this
    # compatibility path inside the same Klee output transaction; it must
    # never point at the checked-in source tree.
    sibling_alias = args.out / "sm8450-modules"
    sibling_root = (args.out.parent / "vendor").resolve()
    sibling_root.mkdir(parents=True, exist_ok=True)
    if sibling_alias.is_symlink():
        if sibling_alias.resolve() != sibling_root:
            sibling_alias.unlink()
    elif sibling_alias.exists():
        raise RuntimeError(
            "refusing to replace non-symlink module output alias: "
            + str(sibling_alias)
        )
    if not sibling_alias.exists():
        sibling_alias.symlink_to(os.path.relpath(sibling_root, sibling_alias.parent))


def reset_platform_external_outputs(args, platform):
    """Remove only generated external-module output from the product tree."""
    # build.sh derives EXT_MOD_REL from the kernel source. With Qualcomm
    # projects living outside kernel_platform this places object files below
    # the product output's obj/vendor/ directory. Clear those exact derived
    # paths so removed modules and old Module.symvers files cannot leak
    # forward.
    product_root = args.out.parent.parent.resolve()
    kernel_out = args.out / args.source.relative_to(platform)
    for relative in args.external_module:
        module = args.external_module_root.joinpath(
            *pathlib.PurePosixPath(relative).parts
        ).resolve()
        generated = (
            kernel_out / os.path.relpath(module, args.source)
        ).resolve()
        try:
            generated.relative_to(product_root)
        except ValueError as error:
            raise RuntimeError(
                "external module output escapes product output: " + str(generated)
            ) from error
        if generated == product_root or generated == args.out.resolve():
            raise RuntimeError("refusing to clear kernel output root: " + str(generated))
        if generated.is_symlink() or generated.is_file():
            generated.unlink()
        elif generated.is_dir():
            shutil.rmtree(generated)


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


def load_dt_layout(path):
    """Load a device-owned DTB/DTBO assembly description."""
    data = path.read_bytes()
    try:
        layout = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"invalid UTF-8 DT layout {path}: {error}") from error

    if not isinstance(layout, dict) or layout.get("version") != 1:
        raise ValueError("DT layout must be an object with version 1")
    allowed = {
        "version",
        "page_size",
        "dtb_variants",
        "dtbo_variants",
        "allowed_dtb_selector_collisions",
    }
    unknown = sorted(set(layout) - allowed)
    if unknown:
        raise ValueError("unknown DT layout fields: " + ", ".join(unknown))
    for key in ("dtb_variants", "dtbo_variants"):
        variants = layout.get(key)
        if not isinstance(variants, list) or not variants:
            raise ValueError(f"DT layout requires a non-empty {key} list")
    page_size = layout.get("page_size", 4096)
    if (
        not isinstance(page_size, int)
        or isinstance(page_size, bool)
        or page_size < 512
        or page_size & (page_size - 1)
    ):
        raise ValueError("DT layout page_size must be a power of two >= 512")
    validate_dt_layout_schema(layout)
    return layout, hashlib.sha256(data).hexdigest()


def hash_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_retained_provenance(path, top):
    """Validate explicitly retained ABI-bound modules before packaging.

    Retained modules are not source-built and must never be silently replaced
    by an arbitrary file from a developer checkout. The device manifest is a
    small, reviewable exception list with immutable size and SHA-256 records.
    """
    if path is None:
        return
    path = path.resolve()
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"invalid retained-module provenance {path}: {error}") from error
    if not isinstance(data, dict) or data.get("version") != 1:
        raise ValueError("retained-module provenance must use version 1")
    entries = data.get("retained_prebuilt")
    if not isinstance(entries, list) or not entries:
        raise ValueError("retained-module provenance requires retained_prebuilt")
    seen = set()
    for entry in entries:
        if not isinstance(entry, dict):
            raise TypeError("retained-module provenance entries must be objects")
        name = entry.get("name")
        relative = entry.get("path")
        expected_hash = entry.get("sha256")
        expected_size = entry.get("size")
        if (
            not isinstance(name, str)
            or pathlib.PurePosixPath(name).name != name
            or pathlib.PurePosixPath(name).suffix != ".ko"
            or name in seen
        ):
            raise ValueError(f"invalid or duplicate retained module name: {name}")
        if (
            not isinstance(relative, str)
            or not relative
            or "\\" in relative
            or pathlib.PurePosixPath(relative).is_absolute()
            or ".." in pathlib.PurePosixPath(relative).parts
        ):
            raise ValueError(f"invalid retained module path for {name}: {relative}")
        if (
            not isinstance(expected_hash, str)
            or not re.fullmatch(r"[0-9a-f]{64}", expected_hash)
            or not isinstance(expected_size, int)
            or isinstance(expected_size, bool)
            or expected_size < 1
        ):
            raise ValueError(f"invalid retained module digest record for {name}")
        module = (top / pathlib.PurePosixPath(relative)).resolve()
        try:
            module.relative_to(top.resolve())
        except ValueError as error:
            raise ValueError(f"retained module escapes checkout: {relative}") from error
        if not module.is_file():
            raise FileNotFoundError(f"retained module is missing: {module}")
        actual_size = module.stat().st_size
        actual_hash = hash_file(module)
        if actual_size != expected_size or actual_hash != expected_hash:
            raise RuntimeError(
                f"retained module provenance mismatch for {name}: "
                f"expected {expected_size} bytes/{expected_hash}, "
                f"got {actual_size} bytes/{actual_hash}"
            )
        seen.add(name)


def validate_source_manifest(path, top, required_paths=()):
    """Require every declared Qualcomm source project to be at its pin."""
    if path is None:
        return
    path = path.resolve()
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"invalid Qualcomm source manifest {path}: {error}") from error
    if not isinstance(data, dict) or data.get("version") != 1:
        raise ValueError("Qualcomm source manifest must use version 1")
    projects = data.get("projects")
    if not isinstance(projects, list) or not projects:
        raise ValueError("Qualcomm source manifest requires projects")
    seen = set()
    declared_roots = set()
    for project in projects:
        if not isinstance(project, dict):
            raise TypeError("Qualcomm source manifest entries must be objects")
        relative = project.get("path")
        revision = project.get("revision")
        if (
            not isinstance(relative, str)
            or not relative
            or "\\" in relative
            or pathlib.PurePosixPath(relative).is_absolute()
            or ".." in pathlib.PurePosixPath(relative).parts
            or relative in seen
            or not isinstance(revision, str)
            or not re.fullmatch(r"[0-9a-f]{40}", revision)
        ):
            raise ValueError(f"invalid Qualcomm source manifest entry: {project}")
        source = (top / pathlib.PurePosixPath(relative)).resolve()
        try:
            source.relative_to(top.resolve())
        except ValueError as error:
            raise ValueError(f"source project escapes checkout: {relative}") from error
        if not source.is_dir():
            raise FileNotFoundError(f"Qualcomm source project is missing: {source}")
        declared_roots.add(source)
        try:
            actual = subprocess.check_output(
                ["git", "-C", str(source), "rev-parse", "HEAD"],
                text=True,
                stderr=subprocess.STDOUT,
            ).strip()
        except (OSError, subprocess.CalledProcessError) as error:
            raise RuntimeError(f"cannot inspect Qualcomm source project: {source}") from error
        if actual != revision:
            raise RuntimeError(
                f"Qualcomm source pin mismatch for {relative}: "
                f"expected {revision}, got {actual}"
            )
        try:
            dirty = subprocess.check_output(
                [
                    "git",
                    "-C",
                    str(source),
                    "status",
                    "--porcelain",
                    "--untracked-files=no",
                ],
                text=True,
                stderr=subprocess.STDOUT,
            ).strip()
        except (OSError, subprocess.CalledProcessError) as error:
            raise RuntimeError(f"cannot inspect source cleanliness: {source}") from error
        if dirty:
            raise RuntimeError(
                f"Qualcomm source project is dirty; refusing generated-source reuse: {relative}"
            )
        seen.add(relative)
    for required in required_paths:
        source = pathlib.Path(required).resolve()
        project_root = source
        while project_root != top.resolve() and not (project_root / ".git").exists():
            project_root = project_root.parent
        if project_root not in declared_roots:
            raise RuntimeError(
                "Qualcomm source project is not pinned by the source manifest: "
                + str(source)
            )


def verify_layout_unchanged(path, expected_digest):
    actual_digest = hash_file(path)
    if actual_digest != expected_digest:
        raise RuntimeError(
            "DT layout changed while the kernel was building: "
            f"expected {expected_digest}, got {actual_digest}"
        )


def validate_posix_relative_path(value, suffix, context):
    if not isinstance(value, str):
        raise TypeError(f"{context} paths must be strings")
    relative = pathlib.PurePosixPath(value)
    if (
        not value
        or "\\" in value
        or relative.is_absolute()
        or relative.as_posix() != value
        or any(part in ("", ".", "..") for part in relative.parts)
        or relative.suffix != suffix
    ):
        raise ValueError(f"invalid {suffix} path for {context}: {value}")
    return relative


def resolve_layout_artifact(root, value, suffix):
    """Resolve one generated Kbuild artifact without allowing tree escapes."""
    relative = validate_posix_relative_path(value, suffix, "DT layout artifact")
    path = root.joinpath(*relative.parts)
    if not path.is_file() or path.stat().st_size == 0:
        raise FileNotFoundError(f"kernel build did not produce DT artifact: {path}")
    try:
        path.resolve(strict=True).relative_to(root.resolve(strict=True))
    except ValueError as error:
        raise ValueError(f"DT artifact escapes generated tree: {value}") from error
    return path


def validate_variant_name(value, suffix):
    name = validate_posix_relative_path(value, suffix, "DT layout variant")
    if name.name != value:
        raise ValueError(f"invalid {suffix} variant name in DT layout: {value}")
    return value


def validate_cell_properties(properties, context):
    if properties is None:
        return {}
    if not isinstance(properties, dict):
        raise TypeError(f"{context} properties must be an object")
    for name, values in properties.items():
        if (
            not isinstance(name, str)
            or not name
            or "/" in name
            or "\x00" in name
        ):
            raise ValueError(f"{context} property names must be non-empty strings")
        if (
            not isinstance(values, list)
            or not values
            or any(
                not isinstance(value, int)
                or isinstance(value, bool)
                or value < 0
                or value > 0xFFFFFFFF
                for value in values
            )
        ):
            raise ValueError(
                f"{context} property {name} must contain 32-bit integer cells"
            )
    return properties


def validate_string_properties(properties, context):
    if properties is None:
        return {}
    if not isinstance(properties, dict):
        raise TypeError(f"{context} string properties must be an object")
    for name, values in properties.items():
        if (
            not isinstance(name, str)
            or not name
            or "/" in name
            or "\x00" in name
        ):
            raise ValueError(f"{context} property names must be non-empty strings")
        if (
            not isinstance(values, list)
            or not values
            or any(
                not isinstance(value, str) or not value or "\x00" in value
                for value in values
            )
        ):
            raise ValueError(
                f"{context} property {name} must contain non-empty strings"
            )
    return properties


def validate_node_path(value, context):
    if not isinstance(value, str):
        raise TypeError(f"{context} node paths must be strings")
    path = pathlib.PurePosixPath(value)
    if (
        not value.startswith("/")
        or (value != "/" and value.endswith("/"))
        or path.as_posix() != value
        or any(part in (".", "..") for part in path.parts)
    ):
        raise ValueError(f"invalid node path for {context}: {value}")
    return value


def validate_unique_string_list(values, context, validator=None):
    if values is None:
        return []
    if (
        not isinstance(values, list)
        or any(not isinstance(value, str) or not value for value in values)
        or len(set(values)) != len(values)
    ):
        raise ValueError(f"{context} must contain unique non-empty strings")
    if validator:
        for value in values:
            validator(value, context)
    return values


def validate_node_property_map(nodes, context, value_validator):
    if nodes is None:
        return {}
    if not isinstance(nodes, dict):
        raise TypeError(f"{context} must be an object keyed by node path")
    for node, properties in nodes.items():
        validate_node_path(node, context)
        value_validator(properties, f"{context} {node}")
    return nodes


def build_overlay_tools(top, args, make, jobs, env):
    """Build the pinned DTC utilities used by Klee's DT assembler."""
    source = top / "kernel_platform" / "external" / "dtc"
    makefile = source / "Makefile"
    if not makefile.is_file():
        raise FileNotFoundError(
            "tracked kernel_platform DTC tools are required for DT assembly: "
            + str(makefile)
        )
    output = args.out / ".klee-dtc-tools"
    output.mkdir(parents=True, exist_ok=True)
    names = ("fdtoverlay", "fdtoverlaymerge", "fdtget", "fdtput")
    tools = {name: output / name for name in names}
    run(
        [
            str(make),
            "-C",
            str(source),
            f"OUT_DIR={output}",
            "NO_PYTHON=1",
            "NO_YAML=1",
            f"-j{jobs}",
            *(str(tools[name]) for name in names),
        ],
        env,
    )
    missing = [str(path) for path in tools.values() if not path.is_file()]
    if missing:
        raise RuntimeError("DTC tool build is incomplete: " + ", ".join(missing))
    return tools


def resolve_mkdtimg(top):
    """Use only the mkdtimg prebuilt pinned by the Android manifest."""
    tool = top / "prebuilts" / "misc" / "linux-x86" / "libufdt" / "mkdtimg"
    if not tool.is_file():
        raise FileNotFoundError("pinned AOSP mkdtimg is missing: " + str(tool))
    return tool


def read_cell_property(fdtget, image, name, env, node="/"):
    command = [str(fdtget), "-t", "i", str(image), node, name]
    print("+", " ".join(command), flush=True)
    result = subprocess.run(
        command,
        env=env,
        check=True,
        text=True,
        stdout=subprocess.PIPE,
    )
    # fdtget prints signed cells for -t i. Normalize them back to the raw
    # 32-bit representation used by layout files.
    return [int(value, 0) & 0xFFFFFFFF for value in result.stdout.split()]


def read_string_property(fdtget, image, name, env, node="/"):
    command = [str(fdtget), "-t", "bx", str(image), node, name]
    print("+", " ".join(command), flush=True)
    result = subprocess.run(
        command,
        env=env,
        check=True,
        text=True,
        stdout=subprocess.PIPE,
    )
    try:
        raw = bytes(int(value, 16) for value in result.stdout.split())
        values = raw.rstrip(b"\0").split(b"\0")
        return [value.decode("utf-8") for value in values if value]
    except (ValueError, UnicodeDecodeError) as error:
        raise RuntimeError(
            f"invalid string property {node}/{name} in {image}"
        ) from error


def fdt_property_exists(fdtget, image, node, name, env):
    result = subprocess.run(
        [str(fdtget), str(image), node, name],
        env=env,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    return result.returncode == 0


def fdt_node_exists(fdtget, image, node, env):
    result = subprocess.run(
        [str(fdtget), "-p", str(image), node],
        env=env,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    return result.returncode == 0


def verify_cell_properties(fdtget, image, node, properties, env, context):
    expected = validate_cell_properties(properties, context)
    for name, values in expected.items():
        actual = read_cell_property(fdtget, image, name, env, node)
        if actual != values:
            raise RuntimeError(
                f"{context} {image.name} property {node}/{name} mismatch: "
                f"expected {values}, got {actual}"
            )


def verify_root_properties(fdtget, image, properties, env, context):
    verify_cell_properties(fdtget, image, "/", properties, env, context)


def verify_string_properties(fdtget, image, node, properties, env, context):
    expected = validate_string_properties(properties, context)
    for name, values in expected.items():
        actual = read_string_property(fdtget, image, name, env, node)
        if actual != values:
            raise RuntimeError(
                f"{context} {image.name} property {node}/{name} mismatch: "
                f"expected {values}, got {actual}"
            )


def verify_dtb_semantics(fdtget, image, variant, env):
    name = variant["name"]
    verify_root_properties(
        fdtget,
        image,
        variant.get("expected_root_properties"),
        env,
        f"DTB variant {name} root",
    )
    verify_string_properties(
        fdtget,
        image,
        "/",
        variant.get("expected_root_strings"),
        env,
        f"DTB variant {name} root",
    )
    for prop in variant.get("forbidden_root_properties", []):
        if fdt_property_exists(fdtget, image, "/", prop, env):
            raise RuntimeError(
                f"DTB variant {name} contains forbidden root property {prop}"
            )
    for node in variant.get("required_nodes", []):
        if not fdt_node_exists(fdtget, image, node, env):
            raise RuntimeError(f"DTB variant {name} is missing required node {node}")
    for node in variant.get("forbidden_nodes", []):
        if fdt_node_exists(fdtget, image, node, env):
            raise RuntimeError(f"DTB variant {name} contains forbidden node {node}")
    for node, properties in variant.get("expected_node_properties", {}).items():
        verify_cell_properties(
            fdtget,
            image,
            node,
            properties,
            env,
            f"DTB variant {name} node",
        )
    for node, properties in variant.get("expected_node_strings", {}).items():
        verify_string_properties(
            fdtget,
            image,
            node,
            properties,
            env,
            f"DTB variant {name} node",
        )


def apply_root_properties(fdtput, fdtget, image, properties, env):
    values_by_name = validate_cell_properties(properties, "DTBO root")
    for name, values in values_by_name.items():
        run(
            [str(fdtput), "-t", "i", str(image), "/", name]
            + [str(value) for value in values],
            env,
        )
    verify_root_properties(
        fdtget, image, values_by_name, env, "DTBO root"
    )


def parse_dtbo_metadata(variant):
    allowed = {
        "name",
        "base",
        "overlays",
        "root_properties",
        "expected_root_strings",
        "applies_to",
        "id",
        "rev",
        "custom",
    }
    unknown = sorted(set(variant) - allowed)
    if unknown:
        raise ValueError("unknown DTBO variant fields: " + ", ".join(unknown))

    metadata = []
    for key in ("id", "rev"):
        value = variant.get(key, 0)
        if (
            not isinstance(value, int)
            or isinstance(value, bool)
            or value < 0
            or value > 0xFFFFFFFF
        ):
            raise ValueError(f"DTBO {key} must be a 32-bit unsigned integer")
        metadata.append(f"--{key}={value}")
    custom = variant.get("custom", [0, 0, 0, 0])
    if (
        not isinstance(custom, list)
        or len(custom) != 4
        or any(
            not isinstance(value, int)
            or isinstance(value, bool)
            or value < 0
            or value > 0xFFFFFFFF
            for value in custom
        )
    ):
        raise ValueError("DTBO custom must contain exactly four 32-bit cells")
    metadata.extend(f"--custom{index}={value}" for index, value in enumerate(custom))
    return metadata


def validate_dt_layout_schema(layout):
    """Reject incomplete or ambiguous layouts before a long kernel build."""
    dtb_allowed = {
        "name",
        "base",
        "overlays",
        "dtbo_required",
        "expected_root_properties",
        "expected_root_strings",
        "forbidden_root_properties",
        "required_nodes",
        "forbidden_nodes",
        "expected_node_properties",
        "expected_node_strings",
    }
    dtb_names = []
    for variant in layout["dtb_variants"]:
        if not isinstance(variant, dict):
            raise TypeError("each DTB variant must be an object")
        unknown = sorted(set(variant) - dtb_allowed)
        if unknown:
            raise ValueError("unknown DTB variant fields: " + ", ".join(unknown))
        name = validate_variant_name(variant.get("name"), ".dtb")
        if name in dtb_names:
            raise ValueError(f"duplicate DTB variant name: {name}")
        dtb_names.append(name)
        validate_posix_relative_path(
            variant.get("base"), ".dtb", f"DTB variant {name} base"
        )
        dtbo_required = variant.get("dtbo_required", True)
        if not isinstance(dtbo_required, bool):
            raise TypeError(f"DTB variant {name} dtbo_required must be boolean")
        overlays = validate_unique_string_list(
            variant.get("overlays", []), f"DTB variant {name} overlays"
        )
        for overlay in overlays:
            validate_posix_relative_path(
                overlay, ".dtbo", f"DTB variant {name} overlay"
            )
        expected_root = validate_cell_properties(
            variant.get("expected_root_properties"), f"DTB variant {name} root"
        )
        if not expected_root:
            raise ValueError(
                f"DTB variant {name} requires expected_root_properties"
            )
        validate_string_properties(
            variant.get("expected_root_strings"), f"DTB variant {name} root"
        )
        validate_unique_string_list(
            variant.get("forbidden_root_properties"),
            f"DTB variant {name} forbidden root properties",
        )
        validate_unique_string_list(
            variant.get("required_nodes"),
            f"DTB variant {name} required nodes",
            validate_node_path,
        )
        validate_unique_string_list(
            variant.get("forbidden_nodes"),
            f"DTB variant {name} forbidden nodes",
            validate_node_path,
        )
        validate_node_property_map(
            variant.get("expected_node_properties"),
            f"DTB variant {name} node properties",
            validate_cell_properties,
        )
        validate_node_property_map(
            variant.get("expected_node_strings"),
            f"DTB variant {name} node strings",
            validate_string_properties,
        )

    coverage = {name: [] for name in dtb_names}
    dtbo_names = []
    for variant in layout["dtbo_variants"]:
        if not isinstance(variant, dict):
            raise TypeError("each DTBO variant must be an object")
        parse_dtbo_metadata(variant)
        name = validate_variant_name(variant.get("name"), ".dtbo")
        if name in dtbo_names:
            raise ValueError(f"duplicate DTBO variant name: {name}")
        dtbo_names.append(name)
        base = validate_posix_relative_path(
            variant.get("base"), ".dtbo", f"DTBO variant {name} base"
        ).as_posix()
        overlays = validate_unique_string_list(
            variant.get("overlays"), f"DTBO variant {name} overlays"
        )
        if not overlays:
            raise ValueError(f"DTBO variant {name} requires overlay inputs")
        for overlay in overlays:
            validate_posix_relative_path(
                overlay, ".dtbo", f"DTBO variant {name} overlay"
            )
        if base in overlays:
            raise ValueError(f"DTBO variant {name} repeats its base as an overlay")
        root_properties = validate_cell_properties(
            variant.get("root_properties"), f"DTBO variant {name} root"
        )
        if not root_properties:
            raise ValueError(f"DTBO variant {name} requires root_properties")
        validate_string_properties(
            variant.get("expected_root_strings"), f"DTBO variant {name} root"
        )
        applies_to = validate_unique_string_list(
            variant.get("applies_to"), f"DTBO variant {name} applies_to"
        )
        if not applies_to:
            raise ValueError(f"DTBO variant {name} requires applies_to DTBs")
        for dtb_name in applies_to:
            validate_variant_name(dtb_name, ".dtb")
            if dtb_name not in coverage:
                raise ValueError(
                    f"DTBO variant {name} references unknown DTB {dtb_name}"
                )
            coverage[dtb_name].append(name)

    required_names = {
        variant["name"]
        for variant in layout["dtb_variants"]
        if variant.get("dtbo_required", True)
    }
    invalid_coverage = {
        name: owners
        for name, owners in coverage.items()
        if (len(owners) != 1 if name in required_names else len(owners) > 1)
    }
    if invalid_coverage:
        details = ", ".join(
            f"{name}={owners}" for name, owners in invalid_coverage.items()
        )
        raise ValueError("every DTB must belong to exactly one DTBO: " + details)

    collisions = layout.get("allowed_dtb_selector_collisions", [])
    if not isinstance(collisions, list):
        raise TypeError("allowed_dtb_selector_collisions must be a list")
    seen_groups = set()
    seen_members = set()
    order = {name: index for index, name in enumerate(dtb_names)}
    for group in collisions:
        names = validate_unique_string_list(
            group, "allowed DTB selector collision"
        )
        if len(names) < 2 or any(name not in order for name in names):
            raise ValueError(
                "allowed DTB selector collisions require at least two known DTBs"
            )
        if names != sorted(names, key=order.get):
            raise ValueError(
                "allowed DTB selector collision members must follow DTB order"
            )
        key = tuple(names)
        if key in seen_groups or seen_members.intersection(names):
            raise ValueError("DTB selector collision declarations overlap")
        seen_groups.add(key)
        seen_members.update(names)


def split_msm_ids(values, context):
    if not values or len(values) % 2:
        raise RuntimeError(f"{context} qcom,msm-id must contain value/revision pairs")
    return {tuple(values[index : index + 2]) for index in range(0, len(values), 2)}


def verify_dtb_selector_collisions(fdtget, dtbs, layout, env):
    groups = {}
    for name, image in dtbs.items():
        signature = (
            tuple(read_string_property(fdtget, image, "compatible", env)),
            tuple(read_cell_property(fdtget, image, "qcom,msm-id", env)),
            tuple(read_cell_property(fdtget, image, "qcom,board-id", env)),
        )
        groups.setdefault(signature, []).append(name)

    actual = {
        tuple(names) for names in groups.values() if len(names) > 1
    }
    allowed = {
        tuple(names)
        for names in layout.get("allowed_dtb_selector_collisions", [])
    }
    if actual != allowed:
        raise RuntimeError(
            "DTB selector collisions differ from the device declaration: "
            f"expected {sorted(allowed)}, got {sorted(actual)}"
        )


def validate_fdt_blob(data, context):
    if len(data) < 8:
        raise RuntimeError(f"{context} is too small to contain an FDT")
    magic, total_size = struct.unpack_from(">II", data)
    if magic != 0xD00DFEED:
        raise RuntimeError(f"{context} has invalid FDT magic 0x{magic:08x}")
    if total_size != len(data):
        raise RuntimeError(
            f"{context} FDT size mismatch: header {total_size}, file {len(data)}"
        )


def verify_concatenated_dtbs(image, variants):
    actual = image.read_bytes()
    expected_parts = []
    for variant in variants:
        data = variant.read_bytes()
        validate_fdt_blob(data, str(variant))
        expected_parts.append(data)
    expected = b"".join(expected_parts)
    if actual != expected:
        raise RuntimeError(
            "dtb.img is not the exact ordered concatenation of declared DTBs"
        )


def verify_dtbo_table(image, layout, entries):
    data = image.read_bytes()
    if len(data) < 32:
        raise RuntimeError("dtbo.img is smaller than its table header")
    (
        magic,
        total_size,
        header_size,
        entry_size,
        entry_count,
        entries_offset,
        page_size,
        version,
    ) = struct.unpack_from(">8I", data)
    expected_page_size = layout.get("page_size", 4096)
    expected_header = (
        0xD7B7AB1E,
        len(data),
        32,
        32,
        len(entries),
        32,
        expected_page_size,
        0,
    )
    actual_header = (
        magic,
        total_size,
        header_size,
        entry_size,
        entry_count,
        entries_offset,
        page_size,
        version,
    )
    if actual_header != expected_header:
        raise RuntimeError(
            f"dtbo.img table header mismatch: expected {expected_header}, "
            f"got {actual_header}"
        )
    table_end = entries_offset + entry_count * entry_size
    if table_end > len(data):
        raise RuntimeError("dtbo.img entry table extends past the image")

    previous_end = table_end
    for index, (variant, expected_image) in enumerate(entries):
        offset = entries_offset + index * entry_size
        values = struct.unpack_from(">8I", data, offset)
        dt_size, dt_offset, entry_id, revision, *custom = values
        # mkdtimg stores the table entries and their FDT payloads back to back.
        # page_size is DT table metadata for consumers; it is not padding
        # between individual payloads.
        expected_offset = previous_end
        if dt_offset != expected_offset:
            raise RuntimeError(
                f"dtbo.img entry {index} offset mismatch: "
                f"expected {expected_offset}, got {dt_offset}"
            )
        if dt_offset + dt_size > len(data):
            raise RuntimeError(f"dtbo.img entry {index} extends past the image")
        expected_data = expected_image.read_bytes()
        actual_data = data[dt_offset : dt_offset + dt_size]
        validate_fdt_blob(actual_data, f"dtbo.img entry {index}")
        if actual_data != expected_data:
            raise RuntimeError(
                f"dtbo.img entry {index} does not match {expected_image.name}"
            )
        expected_metadata = (
            variant.get("id", 0),
            variant.get("rev", 0),
            *variant.get("custom", [0, 0, 0, 0]),
        )
        if (entry_id, revision, *custom) != expected_metadata:
            raise RuntimeError(
                f"dtbo.img entry {index} metadata mismatch: "
                f"expected {expected_metadata}, got {(entry_id, revision, *custom)}"
            )
        previous_end = dt_offset + dt_size
    if previous_end != len(data):
        raise RuntimeError(
            f"dtbo.img has {len(data) - previous_end} unexpected trailing bytes"
        )


def package_dt_layout(
    args, top, make, jobs, env, dts_root, layout, layout_digest
):
    """Assemble source DTBs and separated DTBOs from a device layout."""
    if not args.dt_layout:
        return
    if args.dtb_base or args.dtb_overlay or args.dtbo_target:
        raise ValueError(
            "--dt-layout cannot be combined with legacy DTB lists or --dtbo-target"
        )
    if not args.dtb_output or not args.dtbo_output:
        raise ValueError("--dt-layout requires --dtb-output and --dtbo-output")

    verify_layout_unchanged(args.dt_layout, layout_digest)
    tools = build_overlay_tools(top, args, make, jobs, env)

    dtb_dir = args.dtb_output.parent / "dtb"
    if dtb_dir.exists():
        shutil.rmtree(dtb_dir)
    dtb_dir.mkdir(parents=True)
    dtbs = {}
    ordered_dtbs = []
    for variant in layout["dtb_variants"]:
        name = variant["name"]
        base = resolve_layout_artifact(dts_root, variant.get("base"), ".dtb")
        overlay_values = variant.get("overlays", [])
        overlays = [
            resolve_layout_artifact(dts_root, value, ".dtbo")
            for value in overlay_values
        ]
        output = dtb_dir / name
        if overlays:
            run(
                [
                    str(tools["fdtoverlay"]),
                    "-i",
                    str(base),
                    "-o",
                    str(output),
                    *(str(overlay) for overlay in overlays),
                ],
                env,
            )
        else:
            shutil.copy2(base, output)
        verify_dtb_semantics(tools["fdtget"], output, variant, env)
        dtbs[name] = output
        ordered_dtbs.append(output)

    verify_dtb_selector_collisions(tools["fdtget"], dtbs, layout, env)

    args.dtb_output.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{args.dtb_output.name}.", dir=args.dtb_output.parent
    )
    dtb_temporary = pathlib.Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as image:
            for variant in ordered_dtbs:
                with variant.open("rb") as source:
                    shutil.copyfileobj(source, image)
        if dtb_temporary.stat().st_size == 0:
            raise RuntimeError("source DT layout produced an empty dtb.img")
        verify_concatenated_dtbs(dtb_temporary, ordered_dtbs)
        os.replace(dtb_temporary, args.dtb_output)
    finally:
        if dtb_temporary.exists():
            dtb_temporary.unlink()

    dtbo_dir = args.dtbo_output.parent / "dtbo"
    validation_dir = dtbo_dir / ".validated"
    if dtbo_dir.exists():
        shutil.rmtree(dtbo_dir)
    dtbo_dir.mkdir(parents=True)
    ordered_dtbos = []
    claimed_msm_ids = set()
    for variant in layout["dtbo_variants"]:
        metadata = parse_dtbo_metadata(variant)
        name = variant["name"]
        base = resolve_layout_artifact(dts_root, variant.get("base"), ".dtbo")
        overlay_values = variant.get("overlays", [])
        applies_to = variant.get("applies_to", [])
        overlays = [
            resolve_layout_artifact(dts_root, value, ".dtbo")
            for value in overlay_values
        ]
        output = dtbo_dir / name
        run(
            [
                str(tools["fdtoverlaymerge"]),
                "-i",
                str(base),
                *(str(overlay) for overlay in overlays),
                "-o",
                str(output),
            ],
            env,
        )
        apply_root_properties(
            tools["fdtput"],
            tools["fdtget"],
            output,
            variant.get("root_properties"),
            env,
        )
        verify_string_properties(
            tools["fdtget"],
            output,
            "/",
            variant.get("expected_root_strings"),
            env,
            f"DTBO variant {name} root",
        )
        actual_msm_ids = split_msm_ids(
            read_cell_property(
                tools["fdtget"], output, "qcom,msm-id", env
            ),
            f"DTBO variant {name}",
        )
        expected_msm_ids = set()
        for dtb_name in applies_to:
            expected_msm_ids.update(
                split_msm_ids(
                    read_cell_property(
                        tools["fdtget"],
                        dtbs[dtb_name],
                        "qcom,msm-id",
                        env,
                    ),
                    f"DTB variant {dtb_name}",
                )
            )
        if actual_msm_ids != expected_msm_ids:
            raise RuntimeError(
                f"DTBO variant {name} selectors do not match applies_to: "
                f"expected {sorted(expected_msm_ids)}, got {sorted(actual_msm_ids)}"
            )
        overlap = claimed_msm_ids.intersection(actual_msm_ids)
        if overlap:
            raise RuntimeError(
                f"DTBO variant {name} reuses MSM selectors {sorted(overlap)}"
            )
        claimed_msm_ids.update(actual_msm_ids)
        validation_dir.mkdir(parents=True, exist_ok=True)
        for dtb_name in applies_to:
            run(
                [
                    str(tools["fdtoverlay"]),
                    "-i",
                    str(dtbs[dtb_name]),
                    "-o",
                    str(validation_dir / f"{name}-{dtb_name}"),
                    str(output),
                ],
                env,
            )
        ordered_dtbos.append((variant, metadata, output))
    if validation_dir.exists():
        shutil.rmtree(validation_dir)

    args.dtbo_output.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{args.dtbo_output.name}.", dir=args.dtbo_output.parent
    )
    os.close(descriptor)
    dtbo_temporary = pathlib.Path(temporary_name)
    try:
        mkdtimg = resolve_mkdtimg(top)
        mkdtimg_command = [
            str(mkdtimg),
            "create",
            str(dtbo_temporary),
            f"--page_size={layout.get('page_size', 4096)}",
            "--version=0",
        ]
        for _, metadata, image in ordered_dtbos:
            mkdtimg_command.append(str(image))
            mkdtimg_command.extend(metadata)
        run(mkdtimg_command, env)
        if not dtbo_temporary.is_file() or dtbo_temporary.stat().st_size == 0:
            raise RuntimeError("source DT layout produced an empty dtbo.img")
        if (
            args.dtbo_max_size is not None
            and dtbo_temporary.stat().st_size > args.dtbo_max_size
        ):
            raise RuntimeError(
                "source-built DTBO image exceeds partition capacity: "
                f"{dtbo_temporary.stat().st_size} > {args.dtbo_max_size}"
            )
        verify_dtbo_table(
            dtbo_temporary,
            layout,
            [(variant, image) for variant, _, image in ordered_dtbos],
        )
        run([str(mkdtimg), "dump", str(dtbo_temporary)], env)
        verify_layout_unchanged(args.dt_layout, layout_digest)
        os.replace(dtbo_temporary, args.dtbo_output)
    finally:
        if dtbo_temporary.exists():
            dtbo_temporary.unlink()


def validate_source_dtb_tree(args, platform=None):
    """Require Kbuild to see exactly the device-owned DTS tree."""
    source_root = args.dtb_source_root
    if source_root is None:
        source_root = (
            args.source / "arch" / args.arch / "boot" / "dts" / "vendor"
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

    compiled_root = (
        args.source / "arch" / args.arch / "boot" / "dts" / "vendor"
    )
    if compiled_root.resolve() != source_root:
        mode = "kernel platform" if platform is not None else "kernel"
        raise RuntimeError(
            f"{mode} DTS input does not resolve to the device-owned tree: "
            f"{compiled_root} -> {compiled_root.resolve()}, expected {source_root}"
        )
    return source_root


def platform_dtb_root(args):
    if not args.platform_root:
        raise RuntimeError("platform DT output requested without --platform-root")
    try:
        source_relative = args.source.relative_to(args.platform_root)
    except ValueError as error:
        raise ValueError("kernel source must be inside the kernel platform") from error
    return (
        args.out
        / source_relative
        / "arch"
        / args.arch
        / "boot"
        / "dts"
        / "vendor"
    )


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


def remove_path(path):
    if path is None:
        return
    if path.is_symlink() or path.is_file():
        path.unlink()
    elif path.is_dir():
        raise RuntimeError(f"refusing to recursively delete file output path: {path}")


def absolute_lexical_path(path):
    """Make an output path absolute without resolving its final component."""
    return pathlib.Path(os.path.abspath(os.fspath(path)))


def validate_output_leaf(path, roots, context):
    if path is None:
        return
    resolved_parent = path.parent.resolve()
    if not any(
        resolved_parent == root or root in resolved_parent.parents
        for root in roots
    ):
        raise ValueError(f"{context} is outside Klee output roots: {path}")
    if path.exists() and path.is_dir() and not path.is_symlink():
        raise RuntimeError(f"{context} must be a file output, not a directory: {path}")


def acquire_build_locks(roots):
    """Serialize independent Klee builders that share out/dist trees."""
    # Build execution is Linux-only; keep pure layout validation importable
    # on other hosts so device authors can run the unit tests locally.
    import fcntl

    handles = []
    lock_paths = {
        root.parent / f".{root.name}.klee-build.lock" for root in roots
    }
    for lock_path in sorted(lock_paths, key=lambda path: str(path)):
        lock_path.parent.mkdir(parents=True, exist_ok=True)
        handle = lock_path.open("a+b")
        print(f"Waiting for Klee kernel build lock: {lock_path}", flush=True)
        fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
        print(f"Acquired Klee kernel build lock: {lock_path}", flush=True)
        handles.append(handle)
    return handles


def layout_artifact_paths(layout, dts_root):
    """Return every Kbuild object named by a layout after validating paths."""
    paths = []
    for group, base_suffix in (("dtb_variants", ".dtb"), ("dtbo_variants", ".dtbo")):
        for variant in layout[group]:
            if not isinstance(variant, dict):
                raise TypeError(f"each {group} entry must be an object")
            values = [(variant.get("base"), base_suffix)]
            overlays = variant.get("overlays", [])
            values.extend((value, ".dtbo") for value in overlays)
            for value, suffix in values:
                relative = validate_posix_relative_path(
                    value, suffix, "DT layout artifact"
                )
                paths.append(dts_root.joinpath(*relative.parts))
    return sorted(set(paths))


def bundle_outputs(args):
    if args.platform_root or args.build_config:
        image = args.dist / args.image
    else:
        image = args.out / "arch" / args.arch / "boot" / args.image
    outputs = [image]
    outputs.extend(args.dist / "modules" / name for name in args.required_module)
    outputs.extend(path for path in (args.dtb_output, args.dtbo_output) if path)
    if args.dtbo_target and not args.dtbo_output:
        root = args.dist if args.platform_root else (
            args.out / "arch" / args.arch / "boot"
        )
        outputs.append(root / args.dtbo_target)
    return outputs


def finalize_bundle(args, layout_digest=None):
    """Validate all public outputs, refresh them, then publish the stamp."""
    outputs = bundle_outputs(args)
    missing = [str(path) for path in outputs if not path.is_file()]
    empty = [str(path) for path in outputs if path.is_file() and path.stat().st_size == 0]
    if missing:
        raise RuntimeError("kernel bundle is missing outputs: " + ", ".join(missing))
    if empty:
        raise RuntimeError("kernel bundle has empty outputs: " + ", ".join(empty))
    if layout_digest is not None:
        verify_layout_unchanged(args.dt_layout, layout_digest)
    # Every public output is removed before the build starts. Qualcomm's
    # build.sh legitimately preserves source mtimes when it copies cached
    # KERNEL_KIT files, and stage_kernel_modules intentionally does the same
    # for .ko files. Existence here therefore proves publication by this
    # invocation; comparing source mtimes to the invocation time would reject
    # valid incremental builds. Give all public outputs one publication time
    # only after the complete bundle has passed validation.
    published_ns = time.time_ns()
    for output in outputs:
        os.utime(output, ns=(published_ns, published_ns))

    if args.stamp:
        args.stamp.parent.mkdir(parents=True, exist_ok=True)
        temporary = args.stamp.with_name(args.stamp.name + f".tmp.{os.getpid()}")
        payload = {
            "version": 1,
            "published_ns": published_ns,
            "outputs": [str(path) for path in outputs],
        }
        temporary.write_text(
            json.dumps(payload, sort_keys=True, indent=2) + "\n", encoding="utf-8"
        )
        os.replace(temporary, args.stamp)


def build_kernel_platform(args, top, make, jobs, env, layout, layout_digest):
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

    if args.dt_layout and not args.dt_layout.is_file():
        raise FileNotFoundError("DT layout is missing: " + str(args.dt_layout))
    if args.dt_layout and args.skip_platform_dtbo:
        raise ValueError("an explicit DT layout requires platform DT overlay support")
    if args.dt_layout and (not args.dtb_output or not args.dtbo_output):
        raise ValueError("--dt-layout requires --dtb-output and --dtbo-output")
    if args.dtb_source_root or args.dtb_source_marker or args.dtb_base or args.dt_layout:
        validate_source_dtb_tree(args, platform)

    args.out.mkdir(parents=True, exist_ok=True)
    args.dist.mkdir(parents=True, exist_ok=True)
    # Never accept an image left by a previous build.  The Android makefiles
    # intentionally keep the kernel image and DT artifacts in one dist tree,
    # so an old DTBO can otherwise satisfy a target even when this invocation
    # did not compile the linked device DTS inputs.
    stale_outputs = [
        args.dist / args.image,
        args.dist / ".config",
        args.dist / "Module.symvers",
        args.dist / "modules.list",
        args.dtb_output,
        args.dtbo_output,
        args.stamp,
    ]
    if args.dtbo_target:
        stale_outputs.append(args.dist / args.dtbo_target)
    stale_outputs.extend(args.dist.glob("*.ko"))
    for output in set(path for path in stale_outputs if path is not None):
        remove_path(output)
    reset_module_install_tree(args.dist)
    dts_root = platform_dtb_root(args)
    generated_dt_artifacts = [
        dts_root / relative for relative in args.dtb_base + args.dtb_overlay
    ]
    if args.dt_layout:
        generated_dt_artifacts.extend(
            layout_artifact_paths(layout, dts_root)
        )
    for generated in set(generated_dt_artifacts):
        remove_path(generated)
    platform_env = env.copy()
    platform_env["BUILD_CONFIG"] = build_config.as_posix()
    platform_env["OUT_DIR"] = str(args.out)
    platform_env["DIST_DIR"] = str(args.dist)
    ordered_external_modules = order_external_modules(args.external_module)
    for inherited in (
        "EXT_MODULES",
        "SKIP_EXT_MODULES",
        "KBUILD_EXTRA_SYMBOLS",
        "DT_OVERLAY_SUPPORT",
        "SKIP_VENDOR_BOOT",
    ):
        platform_env.pop(inherited, None)
    if args.external_module:
        if not args.external_module_root:
            raise RuntimeError("external modules require --external-module-root")

        # Keep build.sh responsible for the kernel, DTB and base staging
        # transaction, but do not hand it a frozen list of future
        # Module.symvers files.  Kbuild treats that list as a dependency set;
        # a producer which has not run yet consequently becomes a hard
        # ``No rule to make target .../Module.symvers`` failure.  Klee runs
        # the external phase from DIST_CMDS instead, where its own topological
        # transaction can publish one symbol table before exposing it to the
        # next consumer.
        external_modules = []
        for relative in ordered_external_modules:
            module = args.external_module_root.joinpath(
                *pathlib.PurePosixPath(relative).parts
            )
            if not module.is_dir():
                raise FileNotFoundError(
                    f"external module directory not found: {module}"
                )
            # Preserve the lexical path passed by the device so Klee's
            # staging copy remains deterministic.  SKIP_EXT_MODULES below
            # prevents build.sh from invoking any wrapper itself; EXT_MODULES
            # is retained solely so create_modules_staging includes the
            # ``extra`` tree populated by Klee's phase.
            external_modules.append(
                pathlib.PurePosixPath(
                    os.path.relpath(module, platform)
                ).as_posix()
            )
        platform_env["EXT_MODULES"] = " ".join(external_modules)
        platform_env["SKIP_EXT_MODULES"] = "1"
        kernel_output = args.out / args.source.relative_to(platform)
        reset_platform_external_outputs(args, platform)
        prepare_platform_external_output_alias(args, platform)
        external_script = write_platform_external_module_script(
            args,
            make,
            jobs,
            ordered_external_modules,
            kernel_output,
        )
        if external_script is not None:
            # build.config.msm.common appends prepare_vendor_dlkm to
            # DIST_CMDS. Prefixing our command here makes the freshly
            # installed external modules visible to both initramfs and
            # vendor_dlkm image creation without modifying the platform
            # builder or importing an upstream product rule.
            module_phase = (
                f"{shlex.quote(str(external_script))} \"$MODULES_STAGING_DIR\""
            )
            inherited_dist = platform_env.get("DIST_CMDS", "").strip()
            platform_env["DIST_CMDS"] = (
                f"{inherited_dist} && {module_phase}"
                if inherited_dist
                else module_phase
            )
    if args.dt_layout:
        platform_env["DT_OVERLAY_SUPPORT"] = "1"
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
    # Never pass KBUILD_EXTRA_SYMBOLS to the platform kernel invocation.  The
    # mixed build receives the GKI symbol universe through KBUILD_MIXED_TREE;
    # Klee's generated external phase adds only tables which already exist.
    platform_make_args = [
        str(value)
        for value in args.make_arg
        if not str(value).startswith("KBUILD_EXTRA_SYMBOLS=")
    ]
    run(
        [str(build_script), f"-j{jobs}", *platform_make_args],
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
    # The generated DIST_CMDS phase has already installed every selected
    # external module into build.sh's staging tree.  Do not invoke a second
    # post-build Kbuild here: that would bypass the transaction symbol set and
    # would leave vendor_dlkm out of sync with the modules it publishes.
    stage_kernel_modules(args.dist)
    if args.dt_layout:
        package_dt_layout(
            args,
            top,
            make,
            jobs,
            env,
            dts_root,
            layout,
            layout_digest,
        )
    else:
        validate_platform_dtbo(args)
        package_platform_dtb(args)
    finalize_bundle(args, layout_digest)


def main():
    args = parse_args()
    top = pathlib.Path.cwd().resolve()
    args.source = args.source.resolve()
    args.out = absolute_lexical_path(args.out)
    args.dist = absolute_lexical_path(args.dist)
    if args.external_module_root:
        args.external_module_root = args.external_module_root.resolve()
    if args.dtb_output:
        args.dtb_output = absolute_lexical_path(args.dtb_output)
    if args.dtbo_output:
        args.dtbo_output = absolute_lexical_path(args.dtbo_output)
    if args.dt_layout:
        args.dt_layout = args.dt_layout.resolve()
    if args.dtb_source_root:
        args.dtb_source_root = args.dtb_source_root.resolve()
    if args.platform_root:
        args.platform_root = args.platform_root.resolve()
    if args.stamp:
        args.stamp = absolute_lexical_path(args.stamp)
    if args.retained_provenance:
        args.retained_provenance = args.retained_provenance.resolve()
    if args.source_manifest:
        args.source_manifest = args.source_manifest.resolve()

    if len(set(args.required_module)) != len(args.required_module):
        raise ValueError("required kernel module names must be unique")
    for name in args.required_module:
        path = pathlib.PurePosixPath(name)
        if path.name != name or path.suffix != ".ko":
            raise ValueError(f"invalid required kernel module basename: {name}")

    if len(set(args.external_module)) != len(args.external_module):
        raise ValueError("external kernel module paths must be unique")
    for value in args.external_module:
        path = pathlib.PurePosixPath(value)
        if not value or path.is_absolute() or ".." in path.parts:
            raise ValueError(f"invalid external kernel module path: {value}")
    if args.dtbo_target:
        target = pathlib.PurePosixPath(args.dtbo_target)
        if target.name != args.dtbo_target or target.suffix != ".img":
            raise ValueError(f"invalid DTBO target name: {args.dtbo_target}")

    validate_retained_provenance(args.retained_provenance, top)
    required_source_paths = []
    if args.external_module_root:
        required_source_paths = [
            args.external_module_root.joinpath(
                *pathlib.PurePosixPath(relative).parts
            )
            for relative in args.external_module
        ]
    validate_source_manifest(args.source_manifest, top, required_source_paths)

    layout = None
    layout_digest = None
    if args.dt_layout:
        if not args.dt_layout.is_file():
            raise FileNotFoundError("DT layout is missing: " + str(args.dt_layout))
        layout, layout_digest = load_dt_layout(args.dt_layout)

    args.out.mkdir(parents=True, exist_ok=True)
    args.dist.mkdir(parents=True, exist_ok=True)
    output_roots = (args.out.resolve(), args.dist.resolve())
    for output in bundle_outputs(args):
        validate_output_leaf(output, output_roots, "kernel bundle output")
    validate_output_leaf(args.stamp, output_roots, "kernel bundle stamp")

    # Keep these handles alive until process exit. The operating system drops
    # both flock locks on every success or exception path.
    build_lock_handles = acquire_build_locks(output_roots)
    if not build_lock_handles:
        raise RuntimeError("failed to acquire Klee kernel output locks")
    if args.stamp:
        # The stamp is the transaction marker. Once a builder invocation has
        # acquired exclusive ownership, no previous bundle may remain valid.
        remove_path(args.stamp)

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
        build_kernel_platform(
            args, top, make, jobs, env, layout, layout_digest
        )
        return

    if args.dt_layout and not args.dt_layout.is_file():
        raise FileNotFoundError("DT layout is missing: " + str(args.dt_layout))
    if args.dtb_source_root or args.dtb_source_marker or args.dt_layout:
        validate_source_dtb_tree(args)
    args.out.mkdir(parents=True, exist_ok=True)
    args.dist.mkdir(parents=True, exist_ok=True)
    for output in set(bundle_outputs(args)):
        remove_path(output)
    dts_root = args.out / "arch" / args.arch / "boot" / "dts" / "vendor"
    if layout:
        for generated in layout_artifact_paths(layout, dts_root):
            remove_path(generated)
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
    if args.dtbo_target and not args.dt_layout:
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
    if args.dt_layout:
        package_dt_layout(
            args,
            top,
            make,
            jobs,
            env,
            dts_root,
            layout,
            layout_digest,
        )
    else:
        package_merged_dtb(args, env)
    finalize_bundle(args, layout_digest)


if __name__ == "__main__":
    main()
