"""
ADB utility wrapper for automated Android app testing.
Handles WSL + Windows ADB path, connects to device, runs shell commands.
"""
import subprocess
import time
import json
import os
import re
from typing import Optional

# ── Configuration ────────────────────────────────────────

ADB_PATH = os.environ.get(
    "ADB_PATH",
    "/mnt/c/temp-adb/platform-tools/adb.exe"
)
DEFAULT_DEVICE = os.environ.get(
    "ADB_DEVICE",
    "192.168.31.79:5555"
)
APP_PACKAGE = "com.example.voiceassistant"
APP_ACTIVITY = ".MainActivity"
GRADLE_BIN = "/mnt/c/gradle/gradle-9.2.1/bin/gradle"
PROJECT_DIR = os.environ.get(
    "VA_PROJECT_DIR",
    os.path.expanduser("~/voice-assistant")
)
APK_PATH = f"{PROJECT_DIR}/app/build/outputs/apk/debug/app-debug.apk"
DEBUG_LOG_PATH = f"/data/data/{APP_PACKAGE}/files/debug.log"


# ── ADB Commands ─────────────────────────────────────────

def adb(*args, timeout: int = 30, device: str = None) -> subprocess.CompletedProcess:
    """Run adb command and return result."""
    dev = device or DEFAULT_DEVICE
    cmd = [ADB_PATH, "-s", dev] + list(args)
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


def adb_check() -> bool:
    """Check if ADB and device are available."""
    try:
        r = adb("shell", "echo", "ok", timeout=10)
        return "ok" in r.stdout
    except Exception:
        return False


def adb_connect() -> bool:
    """Connect to device, retrying once with kill-server."""
    try:
        r = adb("connect", DEFAULT_DEVICE, timeout=10)
        if "connected" in r.stdout:
            return True
    except Exception:
        pass

    # Retry with kill-server
    try:
        subprocess.run([ADB_PATH, "kill-server"], capture_output=True, timeout=5)
        subprocess.run([ADB_PATH, "start-server"], capture_output=True, timeout=5)
        r = adb("connect", DEFAULT_DEVICE, timeout=10)
        return "connected" in r.stdout
    except Exception:
        return False


# ── App Management ───────────────────────────────────────

def build_apk() -> tuple[bool, str]:
    """Build the debug APK. Returns (success, output)."""
    try:
        r = subprocess.run(
            [GRADLE_BIN, "assembleDebug", "--no-daemon"],
            capture_output=True, text=True, timeout=180,
            cwd=PROJECT_DIR
        )
        success = "BUILD SUCCESSFUL" in r.stdout
        return success, r.stdout + "\n" + r.stderr
    except subprocess.TimeoutExpired:
        return False, "Build timed out (180s)"
    except Exception as e:
        return False, str(e)


def install_apk() -> tuple[bool, str]:
    """Push and install APK. Returns (success, output)."""
    if not os.path.exists(APK_PATH):
        return False, f"APK not found: {APK_PATH}"

    try:
        # Push
        r = adb("push", APK_PATH, "/data/local/tmp/va.apk", timeout=60)
        if r.returncode != 0:
            return False, f"Push failed: {r.stderr}"

        # Install
        r = adb("shell", "pm", "install", "-r", "/data/local/tmp/va.apk", timeout=30)
        success = "Success" in r.stdout
        return success, r.stdout
    except Exception as e:
        return False, str(e)


def start_app(extras: dict = None) -> bool:
    """Start the app via am start. Pass extras dict for test dispatch."""
    cmd = ["shell", "am", "start", "-n", f"{APP_PACKAGE}/{APP_ACTIVITY}"]
    if extras:
        for key, value in extras.items():
            if isinstance(value, bool):
                cmd.extend(["--ez", key, "true" if value else "false"])
            elif isinstance(value, int):
                cmd.extend(["--ei", key, str(value)])
            else:
                cmd.extend(["--es", key, str(value)])

    r = adb(*cmd, timeout=15)
    return "Error" not in r.stdout and r.returncode == 0


def stop_app():
    """Force-stop the app."""
    adb("shell", "am", "force-stop", APP_PACKAGE, timeout=10)


def clear_log():
    """Clear logcat and app debug log."""
    adb("logcat", "-c", timeout=5)
    adb("shell", "run-as", APP_PACKAGE, "rm", "-f", "files/debug.log", timeout=5)


def read_debug_log(tail: int = 100) -> str:
    """Read the last N lines of the app's debug log."""
    try:
        r = adb(
            "shell", "run-as", APP_PACKAGE,
            "sh", "-c", f"tail -n {tail} files/debug.log 2>/dev/null || echo ''",
            timeout=10
        )
        return r.stdout.strip()
    except Exception as e:
        return f"[read_debug_log error: {e}]"


