"""文档解析：txt/md/pdf → 纯文本。"""

from __future__ import annotations

from pathlib import Path


def parse_file(path: str | Path, content_type: str | None = None) -> str:
    """
    解析本地文件为纯文本。

    Args:
        path: 文件路径。
        content_type: MIME 或扩展名提示。

    Returns:
        提取的文本。

    Raises:
        ValueError: 不支持的类型或无法读取。
        FileNotFoundError: 文件不存在。
    """
    p = Path(path)
    if not p.is_file():
        raise FileNotFoundError(f"file not found: {p}")

    suffix = p.suffix.lower()
    ct = (content_type or "").lower()
    if suffix in {".txt", ".md", ".markdown"} or "text/" in ct or "markdown" in ct:
        return p.read_text(encoding="utf-8", errors="replace")
    if suffix == ".pdf" or "pdf" in ct:
        return _parse_pdf(p)
    raise ValueError(f"unsupported file type: {suffix or ct}")


def _parse_pdf(path: Path) -> str:
    """使用 pypdf 抽取 PDF 文本。"""
    from pypdf import PdfReader

    reader = PdfReader(str(path))
    parts: list[str] = []
    for page in reader.pages:
        try:
            text = page.extract_text() or ""
        except Exception:  # noqa: BLE001
            text = ""
        if text.strip():
            parts.append(text)
    text = "\n\n".join(parts).strip()
    if not text:
        raise ValueError("pdf has no extractable text")
    return text
