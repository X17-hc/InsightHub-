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
    assert "--init" in command
    assert "--read-only" in command
    assert "--cap-drop=ALL" in command
    assert "--security-opt=no-new-privileges" in command
    assert "--user=65534:65534" in command
    assert "MPLCONFIGDIR=/tmp/matplotlib" in command
    assert "OPENBLAS_NUM_THREADS=1" in command
    input_mount = next(item for item in command if item.endswith(":/input:ro"))
    input_dir = Path(input_mount.removesuffix(":/input:ro"))
    assert input_dir.stat().st_mode & 0o777 == 0o555
    assert input_dir.joinpath("evidence.json").stat().st_mode & 0o777 == 0o444
    assert input_dir.joinpath("script.py").stat().st_mode & 0o777 == 0o444
    assert len(artifacts) == 1
    assert artifacts[0]["storageUri"].startswith("artifact://task-1/")


def test_timeout_force_removes_named_container(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    monkeypatch.setenv("SANDBOX_ENABLED", "true")
    monkeypatch.setenv("ARTIFACT_ROOT_DIR", str(tmp_path))
    monkeypatch.setenv("SANDBOX_TIMEOUT_SECONDS", "120")
    get_settings.cache_clear()
    calls: list[list[str]] = []

    def fake_run(command: list[str], **_: object) -> SimpleNamespace:
        calls.append(command)
        if command[1] == "run":
            raise subprocess.TimeoutExpired(command, timeout=120)
        return SimpleNamespace(returncode=0, stdout="", stderr="")

    monkeypatch.setattr(analysis_sandbox.shutil, "which", lambda _: "/usr/bin/docker")
    monkeypatch.setattr(analysis_sandbox.subprocess, "run", fake_run)

    with pytest.raises(analysis_sandbox.SandboxExecutionTimeout, match="exceeded 120 seconds"):
        analysis_sandbox.run_analysis(
            task_id="task-timeout", workspace_id="workspace-1", run_id="run-1", evidence=[]
        )

    run_command = next(command for command in calls if command[1] == "run")
    container_name = run_command[run_command.index("--name") + 1]
    assert ["docker", "rm", "--force", container_name] in calls


def test_resolve_artifact_maps_logical_uri_to_fixed_output_directory(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    monkeypatch.setenv("ARTIFACT_ROOT_DIR", str(tmp_path))
    get_settings.cache_clear()
    expected = tmp_path / "task-1" / "job-1" / "output" / "summary.json"
    expected.parent.mkdir(parents=True)
    expected.write_text("{}", encoding="utf-8")

    assert analysis_sandbox.resolve_artifact("artifact://task-1/job-1/summary.json") == expected.resolve()
    with pytest.raises(FileNotFoundError, match="invalid artifact URI"):
        analysis_sandbox.resolve_artifact("artifact://task-1/../summary.json")
