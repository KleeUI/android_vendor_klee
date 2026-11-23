#!/usr/bin/env python3
"""Execute the generated config hooks with real filesystem output aliases."""

import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest


BUILDER = Path(os.environ.get(
    "KLEE_BUILD_KERNEL_UNDER_TEST", str(Path(__file__).with_name("build_kernel.py"))
))
spec = importlib.util.spec_from_file_location("build_kernel", BUILDER)
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


@unittest.skipUnless(sys.platform == "linux" and shutil.which("bash"),
                     "Generated kernel hooks require Linux and bash")
class ConfigHookTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="klee-config-hooks-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.platform = self.root / "kernel_platform"
        self.source = self.platform / "msm-kernel"
        (self.source / "scripts").mkdir(parents=True)
        self.output = self.root / "physical output"
        self.output.mkdir()
        self.alias = self.root / "out alias"
        self.alias.symlink_to(self.output, target_is_directory=True)
        self.log = self.root / "calls.jsonl"
        self.bin = self.root / "bin"
        self.bin.mkdir()
        # Record the actual argv received by the subprocesses. The tests exercise
        # bash/readlink/symlinks, without compiling a kernel or mocking pathlib.
        recorder = (
            "#!" + sys.executable + "\n"
            "import json, os, sys\n"
            "with open(os.environ['KLEE_HOOK_TEST_LOG'], 'a') as stream:\n"
            "    stream.write(json.dumps(sys.argv) + '\\n')\n"
        )
        for path in (self.source / "scripts/config", self.bin / "make"):
            path.write_text(recorder)
            path.chmod(0o755)
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                        KLEE_HOOK_TEST_LOG=str(self.log))

    def generate(self, output, thermal=True, wlan=True):
        args = SimpleNamespace(out=output, source=self.source, platform_root=self.platform,
                               required_module=["mi_thermal_interface.ko"] if thermal else [],
                               external_module=["wlan/platform"] if wlan else [])
        env = {}
        builder.configure_cupid_source_module_configs(args, self.platform, env)
        builder.configure_external_spec_sync_provider(args, self.platform, env)
        self.assertEqual(args.out, output)
        return args, env

    def execute(self, output, current):
        env = dict(self.env)
        if current is None:
            env.pop("OUT_DIR", None)
        else:
            env["OUT_DIR"] = str(current)
        for name in (".klee-enable-cupid-source-modules.sh", ".klee-disable-legacy-cnss.sh"):
            path = output / name
            if path.exists():
                subprocess.run(["bash", str(path)], cwd=self.platform, env=env,
                               check=True, capture_output=True, text=True)
        if not self.log.exists():
            return []
        return [json.loads(line) for line in self.log.read_text().splitlines()]

    def assert_outer_actions(self, calls):
        self.assertEqual(len(calls), 4, "Both outer hooks must configure and run olddefconfig")
        configs = calls[::2]
        for call in configs:
            self.assertEqual(Path(call[0]), self.source / "scripts/config")
            self.assertEqual(call[1:3], ["--file", str(self.output / "msm-kernel/.config")])
        for symbol in ("MTD", "MTD_BLOCK", "MTD_BLOCK2MTD", "MTD_OOPS", "MTD_OF_PARTS",
                       "IR_SPI", "USB_NET_AX8817X", "USB_NET_AX88179_178A",
                       "REGULATOR_FAN53555", "MI_THERMAL_INTERFACE"):
            index = configs[0].index(symbol)
            self.assertEqual(configs[0][index - 1], "-m")
        for symbol in ("CNSS2", "CNSS_UTILS", "CNSS_GENL", "CNSS_PLAT_IPC_QMI_SVC", "ICNSS2"):
            index = configs[1].index(symbol)
            self.assertEqual(configs[1][index - 1], "-d")
        for call in calls[1::2]:
            self.assertEqual(call[1:], ["-C", str(self.source),
                                       "O=" + str(self.output / "msm-kernel"), "olddefconfig"])

    def test_outer_output_accepts_alias_physical_and_relative_paths(self):
        self.generate(self.alias)
        for current in (self.alias / "msm-kernel", self.output / "msm-kernel",
                        Path("../out alias/msm-kernel")):
            with self.subTest(current=str(current)):
                self.log.unlink(missing_ok=True)
                self.assert_outer_actions(self.execute(self.alias, current))

    def test_outer_output_without_alias(self):
        self.generate(self.output)
        self.assert_outer_actions(self.execute(self.output, self.output / "msm-kernel"))

    def test_nested_and_unrelated_outputs_are_untouched(self):
        self.generate(self.alias)
        for current in (self.alias / "gki_kernel/common", self.output / "gki_kernel/common",
                        self.alias / "msm-kernel-other", self.root / "unrelated", None):
            with self.subTest(current=str(current)):
                self.assertEqual(self.execute(self.alias, current), [])

    def test_config_selection_remains_manifest_driven(self):
        self.generate(self.alias, thermal=False, wlan=False)
        calls = self.execute(self.alias, self.output / "msm-kernel")
        self.assertEqual(len(calls), 2)
        self.assertNotIn("MI_THERMAL_INTERFACE", calls[0])
        self.assertFalse((self.alias / ".klee-disable-legacy-cnss.sh").exists())

    def test_fragment_paths_preserve_output_alias(self):
        args, env = self.generate(self.alias)
        self.assertEqual(args.out, self.alias)
        for name in (".klee-cupid-source-modules.config", ".klee-legacy-cnss.config"):
            relative = os.path.relpath(self.alias / name, self.platform)
            self.assertIn(relative, env["BUILD_CONFIG_FRAGMENTS"])
            self.assertIn("out alias/", (self.alias / name).read_text())
        self.assertEqual(builder.absolute_lexical_path(self.alias), self.alias)


if __name__ == "__main__":
    unittest.main(verbosity=2)
