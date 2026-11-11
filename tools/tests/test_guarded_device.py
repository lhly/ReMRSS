import contextlib
import io
import json
import os
import subprocess
import tempfile
import time
import unittest
from unittest import mock
from pathlib import Path

from tools import guarded_device as gd


FAKE_ADB = r'''#!/usr/bin/env python3
import json, os, sys, time
args = sys.argv[1:]
with open(os.environ["FAKE_ADB_LOG"], "a", encoding="utf-8") as log:
    log.write(json.dumps(args) + "\n")
if args[:3] != ["-H", "127.0.0.1", "-P"]:
    print("unsafe adb server", file=sys.stderr); sys.exit(9)
cmd = args[4:] if args[4:5] == ["devices"] else args[6:]
if cmd[:1] == ["shell"]:
    import shlex
    raw_shell = " ".join(cmd[1:])
    lexer = shlex.shlex(raw_shell, posix=True, punctuation_chars=";&|`$")
    lexer.whitespace_split = True
    shell_tokens = list(lexer)
    if any(token in {";", "&&", "||", "|", "&", "`", "$"} for token in shell_tokens):
        print("unquoted remote-shell operator", file=sys.stderr); sys.exit(10)
    cmd = ["shell", *shlex.split(raw_shell)]
mode = os.environ.get("FAKE_MODE", "ok")
target_serial = os.environ.get("FAKE_SERIAL", "emulator-5586")
state_path = os.environ["FAKE_STATE"]
with open(state_path, encoding="utf-8") as f: state = json.load(f)
def save():
    with open(state_path, "w", encoding="utf-8") as f: json.dump(state, f)
if cmd[:1] != ["devices"] and args[4:6] != ["-s", target_serial]:
    print("unsafe adb serial", file=sys.stderr); sys.exit(9)
if cmd[:2] == ["devices", "-l"]:
    if mode == "multi": print("List of devices attached\n" + target_serial + " device product:x\nother device product:y")
    elif mode == "wrong_serial": print("List of devices attached\nother device product:x")
    else: print("List of devices attached\n" + target_serial + " device product:x")
elif cmd[:3] == ["shell", "getprop", "ro.product.device"]: print("d708" if mode.startswith("phone") else ("emu64a" if mode != "bad_device" else "phone"))
elif cmd[:3] == ["shell", "getprop", "ro.product.model"]: print("Xiaomi 17 Pro" if mode.startswith("phone") else "sdk_gphone64_arm64")
elif cmd[:3] == ["shell", "getprop", "ro.serialno"]: print("d708dac7")
elif cmd[:3] == ["shell", "getprop", "ro.kernel.qemu"]: print("1")
elif cmd[:3] == ["emu", "avd", "name"]: print("remrss-root-rc3-isolated\nOK")
elif cmd[:3] == ["shell", "pm", "path"]:
    package = cmd[3]
    if mode.endswith("query_error"):
        print("Permission denied", file=sys.stderr); sys.exit(1)
    if package in state["installed"]: print("package:/data/app/" + package)
    else: sys.exit(1)  # adb preserves pm's not-found exit status and empty output
elif cmd[0] == "install":
    apk = cmd[1]
    package = os.environ.get("FAKE_APP_PACKAGE", "top.zainzh.rearscreen.debug") if apk.endswith("app.apk") else os.environ.get("FAKE_TEST_PACKAGE", "top.zainzh.rearscreen.debug.test")
    state["installed"].append(package); save()
    if mode == "install_fail_partial" and package.endswith(".debug") and not package.endswith(".test"):
        print("install transport failed", file=sys.stderr); sys.exit(1)
    print("Success")
elif cmd[:3] == ["shell", "am", "instrument"]:
    if "-r" not in cmd:
        state["runner_active"] = False; save()
        print("INSTRUMENTATION_RESULT: stream=\nOK (1 test)\nINSTRUMENTATION_CODE: -1")
        sys.exit(0)
    selector = cmd[7]
    class_name, _, method = selector.partition("#")
    if not method: method = "runsOnlyOnRootDedicatedEmulatorAndDebugVariant"
    state["runner_active"] = True; save()
    with open(os.environ["FAKE_RUNNER_STARTED"], "w", encoding="utf-8") as f: f.write(str(os.getpid()))
    if mode == "timeout_instrument":
        time.sleep(2)
        state["runner_active"] = False; save()
        with open(os.environ["FAKE_RUNNER_FINISHED"], "w", encoding="utf-8") as f: f.write("finished")
    elif mode == "wrong_status_selector":
        print("INSTRUMENTATION_STATUS: class=top.zainzh.rearscreen.OtherTest\nINSTRUMENTATION_STATUS: test=wrong\nINSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS: class=top.zainzh.rearscreen.OtherTest\nINSTRUMENTATION_STATUS: test=wrong\nINSTRUMENTATION_STATUS_CODE: 0\nINSTRUMENTATION_RESULT: stream=\nOK (1 test)\nINSTRUMENTATION_CODE: -1")
        state["runner_active"] = False; save()
    elif mode == "summary_mismatch":
        print("INSTRUMENTATION_STATUS: class=" + class_name + "\nINSTRUMENTATION_STATUS: test=" + method + "\nINSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS: class=" + class_name + "\nINSTRUMENTATION_STATUS: test=" + method + "\nINSTRUMENTATION_STATUS_CODE: 0\nINSTRUMENTATION_RESULT: stream=\nOK (2 tests)\nINSTRUMENTATION_CODE: -1")
        state["runner_active"] = False; save()
    elif mode == "skipped_instrument":
        print("INSTRUMENTATION_STATUS: class=" + class_name + "\nINSTRUMENTATION_STATUS: test=" + method + "\nINSTRUMENTATION_STATUS_CODE: -3\nINSTRUMENTATION_RESULT: stream=\nOK (0 tests)\nINSTRUMENTATION_CODE: -1")
        state["runner_active"] = False; save()
    elif mode == "failed_instrument":
        print("INSTRUMENTATION_STATUS: class=" + class_name + "\nINSTRUMENTATION_STATUS: test=" + method + "\nINSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS: class=" + class_name + "\nINSTRUMENTATION_STATUS: test=" + method + "\nINSTRUMENTATION_STATUS_CODE: -2\nINSTRUMENTATION_RESULT: stream=\nFAILURES!!!\nINSTRUMENTATION_CODE: -1")
        state["runner_active"] = False; save()
    elif mode == "zero_tests":
        print("INSTRUMENTATION_RESULT: stream=\nOK (0 tests)\nINSTRUMENTATION_CODE: -1")
        state["runner_active"] = False; save()
    elif mode == "wrong_end_code":
        print("INSTRUMENTATION_STATUS: class=" + class_name + "\nINSTRUMENTATION_STATUS: test=" + method + "\nINSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS: class=" + class_name + "\nINSTRUMENTATION_STATUS: test=" + method + "\nINSTRUMENTATION_STATUS_CODE: 0\nINSTRUMENTATION_RESULT: stream=\nOK (1 test)\nINSTRUMENTATION_CODE: 0")
        state["runner_active"] = False; save()
    elif mode == "missing_end":
        print("INSTRUMENTATION_STATUS: class=" + class_name + "\nINSTRUMENTATION_STATUS: test=" + method + "\nINSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS: class=" + class_name + "\nINSTRUMENTATION_STATUS: test=" + method + "\nINSTRUMENTATION_STATUS_CODE: 0\nINSTRUMENTATION_RESULT: stream=\nOK (1 test)")
        state["runner_active"] = False; save()
    elif mode == "crash":
        print("INSTRUMENTATION_FAILED: Process crashed.\nINSTRUMENTATION_CODE: -1")
        state["runner_active"] = False; save()
    else:
        print("INSTRUMENTATION_STATUS: class=" + class_name + "\nINSTRUMENTATION_STATUS: test=" + method + "\nINSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS: class=" + class_name + "\nINSTRUMENTATION_STATUS: test=" + method + "\nINSTRUMENTATION_STATUS_CODE: 0\nINSTRUMENTATION_RESULT: stream=\nTime: 0.1\n\nOK (1 test)\nINSTRUMENTATION_CODE: -1")
        state["runner_active"] = False; save()
elif cmd[0] == "uninstall":
    package = cmd[1]
    if mode.endswith("fail_uninstall"):
        print("Failure [DELETE_FAILED_INTERNAL_ERROR]")
    else:
        state["installed"] = [item for item in state["installed"] if item != package]
        state["runner_active"] = False; save()
        print("Success")
else:
    print("unexpected command: " + repr(cmd), file=sys.stderr); sys.exit(8)
'''