def read_logcat(grep: str = None, tail: int = 50) -> str:
    """Read recent logcat entries."""
    cmd = ["logcat", "-d", "-t", str(tail)]
    if grep:
        cmd.extend(["-e", grep])

    r = adb("shell", *cmd, timeout=10)
    return r.stdout.strip()


def get_crash_log() -> str:
    """Check logcat crash buffer."""
    r = adb("logcat", "-d", "-b", "crash", timeout=10)
    return r.stdout.strip()


# ── Test Markers Parsing ─────────────────────────────────

TEST_START_RE = re.compile(r"\[TEST:START:([^\]]+)\]\s*(.*)")
TEST_RESULT_RE = re.compile(r"\[TEST:RESULT:([^\]]+)\]\s+(\S+)=(.+)")
TEST_END_RE = re.compile(r"\[TEST:END:([^\]]+)\]\s+(.*)")
TEST_LOG_RE = re.compile(r"\[TEST:LOG:([^\]]+)\]\s+(.*)")


def parse_test_markers(log_text: str) -> dict:
    """Parse structured [TEST:...] markers from debug.log into a dict.

    Returns:
        {
            "tests": {
                "suite/name": {
                    "suite": "tts",
                    "name": "roundtrip",
                    "params": {...},
                    "results": {"key": "value", ...},
                    "logs": ["msg1", "msg2"],
                    "passed": true,
                    "duration_ms": 1234,
                    "reason": "..."  # if failed
                }
            },
            "overall": {"passed": 2, "failed": 1, "total": 3}
        }
    """
    tests = {}
    current_test = None
    current_suite = None

    for line in log_text.split("\n"):
        # START
        m = TEST_START_RE.search(line)
        if m:
            test_name = m.group(1)
            params_str = m.group(2).strip()
            suite, name = test_name.split("/", 1) if "/" in test_name else ("unknown", test_name)
            current_test = test_name
            current_suite = suite
            tests[current_test] = {
                "suite": suite,
                "name": name,
                "params": _parse_kv(params_str),
                "results": {},
                "logs": [],
                "passed": None,
                "duration_ms": None,
            }
            continue

        # RESULT
        m = TEST_RESULT_RE.search(line)
        if m:
            test_name = m.group(1)
            key = m.group(2)
            value = m.group(3)
            if test_name in tests:
                tests[test_name]["results"][key] = value
            continue

        # END
        m = TEST_END_RE.search(line)
        if m:
            test_name = m.group(1)
            kv_str = m.group(2).strip()
            if test_name in tests:
                kv = _parse_kv(kv_str)
                tests[test_name]["passed"] = kv.get("passed") == "true"
                tests[test_name]["duration_ms"] = int(kv.get("duration_ms", 0))
                if "reason" in kv:
                    tests[test_name]["reason"] = kv["reason"]
            current_test = None
            current_suite = None
            continue

        # LOG
        m = TEST_LOG_RE.search(line)
        if m:
            test_name = m.group(1)
            msg = m.group(2)
            if test_name in tests:
                tests[test_name]["logs"].append(msg)
            continue

    # Overall stats
    total = len(tests)
    passed = sum(1 for t in tests.values() if t["passed"] is True)
    failed = sum(1 for t in tests.values() if t["passed"] is False)
    unknown = total - passed - failed

    return {
        "tests": tests,
        "overall": {
            "total": total,
            "passed": passed,
            "failed": failed,
            "unknown": unknown,
        },
    }


def _parse_kv(text: str) -> dict:
    """Parse key=value pairs from text."""
    result = {}
    for part in text.split():
        if "=" in part:
            key, value = part.split("=", 1)
            result[key] = value
    return result


# ── Utility ──────────────────────────────────────────────

def wait_for_init(timeout: int = 20) -> bool:
    """Wait for app to finish initialization by polling debug.log for 'Engines initialized'."""
    start = time.time()
    while time.time() - start < timeout:
        log = read_debug_log(tail=5)
        if "Engines initialized" in log:
            return True
        time.sleep(1)
    return False


def wait_for_test_complete(timeout: int = 60) -> dict:
    """Wait for test completion by watching for [TEST:END] markers."""
    start = time.time()
    last_log = ""
    while time.time() - start < timeout:
        log = read_debug_log(tail=80)
        if log != last_log:
            last_log = log
            if "[TEST:END:" in log:
                # Give it 500ms more for any trailing markers
                time.sleep(0.5)
                log = read_debug_log(tail=100)
                return parse_test_markers(log)
        time.sleep(1)
    # Timeout — parse whatever we got
    return parse_test_markers(read_debug_log(tail=200))
