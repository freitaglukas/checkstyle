#!/usr/bin/env python3
"""Build pinned baseline/prototype engines and the standalone cache experiment."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zipfile

BASE = "282a3360a4acb991dd35e24421113f5a67f7bb98"
ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]
PARSER = argparse.ArgumentParser()
PARSER.add_argument("--baseline", required=True, type=Path,
                    help="clean dedicated checkout/worktree of the pinned base")
ARGS = PARSER.parse_args()
BASELINE = ARGS.baseline.resolve()
JAVA = (Path(os.environ["JAVA_HOME"]) / "bin/java" if os.environ.get("JAVA_HOME")
        else Path(shutil.which("java") or "java"))
JAVAC = (Path(os.environ["JAVA_HOME"]) / "bin/javac" if os.environ.get("JAVA_HOME")
         else Path(shutil.which("javac") or "javac"))
assert JAVA.is_file() and JAVAC.is_file(), "JDK 21+ with java and javac is required"
assert BASELINE != REPO, "baseline must be a separate dedicated checkout"
assert subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=BASELINE,
                               text=True).strip() == BASE, "wrong baseline commit"
assert not subprocess.check_output(["git", "status", "--porcelain"],
                                   cwd=BASELINE), "baseline checkout must be clean"
(ROOT / "evidence").mkdir(exist_ok=True)
RECEIPTS = []


def execute(label, command, cwd):
    started = time.time()
    with (ROOT / "evidence" / (label + ".stdout.log")).open("w") as stdout, \
            (ROOT / "evidence" / (label + ".stderr.log")).open("w") as stderr:
        result = subprocess.run(command, cwd=cwd, stdout=stdout, stderr=stderr)
    RECEIPTS.append({"label": label, "command": list(map(str, command)),
        "exit_code": result.returncode, "elapsed_seconds": time.time() - started})
    (ROOT / "evidence/build-results.json").write_text(json.dumps(RECEIPTS, indent=2))
    if result.returncode:
        sys.exit(f"{label} failed ({result.returncode}); see evidence/{label}.stderr.log"
                 f" and evidence/{label}.stdout.log")
    print(label + " passed", flush=True)


for label, checkout in [("baseline", BASELINE), ("prototype", REPO)]:
    execute(label + "-focused", [str(checkout / "mvnw"), "-B", "-ntp", "clean",
        "-Dtest=SuppressWithNearbyCommentFilterTest,TreeWalkerTest", "test"], checkout)
    report = ET.parse(checkout / "target/surefire-reports/"
        "TEST-com.puppycrawl.tools.checkstyle.TreeWalkerTest.xml").getroot()
    properties = {item.attrib["name"]: item.attrib["value"]
                  for item in report.find("properties")}
    if label == "prototype":
        dependencies = [part for part in properties["surefire.test.class.path"]
                        .split(os.pathsep) if part.endswith(".jar")]
        assert dependencies and all(Path(part).is_file() for part in dependencies)
        (ROOT / "dependencies.txt").write_text(os.pathsep.join(dependencies))
    execute(label + "-snapshot", [sys.executable, str(ROOT / "capture_engine.py"),
                                  str(checkout), label], ROOT)

(ROOT / "classes").mkdir(exist_ok=True)
classpath = str(ROOT / "engines/baseline.jar") + os.pathsep \
    + (ROOT / "dependencies.txt").read_text()
execute("compile-experiment", [str(JAVAC), "--release", "21", "-cp", classpath,
    "-d", str(ROOT / "classes"), *map(str, sorted((ROOT / "src").glob("*.java")))], ROOT)

# Same prototype, with exactly the final per-file callback removed.
negative = ROOT / "negative-control"
(negative / "classes").mkdir(parents=True, exist_ok=True)
source = (REPO / "src/main/java/com/puppycrawl/tools/checkstyle/TreeWalker.java").read_text()
call = "                    addViolations(reporter.getFileReport(contents, rawViolations));"
assert source.count(call) == 1, "prototype callback changed; update the control deliberately"
(negative / "TreeWalker.java").write_text(source.replace(call,
    "                    // Negative control: omit only the final report callback."))
classpath = str(ROOT / "engines/prototype.jar") + os.pathsep \
    + (ROOT / "dependencies.txt").read_text()
execute("compile-negative-control", [str(JAVAC), "--release", "21", "-cp", classpath,
    "-d", str(negative / "classes"), str(negative / "TreeWalker.java")], ROOT)
with zipfile.ZipFile(ROOT / "engines/prototype.jar") as source, \
        zipfile.ZipFile(ROOT / "engines/hook-disabled.jar", "w",
                        compression=zipfile.ZIP_DEFLATED) as target:
    for name in source.namelist():
        replacement = negative / "classes" / name
        target.writestr(name, replacement.read_bytes() if replacement.is_file()
                       else source.read(name))
print("Prepared engines. Run reproduce.py --phase before and --phase after.")
