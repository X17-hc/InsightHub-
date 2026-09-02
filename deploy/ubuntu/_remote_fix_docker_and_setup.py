#!/usr/bin/env python3
"""Configure Docker registry mirrors on Ubuntu VM and retry image pulls."""
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
DAEMON_JSON = """{
  "registry-mirrors": [
    "https://docker.m.daocloud.io",
    "https://docker.1ms.run",
    "https://dockerproxy.net"
  ]
}
"""


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

    def run(cmd: str, timeout: int = 300) -> tuple[int, str]:
        safe_print("\n$ " + cmd[:200])
        stdin, stdout, stderr = client.exec_command(cmd, get_pty=True, timeout=timeout)
        if "sudo " in cmd:
            stdin.write(PASSWORD + "\n")
            stdin.flush()
        out = stdout.read().decode("utf-8", errors="replace")
        code = stdout.channel.recv_exit_status()
        safe_print(out[-4500:] if len(out) > 4500 else out)
        safe_print("[exit] %s" % code)
        return code, out

    sftp = client.open_sftp()
    with sftp.file("/tmp/daemon.json", "w") as f:
        f.write(DAEMON_JSON)
    sftp.put(
        os.path.join(LOCAL_DEMO, "deploy", "ubuntu", "setup-agent.sh"),
        REMOTE_HOME_DEMO + "/deploy/ubuntu/setup-agent.sh",
    )
    sftp.close()

    code, _ = run(
        "sudo -S bash -lc '"
        "mkdir -p /etc/docker && "
        "cp /tmp/daemon.json /etc/docker/daemon.json && "
        "systemctl restart docker && sleep 3 && "
        "docker info | sed -n \"/Registry Mirrors/,+6p\"'"
    )
    if code != 0:
        client.close()
        return code

    # 先预拉镜像，验证加速可用
    code, out = run(
        "sudo -S bash -lc '"
        "docker pull redis:7.2-alpine && "
        "docker pull pgvector/pgvector:pg16'",
        timeout=900,
    )
    if code != 0:
        safe_print("镜像拉取仍失败，请检查 VM 出网或换镜像源")
        client.close()
        return code

    # 从 compose 起继续：重跑完整 setup（幂等）
    inner = (
        "systemctl stop {u} 2>/dev/null || true; "
        "systemctl reset-failed {u} 2>/dev/null || true; "
        "cp -f {demo}/deploy/ubuntu/setup-agent.sh /tmp/setup-agent.sh; "
        "chmod +x /tmp/setup-agent.sh; sed -i 's/\\r$//' /tmp/setup-agent.sh; "
        "rm -f {log}; "
        "systemd-run --unit={u} --property=Type=oneshot "
        "--property=RemainAfterExit=yes --working-directory=/tmp "
        "/bin/bash -lc 'HOST_ALLOW={host_allow} bash /tmp/setup-agent.sh > {log} 2>&1'"
    ).format(u=UNIT, demo=REMOTE_HOME_DEMO, log=LOG, host_allow=HOST_ALLOW)
    run("sudo -S bash -lc " + repr(inner))

    deadline = time.time() + 2400
    while time.time() < deadline:
        _, out = run(
            "sudo -S bash -lc '"
            f"echo STATE:$(systemctl is-active {UNIT} 2>/dev/null || true); "
            f"echo RESULT:$(systemctl show -p Result --value {UNIT} 2>/dev/null || true); "
            f"wc -c {LOG} 2>/dev/null; tail -n 15 {LOG} 2>/dev/null'",
            timeout=60,
        )
        if "STATE:active" in out and "RESULT:success" in out:
            break
        if "STATE:failed" in out:
            run(f"sudo -S bash -lc 'tail -n 100 {LOG}'", 60)
            client.close()
            return 1
        if "安装完成" in out:
            break
        time.sleep(25)
    else:
        safe_print("TIMEOUT")
        client.close()
        return 1

    run(f"sudo -S bash -lc 'tail -n 40 {LOG}'", 60)
    run("sudo -S systemctl is-active insighthub-agent || true", 60)
    run("curl -fsS http://127.0.0.1:8000/health || true", 60)
    run("sudo -S docker ps --format '{{.Names}} {{.Status}}' || true", 60)
    client.close()
    safe_print("\nDONE")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
