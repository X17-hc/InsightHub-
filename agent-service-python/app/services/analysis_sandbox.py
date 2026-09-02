"""Fixed, isolated Docker execution for analysis artifacts."""

from __future__ import annotations

import ast
import json
import shutil
import subprocess
import uuid
from pathlib import Path
from typing import Any

from app.core.config import get_settings

_ALLOWED_SUFFIXES = {".csv": "text/csv", ".json": "application/json", ".parquet": "application/vnd.apache.parquet",
                    ".png": "image/png", ".svg": "image/svg+xml"}
_ALLOWED_IMPORTS = {"json", "pandas", "numpy", "matplotlib", "pathlib"}
_SCRIPT = '''import json
from pathlib import Path
import pandas as pd
import matplotlib.pyplot as plt
evidence = json.loads(Path('/input/evidence.json').read_text(encoding='utf-8'))
rows = [{'source_type': str(x.get('sourceType', 'UNKNOWN')), 'verified': bool(x.get('verified', False))} for x in evidence]
df = pd.DataFrame(rows, columns=['source_type', 'verified'])
df.to_csv('/output/evidence_summary.csv', index=False)
Path('/output/summary.json').write_text(json.dumps({'evidenceCount': len(rows), 'verifiedCount': int(df['verified'].sum()) if not df.empty else 0}, ensure_ascii=False), encoding='utf-8')
if not df.empty:
    df.groupby('source_type').size().plot(kind='bar', title='Evidence by source type')
    plt.tight_layout(); plt.savefig('/output/evidence_by_source.png', dpi=120); plt.close()
'''


class SandboxUnavailable(RuntimeError):
    """Docker CLI、固定镜像或运行开关不满足时抛出。

    调用方应映射为稳定错误码 ``SANDBOX_UNAVAILABLE``，不得把 Docker stderr、
    宿主机路径或镜像仓库凭据透传给 Java。
    """


class SandboxExecutionTimeout(RuntimeError):
    """Raised when the fixed analysis container exceeds its execution budget."""