FAKE_AAPT = r'''#!/usr/bin/env python3
import os, re, sys
mode = sys.argv[2]
apk = sys.argv[-2] if mode == "xmltree" else sys.argv[-1]
is_app = apk.endswith("app.apk")
if mode == "badging":
    package = os.environ.get("FAKE_APP_PACKAGE", "top.zainzh.rearscreen.debug") if is_app else os.environ.get("FAKE_TEST_PACKAGE", "top.zainzh.rearscreen.debug.test")
    print("package: name='" + package + "' versionCode='1'")
else:
    fixture = os.environ["FAKE_APP_XMLTREE"] if is_app else os.environ["FAKE_TEST_XMLTREE"]
    text = open(fixture, encoding="utf-8").read()
    if not is_app:
        runner = os.environ.get("FAKE_RUNNER", "androidx.test.runner.AndroidJUnitRunner")
        target = os.environ.get("FAKE_TARGET", "top.zainzh.rearscreen.debug")
        text = re.sub(r'(android:name\([^)]*\)=")([^"]+)', lambda m: m.group(1) + runner, text, count=1)
        text = re.sub(r'(android:targetPackage\([^)]*\)=")([^"]+)', lambda m: m.group(1) + target, text, count=1)
    print(text, end="")
'''


class GuardedDeviceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.log = self.root / "adb.log"
        self.state_path = self.root / "device-state.json"
        self.state_path.write_text(json.dumps({"installed": [], "runner_active": False}), encoding="utf-8")
        self.started = self.root / "runner.started"
        self.finished = self.root / "runner.finished"
        self.fake = self.root / "fake-adb"
        self.fake.write_text(FAKE_ADB, encoding="utf-8"); self.fake.chmod(0o755)
        self.app = self.root / "app.apk"; self.test = self.root / "test.apk"
        self.app.touch(); self.test.touch()
        self.aapt = self.root / "aapt"
        self.aapt.write_text(FAKE_AAPT, encoding="utf-8"); self.aapt.chmod(0o755)
        self.session = {"schema_version": 1, "server": {"host": "127.0.0.1", "port": 5039}, "serial": "emulator-5586", "target": {"kind": "emulator", "expected_device": "emu64a", "expected_model": "sdk_gphone64_arm64", "avd_name": "remrss-root-rc3-isolated"}}
        self.env = {
            "FAKE_ADB_LOG": str(self.log), "FAKE_STATE": str(self.state_path),
            "FAKE_RUNNER_STARTED": str(self.started), "FAKE_RUNNER_FINISHED": str(self.finished),
            "FAKE_SERIAL": self.session["serial"], "FAKE_MODE": "ok", "FAKE_TARGET": "top.zainzh.rearscreen.debug", "FAKE_RUNNER": "androidx.test.runner.AndroidJUnitRunner",
            "FAKE_APP_PACKAGE": "top.zainzh.rearscreen.debug",
            "FAKE_APP_XMLTREE": str(Path(__file__).parent / "fixtures/aapt-app-manifest.xmltree.txt"),
            "FAKE_TEST_XMLTREE": str(Path(__file__).parent / "fixtures/aapt-test-manifest.xmltree.txt"),
            "FAKE_TEST_PACKAGE": "top.zainzh.rearscreen.debug.test",
        }
        self.old_env = {key: os.environ.get(key) for key in self.env}
        os.environ.update(self.env)

    def tearDown(self):
        for key, value in self.old_env.items():
            if value is None: os.environ.pop(key, None)
            else: os.environ[key] = value
        self.temp.cleanup()

    def state(self):
        return json.loads(self.state_path.read_text(encoding="utf-8"))

    def invocations(self):
        return [json.loads(line) for line in self.log.read_text(encoding="utf-8").splitlines()] if self.log.exists() else []

    def commands(self):
        return [row[4:] if row[4:5] == ["devices"] else row[6:] for row in self.invocations()]

    def run_instrument(self, selector="top.zainzh.rearscreen.DeviceIsolationSmokeTest#runsOnlyOnRootDedicatedEmulatorAndDebugVariant", timeout=5, runner="androidx.test.runner.AndroidJUnitRunner", allow_phone_debug_tests=False):
        return gd.run_instrument(str(self.fake), self.session, self.app, self.test,
                                 runner, selector, str(self.aapt), timeout,
                                 allow_phone_debug_tests=allow_phone_debug_tests)

    def phone_session(self):
        return {"schema_version": 1, "server": {"host": "127.0.0.1", "port": 5039}, "serial": "d708dac7", "target": {"kind": "phone", "expected_device": "d708", "expected_model": "Xiaomi 17 Pro", "expected_serial": "d708dac7"}}

    def test_pm_path_absent_exit_one_is_only_absence_case(self):
        self.assertFalse(gd.package_installed(str(self.fake), self.session, "top.zainzh.rearscreen.debug"))
        os.environ["FAKE_MODE"] = "query_error"
        with self.assertRaisesRegex(gd.GuardError, "不能视为包不存在"):
            gd.package_installed(str(self.fake), self.session, "top.zainzh.rearscreen.debug")
        self.assertFalse(any(command and command[0] in {"install", "uninstall"} for command in self.commands()))

    def test_partial_install_failure_is_confirmed_and_cleaned(self):
        os.environ["FAKE_MODE"] = "install_fail_partial"
        with self.assertRaises(gd.GuardError): self.run_instrument()
        self.assertEqual([], self.state()["installed"])
        self.assertFalse(self.state()["runner_active"])
        self.assertEqual(["top.zainzh.rearscreen.debug"], [c[1] for c in self.commands() if c[:1] == ["uninstall"]])

    def test_rejects_default_or_forbidden_server_and_target_before_any_adb_request(self):
        path = self.root / "session.json"
        for host, port in (("127.0.0.1", 5037), ("127.0.0.1", 15001), ("::1", 15001)):
            session = dict(self.session); session["server"] = {"host": host, "port": port}
            path.write_text(json.dumps(session))
            with self.assertRaises(gd.GuardError): gd.read_session(path)
        bad_target = dict(self.session); bad_target["serial"] = "127.0.0.1:15001"
        path.write_text(json.dumps(bad_target))
        with self.assertRaises(gd.GuardError): gd.read_session(path)
        self.assertEqual([], self.invocations())

    def test_rejects_multiple_devices_without_identity_or_business_commands(self):
        os.environ["FAKE_MODE"] = "multi"
        with self.assertRaises(gd.GuardError): gd.validate_identity(str(self.fake), self.session)
        self.assertEqual([["devices", "-l"]], self.commands())

    def test_wrong_serial_or_identity_never_installs_or_runs_shell_business(self):
        os.environ["FAKE_MODE"] = "wrong_serial"
        with self.assertRaises(gd.GuardError): gd.validate_identity(str(self.fake), self.session)
        self.log.unlink()
        os.environ["FAKE_MODE"] = "bad_device"
        with self.assertRaises(gd.GuardError): gd.validate_identity(str(self.fake), self.session)
        self.assertFalse(any(c and c[0] in {"install", "uninstall"} or c[:3] == ["shell", "am", "instrument"] for c in self.commands()))

    def test_actual_xmltree_fixture_is_parsed_because_badging_has_no_runner(self):
        badging = subprocess.run([str(self.aapt), "dump", "badging", str(self.test)], text=True, capture_output=True, check=False)
        self.assertNotIn("instrumentation:", badging.stdout)
        app_package, _ = gd.apk_metadata(str(self.aapt), self.app)
        test_package, declarations = gd.apk_metadata(str(self.aapt), self.test)
        self.assertEqual("top.zainzh.rearscreen.debug", app_package)
        self.assertEqual("top.zainzh.rearscreen.debug.test", test_package)
        self.assertEqual([("androidx.test.runner.AndroidJUnitRunner", "top.zainzh.rearscreen.debug")], declarations)

    def test_fake_adb_without_raw_flag_never_emits_status_protocol(self):
        command = [str(self.fake), "-H", "127.0.0.1", "-P", "5039", "-s", "emulator-5586", "shell", "am", "instrument", "-w", "-e", "class", "top.zainzh.rearscreen.DeviceIsolationSmokeTest", "top.zainzh.rearscreen.debug.test/androidx.test.runner.AndroidJUnitRunner"]
        result = subprocess.run(command, text=True, capture_output=True, check=False)
        self.assertEqual(0, result.returncode)
        self.assertNotIn("INSTRUMENTATION_STATUS_CODE", result.stdout)
        self.assertIn("OK (1 test)", result.stdout)
        with self.assertRaisesRegex(gd.GuardError, "缺少raw测试状态"):
            gd.parse_instrumentation_output(result.stdout, "top.zainzh.rearscreen.DeviceIsolationSmokeTest")

    def test_fake_remote_shell_rejects_unquoted_metacharacters(self):
        command = [str(self.fake), "-H", "127.0.0.1", "-P", "5039", "-s", "emulator-5586", "shell", "am", "instrument", "-w", "-r", "-e", "class", "top.zainzh.rearscreen.Test;id", "top.zainzh.rearscreen.debug.test/androidx.test.runner.AndroidJUnitRunner"]
        result = subprocess.run(command, text=True, capture_output=True, check=False)
        self.assertEqual(10, result.returncode)
        self.assertIn("unquoted remote-shell operator", result.stderr)

    def test_nested_class_selector_and_runner_are_shell_quoted(self):
        os.environ["FAKE_RUNNER"] = "androidx.test.runner.CustomRunner$Inner"
        self.run_instrument("top.zainzh.rearscreen.DeviceIsolationSmokeTest$Nested", runner="androidx.test.runner.CustomRunner$Inner")
        instrument_call = next(call for call in self.invocations() if "instrument" in call)
        raw_shell = " ".join(instrument_call[7:])
        self.assertIn("'top.zainzh.rearscreen.DeviceIsolationSmokeTest$Nested'", raw_shell)
        self.assertIn("'top.zainzh.rearscreen.debug.test/androidx.test.runner.CustomRunner$Inner'", raw_shell)
        self.assertFalse(self.state()["runner_active"])

    def test_apk_manifest_target_and_runner_mismatch_fail_before_device_calls(self):
        for env_key, value in (("FAKE_TARGET", "other.app"), ("FAKE_RUNNER", "other.Runner")):
            os.environ[env_key] = value
            with self.assertRaises(gd.GuardError): self.run_instrument()
            self.assertEqual([], self.invocations())
            os.environ.pop(env_key)

    def test_invalid_selector_and_runner_shell_syntax_fail_before_device_calls(self):
        for selector in ("", "top.zainzh.rearscreen.A,top.zainzh.rearscreen.B", "other.app.Test", "top.zainzh.rearscreen.Test;id", "top.zainzh.rearscreen.Test#x;id"):
            with self.subTest(selector=selector), self.assertRaises(gd.GuardError): self.run_instrument(selector)
            self.assertEqual([], self.invocations())
        with self.assertRaises(gd.GuardError):
            gd.run_instrument(str(self.fake), self.session, self.app, self.test,
                              "androidx.test.runner.AndroidJUnitRunner;id", "top.zainzh.rearscreen.Test", str(self.aapt), 5)
        self.assertEqual([], self.invocations())

    def test_instrumentation_scopes_to_target_and_cleans_new_packages(self):
        self.run_instrument()
        self.assertEqual([], self.state()["installed"])
        self.assertFalse(self.state()["runner_active"])
        for row in self.invocations():
            self.assertEqual(["-H", "127.0.0.1", "-P", "5039"], row[:4])
            if row[4:5] != ["devices"]: self.assertEqual(["-s", "emulator-5586"], row[4:6])
        self.assertIn(["shell", "am", "instrument", "-w", "-r", "-e", "class", "'top.zainzh.rearscreen.DeviceIsolationSmokeTest#runsOnlyOnRootDedicatedEmulatorAndDebugVariant'", "top.zainzh.rearscreen.debug.test/androidx.test.runner.AndroidJUnitRunner"], self.commands())

    def test_explicit_single_class_selector_is_supported(self):
        self.run_instrument("top.zainzh.rearscreen.DeviceIsolationSmokeTest")
        self.assertEqual([], self.state()["installed"])

    def test_phone_verify_is_read_only_and_checks_fixed_serial(self):
        os.environ["FAKE_MODE"] = "phone"; os.environ["FAKE_SERIAL"] = "d708dac7"
        session = self.phone_session()
        gd.validate_identity(str(self.fake), session)
        self.assertIn(["shell", "getprop", "ro.serialno"], self.commands())
        self.assertFalse(any(c and c[0] in {"install", "uninstall"} or c[:3] == ["shell", "am", "instrument"] for c in self.commands()))

    def test_verify_cli_consumes_session_and_remains_read_only(self):
        os.environ["FAKE_MODE"] = "phone"; os.environ["FAKE_SERIAL"] = "d708dac7"
        session = self.phone_session()
        path = self.root / "phone-session.json"; path.write_text(json.dumps(session))
        with contextlib.redirect_stdout(io.StringIO()) as output:
            result = gd.main(["verify", "--session", str(path), "--adb", str(self.fake)])
        self.assertEqual(0, result); self.assertIn("phone / d708dac7", output.getvalue())
        self.assertFalse(any(c and c[0] in {"install", "uninstall"} or c[:3] == ["shell", "am", "instrument"] for c in self.commands()))

    def test_phone_instrument_defaults_to_read_only_rejection(self):
        os.environ["FAKE_MODE"] = "phone"; os.environ["FAKE_SERIAL"] = "d708dac7"
        self.session = self.phone_session()
        with self.assertRaisesRegex(gd.GuardError, "默认关闭"):
            self.run_instrument()
        self.assertEqual([], self.invocations())

    def test_explicit_phone_opt_in_installs_runs_and_cleans_only_new_debug_packages(self):
        os.environ["FAKE_MODE"] = "phone"; os.environ["FAKE_SERIAL"] = "d708dac7"
        self.session = self.phone_session()
        self.run_instrument(allow_phone_debug_tests=True)
        business = [c for c in self.commands() if c and c[0] in {"install", "uninstall"} or c[:3] == ["shell", "am", "instrument"]]
        self.assertEqual(["install", "install", "instrument", "uninstall", "uninstall"], [c[0] if c[:3] != ["shell", "am", "instrument"] else "instrument" for c in business])
        self.assertEqual([], self.state()["installed"])
        self.assertFalse(self.state()["runner_active"])
        self.assertEqual("d708dac7", self.session["serial"])

    def test_phone_opt_in_still_refuses_preexisting_app_or_test_baseline(self):
        os.environ["FAKE_MODE"] = "phone"; os.environ["FAKE_SERIAL"] = "d708dac7"
        self.session = self.phone_session()
        for package in ("top.zainzh.rearscreen.debug", "top.zainzh.rearscreen.debug.test"):
            state = self.state(); state["installed"] = [package]; self.state_path.write_text(json.dumps(state))
            self.log.unlink(missing_ok=True)
            with self.assertRaisesRegex(gd.GuardError, "baseline"):
                self.run_instrument(allow_phone_debug_tests=True)
            self.assertFalse(any(c and c[0] in {"install", "uninstall"} or c[:3] == ["shell", "am", "instrument"] for c in self.commands()))
            state["installed"] = []; self.state_path.write_text(json.dumps(state))

    def test_phone_api_revalidates_session_before_any_adb_call(self):
        os.environ["FAKE_MODE"] = "phone"; os.environ["FAKE_SERIAL"] = "d708dac7"
        valid = self.phone_session()
        invalid_sessions = []
        for host in ("0.0.0.0", "example.com"):
            session = json.loads(json.dumps(valid)); session["server"]["host"] = host; invalid_sessions.append(session)
        for port in (5037, 15001):
            session = json.loads(json.dumps(valid)); session["server"]["port"] = port; invalid_sessions.append(session)
        session = json.loads(json.dumps(valid)); session["schema_version"] = 2; invalid_sessions.append(session)
        session = json.loads(json.dumps(valid)); session["target"].pop("expected_serial"); invalid_sessions.append(session)
        session = json.loads(json.dumps(valid)); session["target"]["expected_serial"] = "other"; invalid_sessions.append(session)
        session = json.loads(json.dumps(valid)); session["target"]["kind"] = "other"; invalid_sessions.append(session)
        for session in invalid_sessions:
            with self.subTest(session=session), self.assertRaises(gd.GuardError):
                gd.run_instrument(str(self.fake), session, self.app, self.test,
                                  "androidx.test.runner.AndroidJUnitRunner",
                                  "top.zainzh.rearscreen.DeviceIsolationSmokeTest#runsOnlyOnRootDedicatedEmulatorAndDebugVariant",
                                  str(self.aapt), 5, allow_phone_debug_tests=True)
            self.assertEqual([], self.invocations())

    def test_keyboard_interrupt_after_install_cleans_new_packages_and_propagates(self):
        os.environ["FAKE_MODE"] = "phone"; os.environ["FAKE_SERIAL"] = "d708dac7"
        self.session = self.phone_session()
        real_adb_call = gd.adb_call

        def interrupt_at_runner(adb, session, args, timeout=15.0):
            if args[:3] == ["shell", "am", "instrument"]:
                raise KeyboardInterrupt()
            return real_adb_call(adb, session, args, timeout)

        with mock.patch.object(gd, "adb_call", side_effect=interrupt_at_runner):
            with self.assertRaises(KeyboardInterrupt):
                self.run_instrument(allow_phone_debug_tests=True)
        self.assertEqual([], self.state()["installed"])
        self.assertEqual(["top.zainzh.rearscreen.debug.test", "top.zainzh.rearscreen.debug"],
                         [c[1] for c in self.commands() if c[:1] == ["uninstall"]])

    def test_keyboard_interrupt_during_each_install_confirms_and_cleans_that_package(self):
        os.environ["FAKE_MODE"] = "phone"; os.environ["FAKE_SERIAL"] = "d708dac7"
        self.session = self.phone_session()
        real_adb_call = gd.adb_call
        for apk in (self.app, self.test):
            with self.subTest(apk=apk.name):
                self.log.unlink(missing_ok=True)
                self.state_path.write_text(json.dumps({"installed": [], "runner_active": False}), encoding="utf-8")
                interrupted = False

                def interrupt_after_install(adb, session, args, timeout=15.0):
                    nonlocal interrupted
                    result = real_adb_call(adb, session, args, timeout)
                    if args[:1] == ["install"] and args[1] == str(apk) and not interrupted:
                        interrupted = True
                        raise KeyboardInterrupt()
                    return result

                with mock.patch.object(gd, "adb_call", side_effect=interrupt_after_install):
                    with self.assertRaises(KeyboardInterrupt):
                        self.run_instrument(allow_phone_debug_tests=True)
                self.assertTrue(interrupted)
                self.assertEqual([], self.state()["installed"])
                expected_cleanup = {"top.zainzh.rearscreen.debug"}
                if apk == self.test:
                    expected_cleanup.add("top.zainzh.rearscreen.debug.test")
                self.assertEqual(expected_cleanup,
                                 {c[1] for c in self.commands() if c[:1] == ["uninstall"]})

    def test_keyboard_interrupt_with_unknown_install_state_reports_owner_and_preserves_interrupt(self):
        os.environ["FAKE_MODE"] = "phone"; os.environ["FAKE_SERIAL"] = "d708dac7"
        self.session = self.phone_session()
        real_adb_call = gd.adb_call
        package_queries = 0

        def interrupt_after_app_install(adb, session, args, timeout=15.0):
            result = real_adb_call(adb, session, args, timeout)
            if args[:1] == ["install"]:
                raise KeyboardInterrupt()
            return result

        def fail_confirmation(adb, session, package):
            nonlocal package_queries
            package_queries += 1
            if package_queries <= 2:  # Initial baseline checks: both packages are absent.
                return False
            raise gd.GuardError("fake pm path failure")

        stderr = io.StringIO()
        with mock.patch.object(gd, "adb_call", side_effect=interrupt_after_app_install), \
                mock.patch.object(gd, "package_installed", side_effect=fail_confirmation):
            with contextlib.redirect_stderr(stderr), self.assertRaises(KeyboardInterrupt):
                self.run_instrument(allow_phone_debug_tests=True)
        self.assertIn("debug状态未知", stderr.getvalue())
        self.assertIn("设备操作负责人核对设备并恢复", stderr.getvalue())
        self.assertTrue(self.state()["installed"])  # Unknown is not treated as absent or safe to remove.
        self.assertFalse(any(c[:1] == ["uninstall"] for c in self.commands()))

    def test_keyboard_interrupt_with_cleanup_failure_remains_interrupt_and_reports_packages(self):
        os.environ["FAKE_MODE"] = "phone"; os.environ["FAKE_SERIAL"] = "d708dac7"
        self.session = self.phone_session()
        real_adb_call = gd.adb_call

        def interrupt_at_runner(adb, session, args, timeout=15.0):
            if args[:3] == ["shell", "am", "instrument"]:
                raise KeyboardInterrupt()
            return real_adb_call(adb, session, args, timeout)

        os.environ["FAKE_MODE"] = "phone_fail_uninstall"
        with mock.patch.object(gd, "adb_call", side_effect=interrupt_at_runner):
            with contextlib.redirect_stderr(io.StringIO()) as stderr, self.assertRaises(KeyboardInterrupt):
                self.run_instrument(allow_phone_debug_tests=True)
        self.assertIn("本轮新增包清理也失败", stderr.getvalue())
        self.assertTrue(self.state()["installed"])
        self.assertEqual(2, len([c for c in self.commands() if c[:1] == ["uninstall"]]))

    def test_baseline_packages_are_never_uninstalled_and_formal_package_is_preserved(self):
        os.environ["FAKE_MODE"] = "phone"; os.environ["FAKE_SERIAL"] = "d708dac7"
        self.session = self.phone_session()
        for package in ("top.zainzh.rearscreen.debug", "top.zainzh.rearscreen.debug.test"):
            state = self.state(); state["installed"] = [package]; self.state_path.write_text(json.dumps(state))
            with self.assertRaises(gd.GuardError): self.run_instrument(allow_phone_debug_tests=True)
            self.assertFalse(any(c[:1] == ["uninstall"] for c in self.commands()))
            self.log.unlink(missing_ok=True)
            state["installed"] = []; self.state_path.write_text(json.dumps(state))
        state["installed"] = ["top.zainzh.rearscreen"]; self.state_path.write_text(json.dumps(state))
        self.run_instrument(allow_phone_debug_tests=True)
        self.assertEqual(["top.zainzh.rearscreen"], self.state()["installed"])
        self.assertNotIn("top.zainzh.rearscreen", [c[1] for c in self.commands() if c[:1] == ["uninstall"]])

    def test_phone_opt_in_rejects_formal_apk_package_before_device_calls(self):
        os.environ["FAKE_MODE"] = "phone"; os.environ["FAKE_SERIAL"] = "d708dac7"
        self.session = self.phone_session()
        os.environ["FAKE_APP_PACKAGE"] = "top.zainzh.rearscreen"
        with self.assertRaisesRegex(gd.GuardError, "调试APK包名"):
            self.run_instrument(allow_phone_debug_tests=True)
        self.assertEqual([], self.invocations())

    def test_phone_cli_opt_in_flag_is_required_and_passed_through(self):
        os.environ["FAKE_MODE"] = "phone"; os.environ["FAKE_SERIAL"] = "d708dac7"
        path = self.root / "phone-session.json"; path.write_text(json.dumps(self.phone_session()))
        base = ["instrument", "--session", str(path), "--adb", str(self.fake), "--aapt", str(self.aapt),
                "--app-apk", str(self.app), "--test-apk", str(self.test), "--runner", "androidx.test.runner.AndroidJUnitRunner",
                "--class", "top.zainzh.rearscreen.DeviceIsolationSmokeTest#runsOnlyOnRootDedicatedEmulatorAndDebugVariant"]
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(2, gd.main(base))
        self.assertEqual([], self.invocations())
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, gd.main(base + ["--allow-phone-debug-tests"]))
        self.assertEqual([], self.state()["installed"])
        self.assertTrue(any(c[:3] == ["shell", "am", "instrument"] for c in self.commands()))

    def test_preexisting_baseline_package_is_never_overwritten_or_removed(self):
        state = self.state(); state["installed"] = ["top.zainzh.rearscreen.debug"]; self.state_path.write_text(json.dumps(state))
        with self.assertRaises(gd.GuardError): self.run_instrument()
        self.assertFalse(any(c and c[0] in {"install", "uninstall"} for c in self.commands()))

    def test_zero_fail_crash_missing_or_wrong_junit_end_are_rejected(self):
        cases = {
            "zero_tests": "缺少raw测试状态",
            "failed_instrument": "失败",
            "wrong_end_code": "结束码",
            "missing_end": "结束码",
            "crash": "报告失败",
            "skipped_instrument": "失败、跳过",
            "wrong_status_selector": "selector之外",
            "summary_mismatch": "用例数不匹配",
        }
        for mode, message in cases.items():
            with self.subTest(mode=mode):
                os.environ["FAKE_MODE"] = mode
                with self.assertRaisesRegex(gd.GuardError, message): self.run_instrument()
                self.assertEqual([], self.state()["installed"])
                self.assertFalse(self.state()["runner_active"])
                self.state_path.write_text(json.dumps({"installed": [], "runner_active": False}))
                self.log.unlink()

    def test_timeout_terminates_fake_runner_then_cleans_only_session_packages(self):
        os.environ["FAKE_MODE"] = "timeout_instrument"
        with self.assertRaisesRegex(gd.GuardError, "ADB请求超时: shell am instrument"):
            self.run_instrument(timeout=0.1)
        self.assertTrue(self.started.exists())
        time.sleep(2.1)  # Let an accidentally surviving fake runner reach its finish marker.
        self.assertFalse(self.finished.exists())
        self.assertFalse(self.state()["runner_active"])
        self.assertEqual([], self.state()["installed"])
        self.assertTrue(all(row[:4] == ["-H", "127.0.0.1", "-P", "5039"] for row in self.invocations()))
        self.assertTrue(all(row[4:6] == ["-s", "emulator-5586"] for row in self.invocations() if row[4:5] != ["devices"]))

    def test_failed_cleanup_is_reported_and_preserves_remaining_packages(self):
        os.environ["FAKE_MODE"] = "fail_uninstall"
        with self.assertRaisesRegex(gd.GuardError, "清理失败"):
            self.run_instrument()
        self.assertCountEqual(["top.zainzh.rearscreen.debug", "top.zainzh.rearscreen.debug.test"], self.state()["installed"])
        self.assertFalse(self.state()["runner_active"])
        self.assertTrue(all(row[4:6] == ["-s", "emulator-5586"] for row in self.invocations() if row[4:5] != ["devices"]))


if __name__ == "__main__":
    unittest.main()
