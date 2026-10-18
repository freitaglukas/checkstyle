#!/usr/bin/env python3
"""Run the real Checker in a fresh JVM for each cache observation."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
JAVA = (Path(os.environ["JAVA_HOME"]) / "bin/java" if os.environ.get("JAVA_HOME")
        else Path(shutil.which("java") or "java"))
PARSER = argparse.ArgumentParser()
PARSER.add_argument("--phase", choices=["before", "red", "after", "edges"], default="after")
PARSER.add_argument("--engine", type=Path)
ARGS = PARSER.parse_args()
ENGINE = (ARGS.engine or ROOT / "engines" /
    ("baseline.jar" if ARGS.phase in {"before", "red"} else "prototype.jar")).resolve()
STAMP = time.strftime("%Y%m%dT%H%M%S") + f"-{time.time_ns() % 1000000:06d}"
OUT = ROOT / "evidence" / (ARGS.phase + "-" + STAMP)
OUT.mkdir(parents=True)
DEPENDENCIES = (ROOT / "dependencies.txt").read_text().strip()
CP = os.pathsep.join([str(ROOT / "classes"), str(ENGINE), DEPENDENCIES])
RESULTS = []
ASSERTIONS = []
UNUSED_SOURCE = "com.puppycrawl.tools.checkstyle.filters.SuppressWithNearbyCommentFilter"


def case(name, files):
    directory = OUT / name
    directory.mkdir()
    for file in files:
        shutil.copyfile(ROOT / "fixtures" / file, directory / file)
    return directory


def change(file, text):
    # Exercise Checker's documented lastModified cache key without sleeps/races.
    previous = file.stat().st_mtime_ns
    file.write_text(text)
    timestamp = max(time.time_ns(), previous + 2_000_000_000)
    os.utime(file, ns=(timestamp, timestamp))


def run(directory, label, files, mode="local", enabled=True, checks=True,
        severity="warning", cache=True, external=None, id_format=None,
        check_format="$1", influence="0", skip_parse=False, second_id_format=None,
        reject_all=False, reverse_filters=False):
    cache_path = directory / "cache.properties"
    root = ET.Element("module", name="Checker")
    prop = lambda parent, key, value: ET.SubElement(parent, "property", name=key,
                                                  value=str(value))
    prop(root, "localeLanguage", "en")
    prop(root, "localeCountry", "US")
    if cache:
        prop(root, "cacheFile", cache_path)
    if external:
        filt = ET.SubElement(root, "module", name="SuppressionFilter")
        prop(filt, "file", external)
    tree = ET.SubElement(root, "module", name="TreeWalker")
    if skip_parse:
        prop(tree, "skipFileOnJavaParseException", "true")
    if checks:
        check = ET.SubElement(tree, "module", name="EmptyStatement")
        prop(check, "id", "empty")
    name = "LegacyNearbyFilter" if mode == "global" else UNUSED_SOURCE
    filt = ET.SubElement(tree, "module", name=name)
    prop(filt, "checkFormat", check_format)
    prop(filt, "influenceFormat", influence)
    if mode == "local":
        prop(filt, "reportUnusedSuppressions", str(enabled).lower())
        prop(filt, "severity", severity)
    if id_format:
        prop(filt, "idFormat", id_format)
    if second_id_format:
        second = ET.fromstring(ET.tostring(filt))
        existing = second.find("property[@name='idFormat']")
        if existing is not None:
            existing.set("value", second_id_format)
        else:
            prop(second, "idFormat", second_id_format)
        tree.append(second)
    if reject_all:
        ET.SubElement(tree, "module", name="RejectAllFilter")
    if reverse_filters:
        tree[:] = list(reversed(list(tree)))
    if mode == "global":
        check = ET.SubElement(root, "module", name="LegacyUnusedCheck")
        prop(check, "severity", "warning")
        ET.SubElement(check, "message", key="suppression.unused",
                      value="Suppression comment does not match any violation.")
    config = directory / (label + ".xml")
    content = ('<?xml version="1.0"?>\n<!DOCTYPE module PUBLIC '
        '"-//Checkstyle//DTD Checkstyle Configuration 1.3//EN" '
        '"https://checkstyle.org/dtds/configuration_1_3.dtd">\n'
        + ET.tostring(root, encoding="unicode") + "\n")
    config.write_text(content)
    paths = [directory / name for name in files]
    cmd = [str(JAVA), "-cp", CP, "CacheRepro", str(config),
           str(cache_path) if cache else "-", *map(str, paths)]
    started = time.time()
    process = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
    (directory / (label + ".stdout.log")).write_text(process.stdout)
    (directory / (label + ".stderr.log")).write_text(process.stderr)
    result = {"label": label, "command": cmd, "exit_code": process.returncode,
        "elapsed_seconds": time.time() - started, "processed": [],
        "diagnostics": [], "cached": {}, "config_sha256": digest(config),
        "sources": {p.name: {"sha256": digest(p),
            "mtime_ns": p.stat().st_mtime_ns} for p in paths}}
    for line in process.stdout.splitlines():
        row = line.split("\t")
        if row[0] == "PROCESSED":
            result["processed"].append(row[1])
        elif row[0] == "DIAGNOSTIC":
            result["diagnostics"].append(dict(zip(
                ["file", "line", "column", "severity", "source", "key", "message"], row[1:])))
        elif row[0] == "CACHED":
            result["cached"][row[1]] = row[2] == "true"
        elif row[0] == "ERROR_COUNT":
            result["error_count"] = int(row[1])
        elif row[0] == "ENGINE":
            result["engine_location"] = row[1]
    if cache_path.exists():
        shutil.copyfile(cache_path, directory / (label + ".cache.properties"))
    RESULTS.append(result)
    (directory / (label + ".json")).write_text(json.dumps(result, indent=2))
    assert process.returncode == 0, (label, process.returncode, process.stderr)
    assert "error_count" in result, ("incomplete run", result)
    print(f"RUN {label}: processed={result['processed']} "
          f"diagnostics={len(result['diagnostics'])} cached={result['cached']}", flush=True)
    return result


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def expect(condition, description):
    ASSERTIONS.append({"description": description, "passed": bool(condition)})
    assert condition, description
    print("PASS " + description, flush=True)


def unused(result):
    return [(item["file"], int(item["line"])) for item in result["diagnostics"]
            if item["source"] == UNUSED_SOURCE]


try:
    if ARGS.phase == "before":
        directory = case("global", ["Used.java"])
        cold = run(directory, "cold", ["Used.java"], mode="global")
        warm = run(directory, "warm", ["Used.java"], mode="global")
        expect(cold["diagnostics"] == [] and cold["cached"]["Used.java"],
               "global model: useful suppression succeeds and source is cached cold")
        expect(warm["processed"] == [] and len(warm["diagnostics"]) == 1,
               "original concern reproduced: warm cache falsely reports useful suppression unused")
        expect(cold["config_sha256"] == warm["config_sha256"]
               and cold["sources"] == warm["sources"],
               "the false positive occurs without source or configuration changes")
        uncached = run(directory, "without-cache", ["Used.java"], mode="global", cache=False)
        expect(uncached["processed"] == ["Used.java"] and uncached["diagnostics"] == [],
               "global model: disabling the cache removes the false positive")
    elif ARGS.phase == "red":
        directory = case("stale", ["Unused.java"])
        result = run(directory, "stock", ["Unused.java"], mode="stock")
        expect(len(result["diagnostics"]) == 1,
               "stale marker must produce exactly one diagnostic (RED on stock Checkstyle)")
    elif ARGS.phase == "edges":
        overlap = ("class Used {\n // SUPPRESS CHECKSTYLE EmptyStatement\n"
                   " // SUPPRESS CHECKSTYLE EmptyStatement\n void run() { ; }\n}\n")
        for name, options in [("overlap", {}), ("other-first", {"reject_all": True,
                "reverse_filters": True}), ("other-last", {"reject_all": True})]:
            directory = case(name, ["Used.java"])
            (directory / "Used.java").write_text(overlap)
            cold = run(directory, "cold", ["Used.java"], influence="2", **options)
            warm = run(directory, "warm", ["Used.java"], influence="2", **options)
            expect(cold["diagnostics"] == warm["diagnostics"] == []
                   and warm["processed"] == [], name + ": no filter-order false positive")
        for name, other, count in [("same-selectors", "empty", 0),
                                    ("different-selectors", "different", 1)]:
            directory = case(name, ["Used.java"])
            cold = run(directory, "cold", ["Used.java"], id_format="empty",
                       second_id_format=other)
            warm = run(directory, "warm", ["Used.java"], id_format="empty",
                       second_id_format=other)
            expect(len(cold["diagnostics"]) == len(warm["diagnostics"]) == count,
                   name + (": REPRODUCED OPEN LIMITATION: per-instance warning"
                           if count else ": identical instances agree"))
    else:
        directory = case("paired-useful", ["Used.java"])
        cold = run(directory, "cold", ["Used.java"])
        warm = run(directory, "warm", ["Used.java"])
        expect(cold["diagnostics"] == warm["diagnostics"] == []
               and cold["processed"] == ["Used.java"] and warm["processed"] == []
               and warm["cached"]["Used.java"],
               "same useful-marker example: local reporting stays correct with a genuine warm cache hit")
        names = ["Used.java", "Unused.java", "Mixed.java", "Clean.java"]
        directory = case("local", names)
        cold = run(directory, "cold", names)
        warm = run(directory, "warm", names)
        expected = [("Unused.java", 3), ("Mixed.java", 4)]
        expect(unused(cold) == expected and len(cold["diagnostics"]) == 2,
               "cold: only the two stale comments are reported, at their actual lines")
        expect(cold["cached"] == {"Used.java": True, "Unused.java": False,
               "Mixed.java": False, "Clean.java": True},
               "useful/clean files cached; stale/mixed files with WARNINGs evicted")
        expect(warm["processed"] == ["Unused.java", "Mixed.java"]
               and warm["diagnostics"] == cold["diagnostics"],
               "warm: useful files skipped without false positives; both real findings repeat")
        expect(cold["sources"] == warm["sources"]
               and cold["config_sha256"] == warm["config_sha256"],
               "local cold/warm comparison holds source and configuration constant")
        no_cache = run(directory, "without-cache", names, cache=False)
        expect(no_cache["diagnostics"] == cold["diagnostics"],
               "cache-enabled and cache-disabled diagnostics agree")
        path = directory / "Used.java"
        change(path, path.read_text().replace("; //", "//"))
        edited = run(directory, "violation-removed", names)
        expect(("Used.java", 3) in unused(edited)
               and edited["processed"] == ["Used.java", "Unused.java", "Mixed.java"],
               "removing a suppressed violation makes its surviving marker unused")
        change(path, "class Used { void run() { } }\n")
        cleaned = run(directory, "marker-removed", names)
        expect(("Used.java", 3) not in unused(cleaned) and cleaned["cached"]["Used.java"],
               "removing the stale marker clears its warning and caches the file")

        directory = case("flag", ["Unused.java"])
        disabled = run(directory, "disabled", ["Unused.java"], enabled=False)
        enabled = run(directory, "enabled", ["Unused.java"])
        expect(disabled["diagnostics"] == [] and disabled["cached"]["Unused.java"]
               and unused(enabled) == [("Unused.java", 3)]
               and enabled["processed"] == ["Unused.java"],
               "enabling reporting invalidates an existing clean cache")

        directory = case("removed-check", ["Used.java"])
        run(directory, "with-check", ["Used.java"])
        warm = run(directory, "warm-with-check", ["Used.java"])
        removed = run(directory, "without-check", ["Used.java"], checks=False)
        expect(warm["processed"] == [] and unused(removed) == [("Used.java", 3)],
               "removing the only check invalidates cache and still discovers the stale marker")

        directory = case("selector", ["Used.java"])
        run(directory, "matching-id", ["Used.java"], id_format="empty")
        changed = run(directory, "wrong-id", ["Used.java"], id_format="different")
        expect(unused(changed) == [("Used.java", 3)] and len(changed["diagnostics"]) == 2,
               "selector configuration change reveals original violation and stale marker")

        directory = case("external", ["Unused.java"])
        external = directory / "suppressions.xml"
        prefix = ('<?xml version="1.0"?>\n<!DOCTYPE suppressions PUBLIC '
            '"-//Checkstyle//DTD SuppressionFilter Configuration 1.2//EN" '
            '"https://checkstyle.org/dtds/suppressions_1_2.dtd">\n')
        external.write_text(prefix + '<suppressions><suppress checks="'
            'SuppressWithNearbyCommentFilter"/></suppressions>\n')
        hidden = run(directory, "hidden", ["Unused.java"], external=external)
        hidden_warm = run(directory, "hidden-warm", ["Unused.java"], external=external)
        shutil.copyfile(external, directory / "suppressions-before.xml")
        external.write_text(prefix + '<suppressions><suppress checks="NeverMatches"/>'
            '</suppressions>\n')
        visible = run(directory, "visible", ["Unused.java"], external=external)
        expect(hidden["diagnostics"] == [] and hidden["cached"]["Unused.java"]
               and hidden_warm["processed"] == [] and unused(visible) == [("Unused.java", 3)]
               and hidden["config_sha256"] == visible["config_sha256"],
               "external suppression content change invalidates cache with identical main config")

        for severity in ["error", "warning", "info", "ignore"]:
            directory = case(severity, ["Unused.java"])
            cold = run(directory, "cold", ["Unused.java"], severity=severity)
            warm = run(directory, "warm", ["Unused.java"], severity=severity)
            if severity == "ignore":
                expect(cold["diagnostics"] == [] and cold["cached"]["Unused.java"]
                       and warm["processed"] == [], "IGNORE emits nothing and permits caching")
            else:
                expect(len(cold["diagnostics"]) == 1 and not cold["cached"]["Unused.java"]
                       and warm["processed"] == ["Unused.java"]
                       and cold["diagnostics"] == warm["diagnostics"]
                       and cold["diagnostics"][0]["severity"] == severity
                       and cold["error_count"] == (1 if severity == "error" else 0),
                       severity.upper() + " findings repeat across fresh JVMs with correct ERROR count")

        directory = case("broad-comments", ["Unused.java"])
        file = directory / "Unused.java"
        file.write_text("class Unused {\n // SUPPRESS CHECKSTYLE all\n"
                        " // SUPPRESS CHECKSTYLE all\n}\n")
        broad = run(directory, "broad", ["Unused.java"], check_format=".*", influence="10")
        expect(unused(broad) == [("Unused.java", 2), ("Unused.java", 3)],
               "broad markers neither suppress their own warnings nor count them as usage")

        directory = case("block-columns", ["Unused.java"])
        (directory / "Unused.java").write_text("class Unused { /* SUPPRESS CHECKSTYLE EmptyStatement */"
                " /* SUPPRESS CHECKSTYLE EmptyStatement */ }\n")
        blocks = run(directory, "blocks", ["Unused.java"])
        expect(len(unused(blocks)) == 2
               and len({item["column"] for item in blocks["diagnostics"]}) == 2,
               "two block markers on one line retain distinct positions and diagnostics")

        directory = case("preserved", ["Used.java"])
        (directory / "Used.java").write_text("class Used { void run() { ; } }\n")
        stock = run(directory, "stock", ["Used.java"], mode="stock", cache=False)
        local = run(directory, "local", ["Used.java"], cache=False)
        expect(len(stock["diagnostics"]) == 1 and local["diagnostics"] == stock["diagnostics"],
               "ordinary unsuppressed diagnostic text, source, severity and position are preserved")

        directory = case("parse", ["Unused.java"])
        (directory / "Unused.java").write_text("class Unused {\n"
            " // SUPPRESS CHECKSTYLE EmptyStatement\n this is not Java\n")
        disabled = run(directory, "disabled", ["Unused.java"], checks=False, enabled=False)
        enabled = run(directory, "enabled", ["Unused.java"], checks=False, skip_parse=True)
        expect(disabled["diagnostics"] == [] and len(enabled["diagnostics"]) == 1
               and enabled["diagnostics"][0]["key"] == "parse.exception"
               and unused(enabled) == [],
               "default filter-only path does not parse; failed opt-in parse emits no unused warning")
    print(f"COMPLETE {ARGS.phase}: {len(ASSERTIONS)} assertions; evidence={OUT}", flush=True)
finally:
    (OUT / "results.json").write_text(json.dumps({"phase": ARGS.phase,
        "engine": str(ENGINE), "engine_sha256": digest(ENGINE),
        "assertions": ASSERTIONS, "runs": RESULTS}, indent=2))
