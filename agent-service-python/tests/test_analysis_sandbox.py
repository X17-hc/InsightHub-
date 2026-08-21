"""Focused unit tests for the fixed, isolated analysis Sandbox."""

from __future__ import annotations

import subprocess
from pathlib import Path
from types import SimpleNamespace

import pytest

from app.core.config import get_settings
from app.services import analysis_sandbox


def test_ast_rejects_unsafe_import_and_execution() -> None:
    with pytest.raises(ValueError, match="import"):
        analysis_sandbox._validate_script("import os")
    with pytest.raises(ValueError, match="call"):
        analysis_sandbox._validate_script("eval('1 + 1')")


def test_run_uses_fixed_restricted_docker_command(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    monkeypatch.setenv("SANDBOX_ENABLED", "true")
    monkeypatch.setenv("ARTIFACT_ROOT_DIR", str(tmp_path))
    get_settings.cache_clear()
    calls: list[list[str]] = []

    def fake_run(command: list[str], **_: object) -> SimpleNamespace:
        calls.append(command)
        if command[1] == "run":
            output_mount = next(item for item in command if item.endswith(":/output:rw"))
            Path(output_mount.removesuffix(":/output:rw")).joinpath("summary.json").write_text("{}", encoding="utf-8")
        return SimpleNamespace(returncode=0, stdout="ok", stderr="")

    monkeypatch.setattr(analysis_sandbox.shutil, "which", lambda _: "/usr/bin/docker")
    monkeypatch.setattr(analysis_sandbox.subprocess, "run", fake_run)

    artifacts = analysis_sandbox.run_analysis(
        task_id="task-1", workspace_id="workspace-1", run_id="run-1", evidence=[]
    )

    command = calls[-1]
    assert "--network=none" in command
    assert "--read-only" in command
    assert "--cap-drop=ALL" in command
    assert "--security-opt=no-new-privileges" in command
    assert "--user=65534:65534" in command
    assert len(artifacts) == 1
    assert artifacts[0]["storageUri"].startswith("artifact://task-1/")
