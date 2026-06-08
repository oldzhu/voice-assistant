#!/usr/bin/env python3
"""
Automated test runner for 猪头助手 (Voice Assistant).

Usage:
    python3 tests/runner.py                    # Build + install + run all tests
    python3 tests/runner.py --test tts_roundtrip  # Run specific test
    python3 tests/runner.py --install-only     # Just build and install
    python3 tests/runner.py --no-build         # Skip build (run on current APK)
    python3 tests/runner.py --report report.json  # Save structured report

Test types:
    Core:
      init              — Engine initialization check
      tts_roundtrip     — TTS → speaker → mic → ASR → similarity
      llm_connectivity  — LLM connectivity + latency
      llm_tools         — Tool calling pipeline
      e2e_full_pipeline — Full TTS→ASR→LLM→TTS→ASR loop
    Direct tool tests (v2.0):
      tool_location     — GPS + reverse geocoding
      tool_news         — Sina news headlines
      tool_web_fetch    — HTML fetch + OOM safety
      tool_config       — update_config write
      tool_memory       — remember → what_do_you_know round-trip
      tool_barge_in     — set_barge_in_mode all modes
      tool_clear_history — clear_history
      tool_read_article  — Read article tool
      tool_persistence   — Conversation persistence across restarts (two-phase)
    LLM-mediated (v2.0):
      llm_multi_tool    — ≥2 tool calls in one response
    all            — Run all 14 tests
"""
import argparse
import json
import os
import re
import sys
import time
from datetime import datetime

# Add project root to path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from adb_utils import (
    adb_check, adb_connect,
    build_apk, install_apk, start_app, stop_app, clear_log,
    read_debug_log, read_logcat, get_crash_log,
    wait_for_init, wait_for_test_complete,
    parse_test_markers
)


def print_header(text: str):
    """Print a section header."""
    print(f"\n{'=' * 60}")
    print(f"  {text}")
    print(f"{'=' * 60}")


def print_result(passed: bool, test_name: str, details=""):
    """Print a test result line."""
    icon = "✅" if passed else "❌"
    print(f"  {icon} {test_name}  {details}")


def run_persistence_test(report: dict) -> dict:
    """
    Two-phase persistence test:
      Phase 1: Start app in test mode → write synthetic conversation → stop
      Phase 2: Restart app (no test mode) → verify history loaded from disk
    """
    print_header("PERSISTENCE: Phase 1 (write)")

    # Phase 1: Write
    clear_log()
    stop_app()
    time.sleep(1)
    start_app({"test_type": "tool_persistence"})

    # Wait for init (retry loop, WSL ADB can be slow to respond)
    for _ in range(20):
        time.sleep(1)
        log = read_debug_log(tail=30)
        if "Engines initialized" in log:
            print("  ✅ App initialized")
            break
    else:
        print("  ❌ Phase 1: Init timeout")
        return {"passed": False, "reason": "Phase 1 init timeout"}

    # Wait for Phase 1 test markers
    results = wait_for_test_complete(timeout=30)
    test_data = results["tests"].get("tool/persistence", {})
    if test_data.get("passed") is not True:
        print("  ❌ Phase 1: Write test failed")
        return {"passed": False, "reason": f"Phase 1 write failed: {test_data.get('reason', 'unknown')}"}
    written = int(test_data.get("results", {}).get("written_count", 0))
    print(f"  ✅ Phase 1: Wrote {written} messages")

    # Phase 2: Restart and verify
    print_header("PERSISTENCE: Phase 2 (restore)")
    stop_app()
    time.sleep(2)
    clear_log()
    start_app()  # No test mode — normal start

    # Wait for init and check for loader marker
    start_time = time.time()
    loaded_count = 0
    while time.time() - start_time < 25:
        time.sleep(1)
        log = read_debug_log(tail=50)
        # Parse [PERSISTENCE:LOADED] count=N
        m = re.search(r"\[PERSISTENCE:LOADED\]\s+count=(\d+)", log)
        if m:
            loaded_count = int(m.group(1))
            break
        if "Engines initialized" in log and "PERSISTENCE:LOADED" not in log:
            # App started but no history restored — maybe first run
            print("  ⚠️ App started but no PERSISTENCE:LOADED marker found")
            break

    if loaded_count > 0:
        print(f"  ✅ Phase 2: Restored {loaded_count} messages from disk")
        return {"passed": True, "written": written, "loaded": loaded_count}
    elif "Engines initialized" in read_debug_log(tail=50):
        print(f"  ❌ Phase 2: App initialized but no history loaded")
        return {"passed": False, "reason": "No history loaded on restart"}
    else:
        print(f"  ❌ Phase 2: App init timeout")
        return {"passed": False, "reason": "Phase 2 init timeout"}


