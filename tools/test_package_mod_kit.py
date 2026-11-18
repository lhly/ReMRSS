import base64
import json
import re
import runpy
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

from package_mod_kit import ROOT, package


class ModKitDeliveryTest(unittest.TestCase):
    def test_read_only_binding_contract_matches_cli_schema_types_and_host(self):
        kit = ROOT / "sdk/mod"
        cli = runpy.run_path(str(kit / "remrss_mod.py"))
        contract = cli["DATA_BINDINGS"]
        expected = {f"data.{path}" for paths in contract.values() for path in paths}
        schema = json.loads((kit / "ui.schema.json").read_text())
        self.assertEqual(expected, set(schema["$defs"]["binding"]["properties"]["bind"]["oneOf"][1]["enum"]))
        manifest = json.loads((kit / "manifest.schema.json").read_text())
        self.assertEqual(set(cli["CAPABILITY_NAMES"]), set(manifest["$defs"]["capability"]["properties"]["name"]["enum"]))
        ts = (kit / "src/index.d.ts").read_text()
        binding_types = re.findall(r"export type (?:RearInfoBindingPath|BindingPath) =\s*([^;]+);", ts)
        self.assertEqual(expected, set(re.findall(r"'(data\.[A-Za-z.]+)'", " ".join(binding_types))))
        host = (ROOT / "android/app/src/main/kotlin/top/zainzh/rearscreen/platform/mods/ModRearInfoBindings.kt").read_text()
        self.assertEqual(contract["rear.info"], set(re.findall(r'"(rear\.(?:info|viewport)\.[A-Za-z.]+)"', host)))

    def test_unpacked_kit_checks_signs_and_prepares_a_same_author_update(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with zipfile.ZipFile(package(root)) as archive:
                names = archive.namelist()
                self.assertEqual(len(names), len(set(names)))
                self.assertTrue(all(not any(part.startswith(".") for part in Path(name).parts) for name in names))
                self.assertFalse(any(name.endswith((".pem", ".apk", ".aar", ".properties")) for name in names))
                launcher = next(name for name in names if name.endswith("/bin/remrss"))
                self.assertTrue((archive.getinfo(launcher).external_attr >> 16) & 0o100)
                archive.extractall(root)
                kit = root / Path(launcher).parts[0]
                guide = "docs/rear-wallpaper-template.md"
                self.assertIn(f"]({guide})", (kit / "README.md").read_text())
                self.assertTrue((kit / guide).is_file())

            def mod(*args, ok=True):
                result = subprocess.run([sys.executable, kit / "remrss_mod.py", *args], cwd=root, capture_output=True, text=True)
                self.assertEqual(ok, result.returncode == 0, result.stdout + result.stderr)
                return result.stdout + result.stderr

            mod("create", "example.blank")
            mod("check", "example.blank")
            mod("create", "example.rear-info", "--template", "rear-info")
            mod("preview", "example.rear-info")
            project = root / "example.rear-info"
            manifest = project / "manifest.json"
            initial = json.loads(manifest.read_text())
            self.assertEqual([{"name": "rear.scene", "minimumVersion": 1}], initial["capabilities"]["required"])
            self.assertIn({"name": "rear.info", "minimumVersion": 1}, initial["capabilities"]["optional"])
            self.assertEqual("none", initial["runtime"]["kind"])
            without_info = json.loads(manifest.read_text())
            without_info["capabilities"]["optional"] = []
            manifest.write_text(json.dumps(without_info))
            self.assertIn("数据绑定", mod("check", "example.rear-info", ok=False))
            manifest.write_text(json.dumps(initial))
            scene = project / "rear.ui.json"
            original = scene.read_text()
            scene.write_text(original.replace("data.rear.viewport.contentBoundsPx.width", "data.rear.viewport.displayId"))
            self.assertIn("宿主未提供此数据字段", mod("check", "example.rear-info", ok=False))
            scene.write_text(original)
            subprocess.run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", "author.pem"],
                           cwd=root, check=True, capture_output=True)

            background = root / "example.blank/assets/rear-wallpaper/six-apps.png"
            background.parent.mkdir(parents=True)
            background.write_bytes(base64.b64decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jLZkAAAAASUVORK5CYII="
            ))
            mod("pack", "example.blank", "--key", "author.pem", "--out", "wallpaper.zip")
            mod("check", "wallpaper.zip")
            with zipfile.ZipFile(root / "wallpaper.zip") as wallpaper:
                self.assertIn("assets/rear-wallpaper/six-apps.png", wallpaper.namelist())
                self.assertNotIn("main.js", wallpaper.namelist())

            def signed(name):
                mod("pack", "example.rear-info", "--key", "author.pem", "--out", name)
                mod("check", name)
                with zipfile.ZipFile(root / name) as signed_archive:
                    self.assertNotIn("author.pem", signed_archive.namelist())
                    return json.loads(signed_archive.read("manifest.json"))

            previous = signed("v1.zip")
            initial.update(version="0.2.0", versionCode=2)
            manifest.write_text(json.dumps(initial))
            successor = signed("v2.zip")
            self.assertEqual((1, 2), (previous["versionCode"], successor["versionCode"]))
            self.assertEqual(previous["id"], successor["id"])
            self.assertEqual(previous["author"], successor["author"])
            self.assertEqual(previous["data"]["dataVersion"], successor["data"]["dataVersion"])


if __name__ == "__main__":
    unittest.main()
