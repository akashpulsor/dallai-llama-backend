"""Run local CodeFormer face restoration for the avatar runtime."""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", required=True)
    parser.add_argument("--input-image", required=True)
    parser.add_argument("--output-image", required=True)
    parser.add_argument("--fidelity", default="0.7")
    parser.add_argument("--detection-model", default="retinaface_resnet50")
    parser.add_argument("--only-center-face", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    repo = Path(args.repo).resolve()
    input_image = Path(args.input_image).resolve()
    output_image = Path(args.output_image).resolve()
    output_image.parent.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="codeformer-") as temp_dir:
        temp_result = Path(temp_dir) / "result"
        command = [
            sys.executable,
            str(repo / "inference_codeformer.py"),
            "-i",
            str(input_image),
            "-o",
            str(temp_result),
            "-w",
            str(args.fidelity),
            "--bg_upsampler",
            "None",
            "--detection_model",
            args.detection_model,
        ]
        if args.only_center_face:
            command.append("--only_center_face")

        env = os.environ.copy()
        env["PYTHONPATH"] = str(repo) + os.pathsep + env.get("PYTHONPATH", "")
        subprocess.run(command, cwd=repo, env=env, check=True)

        final_dir = temp_result / "final_results"
        candidates = sorted(final_dir.glob("*.png"))
        if not candidates:
            raise FileNotFoundError(f"CodeFormer did not create a final result in {final_dir}")
        shutil.copyfile(candidates[0], output_image)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