def run_tests(
    test_type: str = "all",
    skip_build: bool = False,
    install_only: bool = False,
    report_path: str = None,
) -> dict:
    """Main test orchestration. Returns complete report dict."""
    report = {
        "timestamp": datetime.now().isoformat(),
        "test_type": test_type,
        "build": {"success": False, "output": ""},
        "install": {"success": False, "output": ""},
        "device_connected": False,
        "init": {"timeout": False, "log": ""},
        "tests": {},
        "overall": {"passed": 0, "failed": 0, "total": 0},
    }

    # ── Step 1: Build ────────────────────────────────────
    if not skip_build:
        print_header("BUILD")
        success, output = build_apk()
        report["build"]["success"] = success
        report["build"]["output"] = output[-500:]  # Last 500 chars
        if not success:
            print(f"  ❌ Build failed! See output above.")
            print(output[-300:])
            return report
        print(f"  ✅ Build successful")

    # ── Step 2: Connect ─────────────────────────────────
    print_header("DEVICE")
    if not adb_check():
        print("  Connecting to device...")
        if not adb_connect():
            print("  ❌ Cannot connect to device!")
            report["device_connected"] = False
            return report
    report["device_connected"] = True
    print(f"  ✅ Device connected")

    # ── Step 3: Install ─────────────────────────────────
    print_header("INSTALL")
    stop_app()
    time.sleep(1)
    clear_log()
    success, output = install_apk()
    report["install"]["success"] = success
    report["install"]["output"] = output.strip()
    if not success:
        print(f"  ❌ Install failed: {output.strip()}")
        return report
    print(f"  ✅ Installed")

    if install_only:
        print(f"  Install-only mode — done.")
        return report

    # ── Persistence test: special two-phase flow ─────────
    if test_type == "tool_persistence":
        result = run_persistence_test(report)
        report["tests"]["tool/persistence"] = result
        if result.get("passed"):
            report["overall"] = {"passed": 1, "failed": 0, "total": 1}
        else:
            report["overall"] = {"passed": 0, "failed": 1, "total": 1}
        print(f"\n  📊 Persistence: {'✅ PASS' if result.get('passed') else '❌ FAIL'}")
        if report_path:
            with open(report_path, "w") as f:
                json.dump(report, f, indent=2, ensure_ascii=False, default=str)
        return report

    # ── Step 4: Start + Init Check ──────────────────────
    print_header("INITIALIZATION")
    extras = {}
    if test_type != "all":
        extras["test_type"] = test_type
    start_app(extras)

    print("  Waiting for engine init...")
    if wait_for_init(timeout=25):
        print("  ✅ Engines initialized")
        report["init"]["timeout"] = False
        report["init"]["log"] = read_debug_log(20)
    else:
        print("  ⚠️ Init timeout — checking for crashes...")
        report["init"]["timeout"] = True
        crash_log = get_crash_log()
        if crash_log:
            print(f"  ❌ Crash detected:\n{crash_log[:500]}")
            report["init"]["crash_log"] = crash_log
        report["init"]["log"] = read_debug_log(50)
        if test_type == "init":
            # Init test is its own test — parse markers anyway
            pass
        else:
            return report

    # ── Step 5: Wait for test completion ─────────────────
    print_header(f"TEST: {test_type}")
    results = wait_for_test_complete(timeout=90)
    report["tests"] = results["tests"]
    report["overall"] = results["overall"]

    # Print results
    for test_name, test_data in results["tests"].items():
        passed = test_data.get("passed")
        if passed is True:
            dur = test_data.get("duration_ms", "?")
            print_result(True, test_name, f"({dur}ms)")
        elif passed is False:
            reason = test_data.get("reason", "unknown")
            print_result(False, test_name, f"— {reason}")
        else:
            print_result(False, test_name, "— unknown/no result")

    # Summary
    ov = results["overall"]
    print(f"\n  📊 {ov['passed']}/{ov['total']} passed, {ov['failed']} failed, {ov['unknown']} unknown")

    # ── Step 6: Crash check ──────────────────────────────
    crash = get_crash_log()
    if crash:
        print_header("CRASH LOG")
        print(crash[:500])
        report["crashes"] = crash

    # ── Step 7: Save report ──────────────────────────────
    if report_path:
        with open(report_path, "w") as f:
            json.dump(report, f, indent=2, ensure_ascii=False, default=str)
        print(f"\n  📄 Report saved to {report_path}")

    return report


def main():
    parser = argparse.ArgumentParser(description="Voice Assistant Auto-Test Runner")
    parser.add_argument(
        "--test", "-t",
        default="all",
        choices=["all", "init", "tts_roundtrip", "llm_connectivity", "llm_tools", "e2e_full_pipeline",
                 "tool_location", "tool_news", "tool_web_fetch", "tool_config", "tool_memory",
                 "tool_barge_in", "tool_clear_history", "tool_read_article", "tool_persistence", "llm_multi_tool"],
        help="Test type to run (default: all)"
    )
    parser.add_argument("--no-build", action="store_true", help="Skip build step")
    parser.add_argument("--install-only", action="store_true", help="Build + install only, don't run tests")
    parser.add_argument("--report", "-r", help="Save JSON report to file")
    args = parser.parse_args()

    report = run_tests(
        test_type=args.test,
        skip_build=args.no_build,
        install_only=args.install_only,
        report_path=args.report,
    )

    # Exit code: 0 if all passed, 1 if any failed
    ov = report.get("overall", {})
    if ov.get("failed", 0) > 0 or ov.get("unknown", 0) > 0:
        sys.exit(1)
    else:
        sys.exit(0)


if __name__ == "__main__":
    main()
