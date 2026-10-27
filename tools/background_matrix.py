"""Real traffic tests on an ISOLATED Android 13+ emulator; no instrumentation stays active.

Overwrites Rill test preferences and changes emulator power/network settings.
Run with --adb <path> --serial emulator-5580 --output <ignored directory>.
Requires an installed debuggable Rill APK and the emulator's AndroidWifi AP.
"""

import argparse
import concurrent.futures
import hashlib
import http.client
import http.server
import json
import random
import re
import subprocess
import threading
import time
import xml.etree.ElementTree as ET
from pathlib import Path

DATA = random.Random(20261012).randbytes(32 * 1024 * 1024)
BLOCKED = threading.Event()
RELEASE = threading.Event()


class Origin(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def handle(self):
        try:
            super().handle()
        except (ConnectionResetError, ConnectionAbortedError):
            # Expected when the test deliberately kills the app or disables Wi-Fi.
            pass

    def do_GET(self):
        if self.path.startswith("/blocked"):
            BLOCKED.set()
            RELEASE.wait(30)
        match = re.fullmatch(r"bytes=(\d+)-(\d+)", self.headers.get("Range", ""))
        if not match:
            self.send_error(416)
            return
        first, last = map(int, match.groups())
        last = min(last, len(DATA) - 1)
        self.send_response(206)
        self.send_header("Content-Range", f"bytes {first}-{last}/{len(DATA)}")
        self.send_header("Content-Length", str(last - first + 1))
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("ETag", '"background-fixture"')
        self.end_headers()
        try:
            self.wfile.write(DATA[first:last + 1])
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass

    def log_message(self, *args):
        pass


class Matrix:
    def __init__(self, args):
        self.args = args
        self.output = Path(args.output)
        self.output.mkdir(parents=True, exist_ok=True)
        self.results = []
        self.counter = 0
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Origin)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.forward = None
        self.original_restricted_mode = None

    def adb(self, *args, data=None, check=True):
        result = subprocess.run([self.args.adb, "-s", self.args.serial, *args], input=data,
                                capture_output=True, timeout=25, check=check)
        return result.stdout.decode("utf-8", errors="replace").strip()

    def shell(self, command, **kwargs):
        return self.adb("shell", command, **kwargs)

    def report(self):
        try:
            report = json.loads(self.shell("run-as app.rill.android cat files/relay-result.json"))
            device_now = int(self.shell("date +%s")) * 1000
            report["snapshot_age_ms"] = max(0, device_now - report["exported_at_ms"])
            return report
        except (ValueError, KeyError, subprocess.SubprocessError):
            return {}

    def request(self, path=None, timeout=15, slow=False):
        self.counter += 1
        if path is None:
            path = f"/track/{self.counter}"
        connection = http.client.HTTPConnection("127.0.0.1", self.forward, timeout=timeout)
        started = time.monotonic()
        first, last = (0, len(DATA) - 1) if slow else (200000, 999999)
        try:
            connection.request("GET", f"http://10.0.2.2:{self.server.server_port}{path}",
                               headers={"Range": f"bytes={first}-{last}"})
            response = connection.getresponse()
            header_time = time.monotonic() - started
            digest = hashlib.sha256()
            received = 0
            while True:
                chunk = response.read(16384)
                if not chunk:
                    break
                digest.update(chunk)
                received += len(chunk)
                if slow:
                    time.sleep(0.04)
            assert response.status == 206, f"HTTP {response.status}"
            assert received == last - first + 1, f"Truncated body: {received}"
            assert digest.digest() == hashlib.sha256(DATA[first:last + 1]).digest(), "Byte mismatch"
            return {"header_seconds": round(header_time, 3), "bytes": received,
                    "seconds": round(time.monotonic() - started, 3)}
        finally:
            connection.close()

    def health(self):
        connection = http.client.HTTPConnection("127.0.0.1", self.forward, timeout=3)
        try:
            connection.request("GET", "/health", headers={"Host": "localhost"})
            response = connection.getresponse()
            assert response.status == 200 and response.read() == b"Rill ready"
        finally:
            connection.close()

    def run_case(self, name, fn):
        if self.args.only and name not in self.args.only:
            return
        print(f"START {name}", flush=True)
        started = time.monotonic()
        try:
            result = {"name": name, "passed": True, "detail": fn()}
        except Exception as error:
            result = {"name": name, "passed": False, "error": repr(error)}
        result["seconds"] = round(time.monotonic() - started, 2)
        self.results.append(result)
        # Preserve the scenario outcome even if ADB stops responding during diagnostics.
        (self.output / "results.json").write_text(json.dumps(self.results, ensure_ascii=False, indent=2), encoding="utf-8")
        result["report"] = self.report()
        for key, command in (("power", "dumpsys deviceidle"),
                             ("service", "dumpsys activity services app.rill.android")):
            try:
                result[key] = self.shell(command, check=False)
            except subprocess.SubprocessError as error:
                result[key] = repr(error)
        (self.output / "results.json").write_text(json.dumps(self.results, ensure_ascii=False, indent=2), encoding="utf-8")
        summary = {k: v for k, v in result.items() if k not in ("report", "power", "service")}
        if isinstance(summary.get("detail"), dict):
            summary["detail"] = {k: v for k, v in summary["detail"].items() if k != "report_during_idle"}
        print(json.dumps(summary), flush=True)

    def traffic(self, seconds):
        samples = []
        until = time.monotonic() + seconds
        while time.monotonic() < until:
            samples.append(self.request())
            time.sleep(2)
        self.health()
        report = self.report()
        assert report.get("snapshot_age_ms", 999999) < 15000, "Diagnostic snapshot stopped updating"
        return {"tracks": len(samples), "max_header_seconds": max(x["header_seconds"] for x in samples),
                "bytes": sum(x["bytes"] for x in samples)}

    def starvation(self):
        with concurrent.futures.ThreadPoolExecutor() as pool:
            blocked = pool.submit(self.request, "/blocked", 35)
            assert BLOCKED.wait(5), "Slow preflight did not start"
            try:
                self.health()
                return self.request(timeout=5)
            finally:
                RELEASE.set()
                blocked.result(timeout=35)

    def wifi_reconnect(self):
        self.shell("svc wifi disable")
        try:
            self.health()
            with concurrent.futures.ThreadPoolExecutor() as pool:
                pending = pool.submit(self.request, None, 45)
                time.sleep(8)
                self.shell("svc wifi enable")
                self.shell("cmd wifi connect-network AndroidWifi open")
                transfer = pending.result(timeout=45)
            return {"pending_transfer": transfer, "after_reconnect": self.request()}
        finally:
            self.shell("svc wifi enable")

    def doze(self, exempt):
        self.shell(f"dumpsys deviceidle whitelist {'+' if exempt else '-'}app.rill.android")
        self.shell("input keyevent 223")
        self.shell("dumpsys deviceidle force-idle")
        time.sleep(3)
        assert "mState=IDLE" in self.shell("dumpsys deviceidle"), "Doze was not entered"
        try:
            if exempt:
                return self.traffic(self.args.duration)
            result = {"report_during_idle": self.report()}
            try:
                result["transfer"] = self.request(timeout=12)
                result["network_block_observed"] = False
            except (TimeoutError, OSError, http.client.HTTPException, AssertionError) as error:
                result["network_block_observed"] = True
                result["blocked_error"] = repr(error)
            return result
        finally:
            self.shell("dumpsys deviceidle unforce")
            self.shell("input keyevent 224")

    def restart(self):
        before = self.shell("pidof app.rill.android")
        assert before.isdigit()
        self.shell(f"run-as app.rill.android kill -9 {before}")
        started = time.monotonic()
        last_error = None
        while time.monotonic() - started < 90:
            after = self.shell("pidof app.rill.android", check=False)
            if after.isdigit() and after != before:
                try:
                    self.health()
                    return {"before_pid": before, "after_pid": after,
                            "recovery_seconds": round(time.monotonic() - started, 2),
                            "transfer": self.request()}
                except Exception as error:
                    last_error = repr(error)
            time.sleep(2)
        raise AssertionError(f"Sticky service did not recover: {last_error}")

    def suspend(self):
        process = self.shell("pidof app.rill.android")
        assert process.isdigit()
        self.shell(f"run-as app.rill.android kill -STOP {process}")
        try:
            with concurrent.futures.ThreadPoolExecutor() as pool:
                request = pool.submit(self.request, None, 20)
                try:
                    time.sleep(7)
                    assert not request.done(), "Suspended process unexpectedly served a request"
                finally:
                    self.shell(f"run-as app.rill.android kill -CONT {process}")
                transfer = request.result(timeout=20)
                deadline = time.monotonic() + 12
                while time.monotonic() < deadline:
                    gap = self.report().get("service_max_tick_gap_ms", 0)
                    if gap >= 7000:
                        return {"transfer_after_os_resumes": transfer, "recorded_tick_gap_ms": gap}
                    time.sleep(1)
                raise AssertionError("Suspension was not retained in heartbeat diagnostics")
        finally:
            self.shell(f"run-as app.rill.android kill -CONT {process}", check=False)

    def idle_resume(self):
        self.request()
        before = self.shell("pidof app.rill.android")
        self.shell("input keyevent 223")
        # No ADB polling or player traffic during the idle interval.
        time.sleep(180)
        transfer = self.request()
        after = self.shell("pidof app.rill.android")
        return {"idle_seconds": 180, "same_process": before == after, "transfer": transfer}

    def restricted_network(self):
        self.original_restricted_mode = self.shell("settings get global restricted_networking_mode")
        self.shell("settings put global restricted_networking_mode 1")
        try:
            deadline = time.monotonic() + 18
            while time.monotonic() < deadline:
                report = self.report()
                if report.get("wifi", {}).get("blocked"):
                    break
                time.sleep(1)
            else:
                raise AssertionError("System did not deliver a blocked-network callback")
            try:
                self.request(timeout=4)
            except (TimeoutError, OSError, http.client.HTTPException, AssertionError):
                pass
            else:
                raise AssertionError("Transfer unexpectedly succeeded while network was blocked")
        finally:
            self.restore_network_restriction()
        return {"blocked_callback_observed": True, "transfer_after_unblock": self.request(timeout=40)}

    def restore_network_restriction(self):
        previous = self.original_restricted_mode
        if previous is None:
            return
        if previous == "null":
            self.shell("settings delete global restricted_networking_mode")
        else:
            self.shell(f"settings put global restricted_networking_mode {int(previous)}")
        self.original_restricted_mode = None

    def setup(self):
        assert self.args.serial.startswith("emulator-"), "Only isolated emulators are supported"
        assert self.shell("getprop ro.hardware") in ("ranchu", "goldfish")
        metadata = {key: self.shell(f"getprop {key}") for key in
                    ("ro.build.version.sdk", "ro.build.version.release", "ro.build.fingerprint", "ro.product.model")}
        assert int(metadata["ro.build.version.sdk"]) >= 33, "Requires Android 13+"
        (self.output / "device.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
        self.shell("dumpsys deviceidle unforce")
        self.shell("dumpsys battery unplug")
        self.shell("cmd power set-mode 0")
        self.shell("dumpsys deviceidle whitelist -app.rill.android")
        self.shell("input keyevent 224")
        self.shell("wm dismiss-keyguard")
        self.shell("am force-stop app.rill.android")
        prefs = f'''<?xml version="1.0" encoding="utf-8"?><map>
<string name="targets">http://10.0.2.2:{self.server.server_port}</string>
<boolean name="notifications_asked" value="true"/>
<boolean name="background_asked" value="true"/>
<boolean name="relay_enabled" value="false"/>
</map>'''
        self.shell("run-as app.rill.android sh -c 'mkdir -p shared_prefs && cat > shared_prefs/rill.xml'", data=prefs.encode())
        self.shell("pm grant app.rill.android android.permission.POST_NOTIFICATIONS")
        self.shell("svc wifi enable")
        self.shell("cmd wifi connect-network AndroidWifi open")
        self.shell("am start -W -n app.rill.android/.MainActivity")
        self.shell("uiautomator dump /sdcard/rill-test-ui.xml")
        tree = ET.fromstring(self.shell("cat /sdcard/rill-test-ui.xml"))
        button = next(node for node in tree.iter("node") if node.get("text") == "开启")
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", button.get("bounds")))
        self.shell(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
        self.forward = int(self.adb("forward", "tcp:0", "tcp:18889"))
        time.sleep(2)
        self.shell("am start -W -a android.settings.SETTINGS")
        self.health()

    def run(self):
        try:
            self.setup()
            self.run_case("slow_preflight_isolation", self.starvation)
            self.run_case("screen_on_background", lambda: self.traffic(self.args.duration))
            self.shell("input keyevent 223")
            self.run_case("screen_off_long_stream", lambda: self.request(slow=True))
            self.shell("cmd power set-mode 1")
            self.run_case("battery_saver", lambda: self.traffic(self.args.duration))
            self.shell("cmd power set-mode 0")
            self.run_case("doze_without_exemption", lambda: self.doze(False))
            self.run_case("doze_exit_recovery", lambda: self.traffic(20))
            self.run_case("doze_with_exemption", lambda: self.doze(True))
            self.run_case("wifi_reconnection", self.wifi_reconnect)
            self.run_case("restricted_network_recovery", self.restricted_network)
            self.run_case("process_suspension_recovery", self.suspend)
            self.run_case("process_death_recovery", self.restart)
            self.run_case("post_recovery_background", lambda: self.traffic(self.args.duration))
            self.run_case("buffered_playback_idle_resume", self.idle_resume)
        finally:
            RELEASE.set()
            try:
                self.restore_network_restriction()
            except subprocess.SubprocessError as error:
                print(f"Network setting cleanup failed: {error}", flush=True)
            for command in ("dumpsys deviceidle unforce", "cmd power set-mode 0", "dumpsys battery reset",
                            "dumpsys deviceidle whitelist -app.rill.android", "svc wifi enable", "input keyevent 224"):
                try:
                    self.shell(command, check=False)
                except subprocess.SubprocessError as error:
                    print(f"Cleanup failed: {error}", flush=True)
            if self.forward:
                try:
                    self.adb("forward", "--remove", f"tcp:{self.forward}")
                except subprocess.SubprocessError as error:
                    print(f"Forward cleanup failed: {error}", flush=True)
            self.server.shutdown()
        if any(not result["passed"] for result in self.results):
            raise SystemExit(1)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--duration", type=int, default=120)
    parser.add_argument("--only", nargs="+", help="Run only the named scenarios")
    Matrix(parser.parse_args()).run()