def _remove_container(container_name: str) -> None:
    """Best-effort cleanup for a Docker client killed by subprocess timeout."""
    try:
        subprocess.run(
            ["docker", "rm", "--force", container_name],
            capture_output=True,
            text=True,
            timeout=10,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        # The original timeout is the actionable failure. Cleanup diagnostics
        # may contain host details, so they are deliberately not propagated.
        pass


def _validate_script(script: str) -> None:
    """在容器启动前执行最小 AST 白名单校验。

    AST 校验是纵深防御而非 Python 沙箱；真正的隔离仍依赖固定镜像、无网络、
    非 root、只读根文件系统、capability drop 与受限挂载。当前脚本是服务端固定
    模板，模型不能修改 Docker 参数或挂载路径。
    """
    tree = ast.parse(script, mode="exec")
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            names = [alias.name.split(".")[0] for alias in node.names]
            if any(name not in _ALLOWED_IMPORTS for name in names):
                raise ValueError("analysis script import is not allowed")
        if isinstance(node, ast.ImportFrom):
            module = (node.module or "").split(".")[0]
            if module not in _ALLOWED_IMPORTS:
                raise ValueError("analysis script import is not allowed")
        if isinstance(node, (ast.Exec if hasattr(ast, "Exec") else ast.Expr,)):
            continue
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id in {"eval", "exec", "open", "__import__"}:
            raise ValueError("analysis script call is not allowed")


def ensure_available() -> None:
    """快速检查 Sandbox 开关、Docker CLI 和固定镜像是否可用。"""
    settings = get_settings()
    if not settings.sandbox_enabled or shutil.which("docker") is None:
        raise SandboxUnavailable("SANDBOX_UNAVAILABLE: Docker CLI is unavailable")
    probe = subprocess.run(["docker", "image", "inspect", settings.sandbox_image], capture_output=True, text=True, timeout=10)
    if probe.returncode != 0:
        raise SandboxUnavailable("SANDBOX_UNAVAILABLE: sandbox image is unavailable")


def run_analysis(*, task_id: str, workspace_id: str, run_id: str, evidence: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """在一次性受限容器中聚合已筛选证据并返回产物元数据。

    输入只读、输出仅限当前任务 job 目录；容器不能联网且使用数值非 root UID。
    超时后先强制移除具名容器，再抛出稳定超时异常。返回的 ``storageUri`` 仅供
    Agent 内部持久化，内部 API DTO 和浏览器响应必须移除该字段。
    """
    settings = get_settings()
    ensure_available()
    _validate_script(_SCRIPT)
    root = Path(settings.artifact_root_dir).resolve()
    job = uuid.uuid4().hex
    container_name = f"insighthub-sandbox-{job}"
    input_dir, output_dir = root / task_id / job / "input", root / task_id / job / "output"
    input_dir.mkdir(parents=True, exist_ok=False); output_dir.mkdir(parents=True, exist_ok=False)
    try:
        evidence_file = input_dir / "evidence.json"
        script_file = input_dir / "script.py"
        evidence_file.write_text(json.dumps(evidence, ensure_ascii=False), encoding="utf-8")
        script_file.write_text(_SCRIPT, encoding="utf-8")
        # The container is deliberately not the service account.  It may read
        # the immutable input but receives write-only access to this one job's
        # output directory; the service account reads the result afterwards.
        # systemd applies UMask=0027, so write_text() otherwise creates 0640
        # files that UID 65534 cannot read through the bind mount.  Only this
        # job's leaf inputs are exposed read-only; protected parent directories
        # still prevent other host users from traversing the artifact tree.
        evidence_file.chmod(0o444)
        script_file.chmod(0o444)
        input_dir.chmod(0o555)
        output_dir.chmod(0o733)
        command = ["docker", "run", "--rm", "--name", container_name, "--init", "--network=none", "--user=65534:65534", "--read-only", "--security-opt=no-new-privileges",
                   "--cap-drop=ALL", f"--cpus={settings.sandbox_cpu_limit}", f"--memory={settings.sandbox_memory_limit}",
                   f"--pids-limit={settings.sandbox_pids_limit}", "--tmpfs=/tmp:rw,nosuid,nodev,size=64m",
                   "--env", "HOME=/tmp", "--env", "MPLCONFIGDIR=/tmp/matplotlib", "--env", "MPLBACKEND=Agg",
                   "--env", "OPENBLAS_NUM_THREADS=1", "--env", "OMP_NUM_THREADS=1", "--env", "MKL_NUM_THREADS=1",
                   "--env", "NUMEXPR_NUM_THREADS=1",
                   "-v", f"{input_dir}:/input:ro", "-v", f"{output_dir}:/output:rw", settings.sandbox_image, "python", "/input/script.py"]
        result = subprocess.run(command, capture_output=True, text=True, timeout=settings.sandbox_timeout_seconds)
        if result.returncode != 0:
            raise RuntimeError("SANDBOX_FAILED: " + result.stderr[-500:])
        files = sorted(path for path in output_dir.iterdir() if path.is_file() and path.suffix.lower() in _ALLOWED_SUFFIXES)
        total = sum(path.stat().st_size for path in files)
        if len(files) > settings.artifact_max_count or total > settings.artifact_max_total_bytes:
            raise RuntimeError("SANDBOX_FAILED: artifact limits exceeded")
        return [{"id": "artifact-" + uuid.uuid4().hex[:20], "taskId": task_id, "workspaceId": workspace_id, "runId": run_id,
                 "artifactType": "CHART" if path.suffix.lower() in {'.png', '.svg'} else "TABLE", "title": path.stem,
                 "storageUri": f"artifact://{task_id}/{job}/{path.name}", "fileName": path.name,
                 "mimeType": _ALLOWED_SUFFIXES[path.suffix.lower()], "size": path.stat().st_size, "status": "SUCCESS",
                 "codeSummary": "fixed evidence aggregation script", "stdoutSummary": result.stdout[-500:]} for path in files]
    except subprocess.TimeoutExpired as exc:
        _remove_container(container_name)
        raise SandboxExecutionTimeout(
            f"SANDBOX_FAILED: execution exceeded {settings.sandbox_timeout_seconds} seconds"
        ) from exc


def resolve_artifact(storage_uri: str) -> Path:
    """把内部逻辑 URI 解析为受控文件，并阻止路径穿越和非白名单类型。

    返回路径只供 Agent 文件接口使用，绝不能序列化给 Java 或浏览器；调用方仍
    需检查符号链接、实际文件大小和 MIME 元数据的一致性。
    """
    root = Path(get_settings().artifact_root_dir).resolve()
    if not storage_uri.startswith("artifact://"):
        raise FileNotFoundError("invalid artifact URI")
    parts = storage_uri.removeprefix("artifact://").split("/")
    # URI deliberately hides the implementation-only output directory.  Keep
    # the logical three-component contract stable while resolving it to the
    # fixed host layout used by run_analysis().
    if len(parts) != 3 or any(not part or part in {".", ".."} for part in parts):
        raise FileNotFoundError("invalid artifact URI")
    task_id, job_id, file_name = parts
    candidate = (root / task_id / job_id / "output" / file_name).resolve()
    if root not in candidate.parents or candidate.suffix.lower() not in _ALLOWED_SUFFIXES:
        raise FileNotFoundError("invalid artifact path")
    return candidate
