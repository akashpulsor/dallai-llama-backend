"""Apply one allowlisted RVC speaker adapter to prepared source speech.

The avatar worker runs on Windows for the current RTX 4060 deployment, while
Applio and its CUDA environment live in WSL. This wrapper forwards paths into
WSL when needed and runs natively when the worker is already on Linux.
"""
from __future__ import annotations

import argparse
import json
import os
import shlex
import subprocess
import sys
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run a packaged RVC voice adapter.")
    parser.add_argument("--input", required=True, help="Prepared source-speech audio")
    parser.add_argument("--output", required=True, help="Destination WAV")
    parser.add_argument("--model", required=True, help="Packaged voice.pth")
    parser.add_argument("--index", required=True, help="Packaged voice.index")
    parser.add_argument("--applio-root", required=True)
    parser.add_argument("--wsl-venv", default="~/.venvs/indic-voice-benchmark")
    parser.add_argument("--pitch", type=int, default=0)
    parser.add_argument("--index-rate", type=float, default=0.6)
    parser.add_argument("--volume-envelope", type=float, default=0.8)
    parser.add_argument("--protect", type=float, default=0.4)
    parser.add_argument("--f0-method", default="rmvpe")
    parser.add_argument("--embedder", default="contentvec")
    parser.add_argument("--native", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if os.name == "nt" and not args.native:
        return run_in_wsl(args)
    return run_native(args)


def run_in_wsl(args: argparse.Namespace) -> int:
    converted = {
        "input": wsl_path(args.input),
        "output": wsl_path(args.output),
        "model": wsl_path(args.model),
        "index": wsl_path(args.index),
        "script": wsl_path(Path(__file__).resolve()),
    }
    native_args = [
        "python",
        converted["script"],
        "--native",
        "--input",
        converted["input"],
        "--output",
        converted["output"],
        "--model",
        converted["model"],
        "--index",
        converted["index"],
        "--applio-root",
        args.applio_root,
        "--pitch",
        str(args.pitch),
        "--index-rate",
        str(args.index_rate),
        "--volume-envelope",
        str(args.volume_envelope),
        "--protect",
        str(args.protect),
        "--f0-method",
        args.f0_method,
        "--embedder",
        args.embedder,
    ]
    activate = shell_path(args.wsl_venv.rstrip("/") + "/bin/activate")
    command = f"source {activate} && " + " ".join(shlex.quote(value) for value in native_args)
    completed = subprocess.run(["wsl.exe", "bash", "-lc", command], check=False)
    return completed.returncode


def run_native(args: argparse.Namespace) -> int:
    input_path = require_file(args.input, "source speech")
    model_path = require_file(args.model, "RVC model")
    index_path = require_file(args.index, "RVC feature index")
    applio_root = require_directory(args.applio_root, "Applio root")
    output_path = Path(args.output).expanduser().resolve()
    output_path.parent.mkdir(parents=True, exist_ok=True)

    os.chdir(applio_root)
    sys.path.insert(0, str(applio_root))
    from rvc.infer.infer import VoiceConverter

    converter = VoiceConverter()
    try:
        converter.convert_audio(
            audio_input_path=str(input_path),
            audio_output_path=str(output_path),
            model_path=str(model_path),
            index_path=str(index_path),
            pitch=args.pitch,
            f0_method=args.f0_method,
            index_rate=args.index_rate,
            volume_envelope=args.volume_envelope,
            protect=args.protect,
            embedder_model=args.embedder,
            clean_audio=False,
            export_format="WAV",
            split_audio=False,
        )
    finally:
        converter.cleanup_model()

    if not output_path.is_file() or output_path.stat().st_size < 1000:
        raise RuntimeError(f"RVC did not create a valid output WAV: {output_path}")
    print(json.dumps({"status": "COMPLETED", "output": str(output_path)}))
    return 0


def wsl_path(value: str | Path) -> str:
    path = str(Path(value).expanduser().resolve())
    completed = subprocess.run(
        ["wsl.exe", "--exec", "wslpath", "-a", path],
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return completed.stdout.strip()


def shell_path(value: str) -> str:
    if value.startswith("~/"):
        return '"$HOME/' + value[2:].replace('"', '\\"') + '"'
    return shlex.quote(value)


def require_file(value: str, label: str) -> Path:
    path = Path(value).expanduser().resolve()
    if not path.is_file():
        raise FileNotFoundError(f"{label} was not found: {path}")
    return path


def require_directory(value: str, label: str) -> Path:
    path = Path(value).expanduser().resolve()
    if not path.is_dir():
        raise FileNotFoundError(f"{label} was not found: {path}")
    return path


if __name__ == "__main__":
    raise SystemExit(main())
