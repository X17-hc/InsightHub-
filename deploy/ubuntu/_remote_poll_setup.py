#!/usr/bin/env python3
"""Poll / resume setup after docker images are already pulled."""
from __future__ import annotations

import os
import sys
import time

import paramiko

HOST = os.environ.get("UBUNTU_SSH_HOST", "192.168.100.129")
USER = os.environ.get("UBUNTU_SSH_USER", "chang")
PASSWORD = os.environ.get("UBUNTU_SSH_PASSWORD")
if not PASSWORD:
    raise SystemExit("Set UBUNTU_SSH_PASSWORD")

LOCAL_DEMO = os.environ.get("INSIGHTHUB_SOURCE_ROOT")
if not LOCAL_DEMO:
    raise SystemExit("Set INSIGHTHUB_SOURCE_ROOT")
REMOTE_HOME_DEMO = os.environ.get("INSIGHTHUB_REMOTE_ROOT", "/home/chang/insighthub/InsightHub-")
HOST_ALLOW = os.environ.get("INSIGHTHUB_WINDOWS_HOST", "192.168.100.1")
UNIT = "insighthub-setup.service"
LOG = "/tmp/setup-agent.log"


def safe_print(text: str) -> None:
    enc = sys.stdout.encoding or "utf-8"
    print(text.encode(enc, errors="replace").decode(enc, errors="replace"))


def main() -> int:
    client = paramiko.SSHClient()
    client.load_system_host_keys()
    client.set_missing_host_key_policy(paramiko.RejectPolicy())
    client.connect(
        HOST,
        username=USER,
        password=PASSWORD,
        timeout=20,
        allow_agent=False,
        look_for_keys=False,
    )

    def run(cmd: str, timeout: int = 90) -> tuple[int, str]:
        safe_print("\n$ " + cmd[:180])
        stdin, stdout, stderr = client.exec_command(cmd, get_pty=True, timeout=timeout)
        if "sudo " in cmd:
            stdin.write(PASSWORD + "\n")
            stdin.flush()
        out = stdout.read().decode("utf-8", errors="replace")
        code = stdout.channel.recv_exit_status()
        safe_print(out[-4000:] if len(out) > 4000 else out)
        safe_print("[exit] %s" % code)
        return code, out

    _, out = run(
        "sudo -S bash -lc "
        + repr(
            f"echo STATE:$(systemctl is-active {UNIT} 2>/dev/null || true); "
            f"echo RESULT:$(systemctl show -p Result --value {UNIT} 2>/dev/null || true); "
            f"wc -c {LOG} 2>/dev/null; tail -n 12 {LOG} 2>/dev/null"
        )
    )

    need_start = True
    if "STATE:activating" in out:
        safe_print("setup already running")
        need_start = False
    elif "STATE:active" in out and "RESULT:success" in out and "安装完成" in out:
        safe_print("setup already done")
        need_start = False
    elif "STATE:active" in out and "RESULT:success" in out:
        # finished but maybe old run; restart
        need_start = True

    if need_start:
        sftp = client.open_sftp()
        sftp.put(
            os.path.join(LOCAL_DEMO, "deploy", "ubuntu", "setup-agent.sh"),
            REMOTE_HOME_DEMO + "/deploy/ubuntu/setup-agent.sh",
        )
        sftp.close()
        inner = (
            f"systemctl stop {UNIT} 2>/dev/null || true; "
            f"systemctl reset-failed {UNIT} 2>/dev/null || true; "
            f"cp -f {REMOTE_HOME_DEMO}/deploy/ubuntu/setup-agent.sh /tmp/setup-agent.sh; "
            "chmod +x /tmp/setup-agent.sh; sed -i 's/\\r$//' /tmp/setup-agent.sh; "
            f"rm -f {LOG}; "
            # --no-block：立即返回，安装在独立 unit 中继续
            f"systemd-run --no-block --unit={UNIT} --property=Type=oneshot "
            f"--property=RemainAfterExit=yes --working-directory=/tmp "
            f"/bin/bash -lc 'HOST_ALLOW={HOST_ALLOW} bash /tmp/setup-agent.sh > {LOG} 2>&1'; "
            f"sleep 2; systemctl is-active {UNIT} || true; head -n 5 {LOG} || true"
        )
        run("sudo -S bash -lc " + repr(inner), timeout=60)

    deadline = time.time() + 2400
    while time.time() < deadline:
        _, out = run(
            "sudo -S bash -lc "
            + repr(
                f"echo STATE:$(systemctl is-active {UNIT} 2>/dev/null || true); "
                f"echo RESULT:$(systemctl show -p Result --value {UNIT} 2>/dev/null || true); "
                f"wc -c {LOG} 2>/dev/null; tail -n 18 {LOG} 2>/dev/null"
            ),
            timeout=60,
        )
        if "STATE:active" in out and "RESULT:success" in out:
            break
        if "STATE:failed" in out:
            run("sudo -S bash -lc " + repr(f"tail -n 120 {LOG}"), 60)
            client.close()
            return 1
        if "安装完成" in out:
            break
        time.sleep(30)
    else:
        safe_print("TIMEOUT")
        client.close()
        return 1

    run("sudo -S bash -lc " + repr(f"tail -n 50 {LOG}"), 60)
    run("sudo -S systemctl is-active insighthub-agent || true", 60)
    run("curl -fsS http://127.0.0.1:8000/health || true", 60)
    run("sudo -S docker ps --format '{{.Names}} {{.Status}}' || true", 60)
    client.close()
    safe_print("\nDONE")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
