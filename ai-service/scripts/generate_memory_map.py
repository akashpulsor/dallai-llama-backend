"""Generate a repo-local memory map that can be consumed by any LLM.

Outputs:
  .llm_memory/manifest.json
  .llm_memory/manifest.md
  .llm_memory/files/<repo-path>.json
"""

from __future__ import annotations

import ast
import json
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
OUTPUT_DIR = ROOT / ".llm_memory"
FILES_DIR = OUTPUT_DIR / "files"

EXCLUDED_DIRS = {
    ".git",
    ".idea",
    ".llm_memory",
    ".mypy_cache",
    ".pytest_cache",
    ".ruff_cache",
    ".venv",
    "__pycache__",
    "node_modules",
}

TEXT_EXTENSIONS = {
    ".md",
    ".py",
    ".sql",
    ".txt",
    ".xml",
    ".yaml",
    ".yml",
    ".json",
    ".dockerignore",
    ".gitignore",
}


@dataclass
class FileMap:
    path: str
    language: str
    lines: int
    summary: str
    imports: list[str]
    classes: list[dict[str, Any]]
    functions: list[dict[str, Any]]
    constants: list[str]


def iter_files(root: Path):
    for path in sorted(root.rglob("*")):
        if not path.is_file():
            continue
        if any(part in EXCLUDED_DIRS for part in path.parts):
            continue
        if path.suffix.lower() not in TEXT_EXTENSIONS and path.name not in {".gitignore", ".dockerignore"}:
            continue
        yield path


def rel(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def safe_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        return path.read_text(encoding="utf-8", errors="replace")


def short_summary(text: str) -> str:
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if not lines:
        return ""
    first = lines[0]
    if first.startswith('"""') or first.startswith("'''"):
        cleaned = first.strip("\"'")
        if cleaned:
            return cleaned[:200]
    return first[:200]


def format_args(node: ast.FunctionDef | ast.AsyncFunctionDef) -> list[str]:
    args: list[str] = []
    for arg in node.args.posonlyargs + node.args.args:
        args.append(arg.arg)
    if node.args.vararg:
        args.append(f"*{node.args.vararg.arg}")
    for arg in node.args.kwonlyargs:
        args.append(arg.arg)
    if node.args.kwarg:
        args.append(f"**{node.args.kwarg.arg}")
    return args


def decorator_name(node: ast.AST) -> str:
    if isinstance(node, ast.Name):
        return node.id
    if isinstance(node, ast.Attribute):
        prefix = decorator_name(node.value)
        return f"{prefix}.{node.attr}" if prefix else node.attr
    if isinstance(node, ast.Call):
        return decorator_name(node.func)
    return ""


def parse_python(path: Path, text: str) -> FileMap:
    tree = ast.parse(text)
    imports: list[str] = []
    classes: list[dict[str, Any]] = []
    functions: list[dict[str, Any]] = []
    constants: list[str] = []

    module_doc = ast.get_docstring(tree) or short_summary(text)

    for node in tree.body:
        if isinstance(node, ast.Import):
            imports.extend(alias.name for alias in node.names)
        elif isinstance(node, ast.ImportFrom):
            module = node.module or ""
            imports.append(module)
        elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            functions.append(
                {
                    "name": node.name,
                    "async": isinstance(node, ast.AsyncFunctionDef),
                    "args": format_args(node),
                    "decorators": [name for name in (decorator_name(d) for d in node.decorator_list) if name],
                    "doc": (ast.get_docstring(node) or "")[:240],
                    "line": node.lineno,
                }
            )
        elif isinstance(node, ast.ClassDef):
            methods: list[dict[str, Any]] = []
            for child in node.body:
                if isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef)):
                    methods.append(
                        {
                            "name": child.name,
                            "async": isinstance(child, ast.AsyncFunctionDef),
                            "args": format_args(child),
                            "decorators": [name for name in (decorator_name(d) for d in child.decorator_list) if name],
                            "line": child.lineno,
                        }
                    )
            classes.append(
                {
                    "name": node.name,
                    "bases": [ast.unparse(base) for base in node.bases],
                    "doc": (ast.get_docstring(node) or "")[:240],
                    "line": node.lineno,
                    "methods": methods,
                }
            )
        elif isinstance(node, ast.Assign):
            for target in node.targets:
                if isinstance(target, ast.Name) and target.id.isupper():
                    constants.append(target.id)

    return FileMap(
        path=rel(path),
        language="python",
        lines=len(text.splitlines()),
        summary=module_doc[:300],
        imports=sorted({item for item in imports if item}),
        classes=classes,
        functions=functions,
        constants=sorted(set(constants)),
    )


def parse_text_file(path: Path, text: str) -> FileMap:
    headings = []
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("#"):
            headings.append(stripped[:120])
    return FileMap(
        path=rel(path),
        language=path.suffix.lower().lstrip(".") or "text",
        lines=len(text.splitlines()),
        summary=short_summary(text),
        imports=[],
        classes=[],
        functions=[{"name": heading, "line": 0} for heading in headings[:20]],
        constants=[],
    )


def build_map(path: Path) -> FileMap:
    text = safe_text(path)
    if path.suffix.lower() == ".py":
        return parse_python(path, text)
    return parse_text_file(path, text)


def write_file_map(file_map: FileMap):
    out_path = FILES_DIR / f"{file_map.path.replace('/', '__')}.json"
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(file_map.__dict__, indent=2, ensure_ascii=True), encoding="utf-8")


def write_manifest(file_maps: list[FileMap]):
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    FILES_DIR.mkdir(parents=True, exist_ok=True)

    manifest = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "root": str(ROOT),
        "files": [file_map.__dict__ for file_map in file_maps],
    }
    (OUTPUT_DIR / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=True), encoding="utf-8")

    lines = [
        "# Code Memory Map",
        "",
        f"Generated: {manifest['generated_at']}",
        "",
        "## Files",
        "",
    ]
    for file_map in file_maps:
        lines.append(f"### {file_map.path}")
        lines.append(f"- Language: {file_map.language}")
        lines.append(f"- Lines: {file_map.lines}")
        if file_map.summary:
            lines.append(f"- Summary: {file_map.summary}")
        if file_map.imports:
            lines.append(f"- Imports: {', '.join(file_map.imports[:12])}")
        if file_map.classes:
            lines.append(f"- Classes: {', '.join(cls['name'] for cls in file_map.classes[:12])}")
        if file_map.functions:
            names = [fn["name"] for fn in file_map.functions[:12]]
            lines.append(f"- Functions/Entries: {', '.join(names)}")
        lines.append("")
    (OUTPUT_DIR / "manifest.md").write_text("\n".join(lines), encoding="utf-8")


def main():
    file_maps = [build_map(path) for path in iter_files(ROOT)]
    for file_map in file_maps:
        write_file_map(file_map)
    write_manifest(file_maps)
    print(f"Wrote memory map for {len(file_maps)} files to {OUTPUT_DIR}")


if __name__ == "__main__":
    main()
