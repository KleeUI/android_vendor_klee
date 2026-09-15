#!/usr/bin/env python3
# Copyright (C) 2026 The KleeUI Project
# SPDX-License-Identifier: Apache-2.0

"""Remove stale kernel modules from Klee's incremental packaging outputs.

Android's module packaging stages files in directories and does not always
remove a module that disappeared from BOARD_*_KERNEL_MODULES.  A stale module
can therefore survive into vendor_boot even though it is absent from the
current source manifest.  This helper prunes only the product-local staging
directories described by the device manifest, before Ninja starts.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path


METADATA_NAMES = {
    "modules.alias",
    "modules.alias.bin",
    "modules.dep",
    "modules.dep.bin",
    "modules.devname",
    "modules.load",
    "modules.load.cupid",
    "modules.softdep",
    "modules.symbols",
    "modules.symbols.bin",
}


def module_names(path: Path) -> set[str]:
    names: set[str] = set()
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        value = line.split("#", 1)[0].strip()
        if value.endswith(".ko"):
            names.add(Path(value).name)
    return names


def prune_directory(path: Path, allowed: set[str]) -> tuple[int, int]:
    if not path.is_dir():
        return 0, 0

    removed_modules = 0
    removed_metadata = 0
    for entry in path.iterdir():
        if not (entry.is_file() or entry.is_symlink()):
            continue
        if entry.name.endswith(".ko") and entry.name not in allowed:
            entry.unlink()
            removed_modules += 1
        elif entry.name in METADATA_NAMES:
            entry.unlink()
            removed_metadata += 1
    return removed_modules, removed_metadata


def invalidate_outputs(product_out: Path, names: list[str]) -> list[str]:
    """Remove image outputs that embed the staged module directories.

    The packaging rules consume directory contents, but the directory cleanup
    itself is not a Ninja edge.  Removing an old module therefore does not
    necessarily make an already-built image dirty.  Delete the affected image
    outputs before Ninja starts so the current staging contract is always
    reflected in the image.
    """

    removed: list[str] = []
    for name in names:
        output = product_out / name
        if output.is_file() or output.is_symlink():
            output.unlink()
            removed.append(name)
    return removed


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--top", required=True, type=Path)
    parser.add_argument("--product-out", required=True, type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    args = parser.parse_args()

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    if manifest.get("schema") != 1:
        raise SystemExit("unsupported kernel-module-staging schema")

    allowed: set[str] = set()
    for relative in manifest.get("module_lists", []):
        source = args.top / relative
        if not source.is_file():
            raise SystemExit(f"missing kernel module list: {source}")
        allowed.update(module_names(source))
    if not allowed:
        raise SystemExit("kernel module staging allowlist is empty")

    removed_modules = 0
    removed_metadata = 0
    visited = 0
    for relative in manifest.get("product_staging_dirs", []):
        directory = args.product_out / relative
        modules, metadata = prune_directory(directory, allowed)
        if directory.is_dir():
            visited += 1
        removed_modules += modules
        removed_metadata += metadata

    invalidated = invalidate_outputs(
        args.product_out,
        [str(name) for name in manifest.get("invalidate_outputs", [])],
    )

    print(
        "KLEE_KERNEL_STAGING_PRUNE "
        f"allowed={len(allowed)} visited={visited} "
        f"removed_modules={removed_modules} removed_metadata={removed_metadata} "
        f"invalidated={','.join(invalidated) if invalidated else 'none'}",
        flush=True,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
