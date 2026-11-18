#!/usr/bin/env python3
"""Package the ReMRSS author kit without the Android host or workspace files."""

import argparse
import json
import re
import stat
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KIT = Path("sdk/mod")
KIT_FILES = {
    "README.md", "CHANGELOG.md", "package.json", "remrss_mod.py", "bin/remrss",
    "manifest.schema.json", "runtime.schema.json", "ui.schema.json",
    "src/index.js", "src/index.d.ts", "docs/mod-sdk.md",
    "examples/notification-mod.zip", "tests/cases.json", "tests/sdk.test.js", "tests/test_remrss.py",
}
TEMPLATE_FILES = {"README.md", "settings.ui.json", "rear.ui.json"}
TEMPLATES = {
    "blank": set(),
    "info": set(),
    "rear-info": set(),
    "counter": {"main.js", "tests/cases.json", "tests/config-changes.json"},
    "notification": {"main.js", "tests/cases.json"},
}


def package(output_dir: Path, repo: Path = ROOT) -> Path:
    kit = repo / KIT
    version = json.loads((kit / "package.json").read_text(encoding="utf-8"))["version"]
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        raise ValueError("Mod kit version must be major.minor.patch")
    name = f"remrss-mod-kit-{version}"
    files = {item: kit / item for item in KIT_FILES}
    for template, extras in TEMPLATES.items():
        for file in TEMPLATE_FILES | extras:
            link = f"templates/{template}/{file}"
            files[link] = kit / link
    files.update({file: repo / file for file in ("LICENSE", "NOTICE")})
    contents = {}
    for rel, source in sorted(files.items()):
        # Avoid exporting local or external files through a substituted symlink.
        if not source.is_file() or source.resolve() != source.absolute():
            raise ValueError(f"Missing or redirected Mod kit input: {source}")
        contents[rel] = source.read_bytes()

    output_dir.mkdir(parents=True, exist_ok=True)
    archive = output_dir / f"{name}.zip"
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as bundle:
        for rel, content in contents.items():
            entry = zipfile.ZipInfo(f"{name}/{rel}")
            entry.create_system = 3
            mode = 0o755 if rel in ("bin/remrss", "remrss_mod.py") else 0o644
            entry.external_attr = (stat.S_IFREG | mode) << 16
            bundle.writestr(entry, content, compress_type=zipfile.ZIP_DEFLATED)
    return archive


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    print(package(args.output_dir))
