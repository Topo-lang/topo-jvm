#!/usr/bin/env python3
"""Require the registered JVM evidence suite to execute without skips."""
import json
from pathlib import Path
import xml.etree.ElementTree as ET

registered = json.loads(Path("ctest-registered.json").read_text())["tests"]
tests = {test["name"]: test for test in registered if test["name"].startswith("JvmBuildEvidenceTest.")}
required = {"JvmBuildEvidenceTest." + name for name in (
    "PureModelUsesActualSourceSelectionAndOrderedClasspath",
    "ModelDigestAndSelectedFilesDriftFailBeforeCompilation",
    "UnsupportedIsolationFailsBeforeCompilerExecution",
    "ModelBindsSamePathSourceClasspathBytesAndBackendRecipe",
    "ActualCompilerTransformBundleAndJarHaveVerifiableParentChain",
    "RepeatedBuildDropsClassesRemovedFromTheSelectedSource")}
cases = [case for case in ET.parse("ctest-results.xml").getroot().findall(".//testcase")
         if case.attrib["name"].startswith("JvmBuildEvidenceTest.")]
executed = {case.attrib["name"] for case in cases}
disabled = [name for name, test in tests.items() for prop in test.get("properties", [])
            if prop["name"] == "DISABLED" and prop["value"]]
failed = [case.attrib["name"] for case in cases
          if case.find("failure") is not None or case.find("error") is not None]
skipped = [case.attrib["name"] for case in cases
           if case.find("skipped") is not None or case.attrib.get("status", "run") != "run"]
summary = {"registered": len(tests), "reported": len(cases),
           "passed": len(cases) - len(set(failed + skipped)), "failed": failed,
           "skipped": skipped, "disabled": disabled,
           "missingRequired": sorted(required - tests.keys()),
           "missingResults": sorted(tests.keys() - executed)}
Path("evidence-test-summary.json").write_text(json.dumps(summary, indent=2) + "\n")
print(json.dumps(summary, indent=2))
if required - tests.keys() or tests.keys() != executed or disabled or failed or skipped:
    raise SystemExit("JVM evidence tests must be registered, executed and passing")
