#!/usr/bin/env python3
"""Check remote agent install status."""
from __future__ import annotations

import os
import sys

import paramiko

PASSWORD = os.environ.get("UBUNTU_SSH_PASSWORD")
if not PASSWORD:
    raise SystemExit("Set UBUNTU_SSH_PASSWORD")


def main() -> int:
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(
        "192.168.125.128",
        username="chang",
        password=PASSWORD,
        timeout=20,
        allow_agent=False,
        look_for_keys=False,
    )
    cmd = r"""sudo -S bash -lc '
systemctl is-active insighthub-agent 2>/dev/null || true
docker ps --format "{{.Names}} {{.Status}}" 2>/dev/null || true
ls -la /etc/insighthub/agent.env 2>/dev/null || echo NO_ENV
test -x /opt/insighthub/agent/.venv/bin/python && echo VENV_OK || echo VENV_MISS
curl -fsS http://127.0.0.1:8000/health 2>&1 | head -c 200 || true
echo
pgrep -af setup-agent || echo NO_SETUP_RUNNING
'
"""
    stdin, stdout, stderr = c.exec_command(cmd, get_pty=True, timeout=60)
    stdin.write(PASSWORD + "\n")
    stdin.flush()
    out = stdout.read().decode("utf-8", errors="replace")
    print(out.encode("ascii", "replace").decode())
    print("exit", stdout.channel.recv_exit_status())
    c.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
