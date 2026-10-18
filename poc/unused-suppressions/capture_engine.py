#!/usr/bin/env python3
"""Snapshot built classes, replacing offline-instrumented classes with their originals."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("checkout", type=Path)
parser.add_argument("name", choices=["baseline", "prototype"])
args = parser.parse_args()
root = Path(__file__).resolve().parent
engine = root / "engines" / (args.name + ".jar")
engine.parent.mkdir(exist_ok=True)
classes = args.checkout / "target/classes"
originals = args.checkout / "target/generated-classes/jacoco"
with zipfile.ZipFile(engine, "w", compression=zipfile.ZIP_DEFLATED) as archive:
    for file in sorted(classes.rglob("*")):
        if file.is_file():
            relative = file.relative_to(classes)
            original = originals / relative
            source = original if file.suffix == ".class" and original.exists() else file
            archive.writestr(str(relative), source.read_bytes())
head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=args.checkout, text=True).strip()
diff = subprocess.check_output(["git", "diff", "--binary", "HEAD"], cwd=args.checkout)
(root / "evidence" / (args.name + "-engine.json")).write_text(json.dumps({
    "head": head, "diff_sha256": hashlib.sha256(diff).hexdigest(),
    "engine_sha256": hashlib.sha256(engine.read_bytes()).hexdigest(),
    "engine": str(engine), "originals_overlay": str(originals),
}, indent=2))
print(engine)
