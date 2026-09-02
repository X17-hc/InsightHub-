#!/usr/bin/env python3
"""Run setup-agent.sh via systemd-run so it survives SSH disconnect."""
from __future__ import annotations

import os
import sys
import time

import paramiko

HOST = os.environ.get("UBUNTU_SSH_HOST", "192.168.100.129")
USER = os.environ.get("UBUNTU_SSH_USER", "chang")
PASSWORD = os.environ.get("UBUNTU_SSH_PASSWORD")
if not PASSWORD:
    print("Set UBUNTU_SSH_PASSWORD env var", file=sys.stderr)
    raise SystemExit(2)

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
    safe_print("connecting...")
    client.connect(
        HOST,
        username=USER,
        password=PASSWORD,
        timeout=20,
        allow_agent=False,
        look_for_keys=False,
    )
    safe_print("connected")

    def run(cmd: str, timeout: int = 120) -> tuple[int, str]:
        safe_print("\n$ " + cmd[:220])
        stdin, stdout, stderr = client.exec_command(cmd, get_pty=True, timeout=timeout)
        if "sudo " in cmd:
            stdin.write(PASSWORD + "\n")
            stdin.flush()
        out = stdout.read().decode("utf-8", errors="replace")
        code = stdout.channel.recv_exit_status()
        safe_print(out[-5000:] if len(out) > 5000 else out)
        safe_print("[exit] %s" % code)
        return code, out

    sftp = client.open_sftp()
    loc = os.path.join(LOCAL_DEMO, "deploy", "ubuntu", "setup-agent.sh")
    rem = REMOTE_HOME_DEMO + "/deploy/ubuntu/setup-agent.sh"
    safe_print("upload " + rem)
    sftp.put(loc, rem)
    sftp.close()

    # 停止可能残留的旧安装单元，用 systemd-run 启动独立安装
    run(
        "sudo -S bash -lc '"
        f"systemctl stop {UNIT} 2>/dev/null || true; "
        f"systemctl reset-failed {UNIT} 2>/dev/null || true; "
        f"cp -f {REMOTE_HOME_DEMO}/deploy/ubuntu/setup-agent.sh /tmp/setup-agent.sh; "
        "chmod +x /tmp/setup-agent.sh; sed -i \"s/\\r$//\" /tmp/setup-agent.sh; "
        f"rm -f {LOG}; "
        f"systemd-run --unit={UNIT} --property=Type=oneshot "
        f"--property=RemainAfterExit=yes "
        f"--working-directory=/tmp "
        f'/bin/bash -lc "HOST_ALLOW={HOST_ALLOW} bash /tmp/setup-agent.sh > {LOG} 2>&1"; '
        f"systemctl is-active {UNIT} || true; sleep 2; wc -c {LOG}; head -n 8 {LOG} || true'",
        90,
    )

    deadline = time.time() + 2400
    while time.time() < deadline:
        code, out = run(
            "sudo -S bash -lc '"
            f"echo STATE:$(systemctl is-active {UNIT} 2>/dev/null || true); "
            f"echo RESULT:$(systemctl show -p Result --value {UNIT} 2>/dev/null || true); "
            f"wc -c {LOG} 2>/dev/null; "
            f"tail -n 20 {LOG} 2>/dev/null'",
            60,
        )
        # oneshot RemainAfterExit=yes → active 表示成功结束；activating 表示仍在跑
        if "STATE:active" in out and "RESULT:success" in out:
            safe_print("setup unit finished success")
            break
        if "STATE:failed" in out or "RESULT:exit-code" in out or "RESULT:timeout" in out:
            safe_print("setup unit FAILED")
            run(f"sudo -S bash -lc 'tail -n 120 {LOG}; systemctl status {UNIT} --no-pager || true'", 60)
            client.close()
            return 1
        # inactive without remain? treat as still starting unless log says 完成
        if "安装完成" in out or "========================================" in out:
            break
        time.sleep(25)
    else:
        safe_print("TIMEOUT")
        run(f"sudo -S bash -lc 'tail -n 80 {LOG}; systemctl status {UNIT} --no-pager || true'", 60)
        client.close()
        return 1

    run(f"sudo -S bash -lc 'tail -n 60 {LOG}'", 60)
    run("sudo -S systemctl is-active insighthub-agent || true", 60)
    run("curl -fsS http://127.0.0.1:8000/health || true", 60)
    run("sudo -S docker ps --format '{{.Names}} {{.Status}}' || true", 60)
    client.close()
    safe_print("\nDONE")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
