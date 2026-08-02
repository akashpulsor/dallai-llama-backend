"""Run MuseTalk lip-sync generation for the founder-avatar runtime."""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", required=True)
    parser.add_argument("--input-video", required=True)
    parser.add_argument("--audio", required=True)
    parser.add_argument("--output-video", required=True)
    parser.add_argument("--version", default="v15", choices=["v1", "v15"])
    parser.add_argument("--batch-size", default="1")
    parser.add_argument("--gpu-id", default="0")
    parser.add_argument("--fps", default="25")
    parser.add_argument("--bbox-shift", default="0")
    parser.add_argument("--extra-margin", default="10")
    parser.add_argument("--parsing-mode", default="jaw")
    parser.add_argument("--left-cheek-width", default="90")
    parser.add_argument("--right-cheek-width", default="90")
    parser.add_argument("--ffmpeg-path", default="")
    parser.add_argument("--use-float16", action=argparse.BooleanOptionalAction, default=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    repo = Path(args.repo).resolve()
    input_video = Path(args.input_video).resolve()
    audio = Path(args.audio).resolve()
    output_video = Path(args.output_video).resolve()
    output_video.parent.mkdir(parents=True, exist_ok=True)

    models_dir = repo / "models"
    unet_model = models_dir / "musetalkV15" / "unet.pth"
    unet_config = models_dir / "musetalkV15" / "musetalk.json"
    whisper_dir = models_dir / "whisper"
    required = [
        repo / "scripts" / "inference.py",
        unet_model,
        unet_config,
        whisper_dir / "config.json",
        models_dir / "sd-vae" / "config.json",
        models_dir / "sd-vae" / "diffusion_pytorch_model.bin",
        models_dir / "dwpose" / "dw-ll_ucoco_384.pth",
        repo / "musetalk" / "utils" / "face_detection" / "detection" / "sfd" / "s3fd.pth",
        models_dir / "face-parse-bisent" / "79999_iter.pth",
        models_dir / "face-parse-bisent" / "resnet18-5c106cde.pth",
    ]
    expected_checkpoint_sizes = {
        unet_model: 3_400_074_924,
        models_dir / "dwpose" / "dw-ll_ucoco_384.pth": 406_878_486,
        repo / "musetalk" / "utils" / "face_detection" / "detection" / "sfd" / "s3fd.pth": 89_843_225,
    }
    missing = [str(path) for path in required if not path.exists()]
    invalid = [
        f"{path} (expected {expected_size} bytes, found {path.stat().st_size})"
        for path, expected_size in expected_checkpoint_sizes.items()
        if path.exists() and path.stat().st_size != expected_size
    ]
    if missing or invalid:
        raise FileNotFoundError(
            "MuseTalk is not fully installed. "
            + ("Missing: " + ", ".join(missing) + ". " if missing else "")
            + ("Invalid checkpoints: " + ", ".join(invalid) + ". " if invalid else "")
            + "Run bootstrap_founder_avatar_models.ps1 -LipSync after installing the MuseTalk env."
        )

    result_dir = output_video.parent / "musetalk-results"
    result_dir.mkdir(parents=True, exist_ok=True)
    config_path = output_video.parent / "musetalk-inference.yaml"
    output_name = output_video.name
    config_path.write_text(
        "\n".join([
            "task_0:",
            f'  video_path: "{input_video.as_posix()}"',
            f'  audio_path: "{audio.as_posix()}"',
            f'  result_name: "{output_name}"',
            "",
        ]),
        encoding="utf-8",
    )

    env = os.environ.copy()
    python_paths = [str(repo)]
    if os.name == "nt":
        python_paths.insert(0, str(Path(__file__).resolve().parent / "musetalk_windows_compat"))
    env["PYTHONPATH"] = os.pathsep.join(python_paths + [env.get("PYTHONPATH", "")])
    env["PYTHONIOENCODING"] = "utf-8"
    env["PYTHONUTF8"] = "1"
    command = [
        sys.executable,
        "-m",
        "scripts.inference",
        "--inference_config",
        str(config_path),
        "--result_dir",
        str(result_dir),
        "--unet_model_path",
        str(unet_model),
        "--unet_config",
        str(unet_config),
        "--whisper_dir",
        str(whisper_dir),
        "--version",
        args.version,
        "--fps",
        str(args.fps),
        "--batch_size",
        str(args.batch_size),
        "--gpu_id",
        str(args.gpu_id),
        "--bbox_shift",
        str(args.bbox_shift),
        "--extra_margin",
        str(args.extra_margin),
        "--parsing_mode",
        str(args.parsing_mode),
        "--left_cheek_width",
        str(args.left_cheek_width),
        "--right_cheek_width",
        str(args.right_cheek_width),
        "--output_vid_name",
        output_name,
    ]
    if args.ffmpeg_path:
        command.extend(["--ffmpeg_path", str(Path(args.ffmpeg_path).resolve())])
    if args.use_float16:
        command.append("--use_float16")

    subprocess.run(command, cwd=repo, env=env, check=True)
    generated = result_dir / args.version / output_name
    if not generated.exists() or generated.stat().st_size == 0:
        raise FileNotFoundError(f"MuseTalk did not produce expected output: {generated}")
    if generated.resolve() != output_video.resolve():
        shutil.copy2(generated, output_video)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
