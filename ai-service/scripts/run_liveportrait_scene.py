"""Run local LivePortrait face-motion generation for the avatar runtime."""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", required=True)
    parser.add_argument("--source-video", required=True)
    parser.add_argument("--audio", required=True)
    parser.add_argument("--output-video", required=True)
    parser.add_argument("--duration", default="8")
    parser.add_argument("--driving-video", default="")
    return parser.parse_args()


def run(command: list[str], *, cwd: Path | None = None, env: dict[str, str] | None = None) -> None:
    subprocess.run(command, cwd=cwd, env=env, check=True)


def prepare_source(source_media: Path, source_frame: Path) -> None:
    if source_media.suffix.lower() in IMAGE_EXTENSIONS:
        shutil.copyfile(source_media, source_frame)
        return
    run(
        [
            "ffmpeg",
            "-y",
            "-ss",
            "0.5",
            "-i",
            str(source_media),
            "-frames:v",
            "1",
            str(source_frame),
        ]
    )


def prepare_driving(source_media: Path, driving_media: Path | None, output: Path, duration: float) -> None:
    source = driving_media if driving_media and driving_media.exists() else source_media
    if source.suffix.lower() in IMAGE_EXTENSIONS:
        run(
            [
                "ffmpeg",
                "-y",
                "-loop",
                "1",
                "-i",
                str(source),
                "-t",
                f"{duration:.2f}",
                "-vf",
                "scale=720:1280:force_original_aspect_ratio=decrease,pad=720:1280:(ow-iw)/2:(oh-ih)/2",
                "-pix_fmt",
                "yuv420p",
                str(output),
            ]
        )
        return
    run(
        [
            "ffmpeg",
            "-y",
            "-stream_loop",
            "-1",
            "-i",
            str(source),
            "-t",
            f"{duration:.2f}",
            "-an",
            "-pix_fmt",
            "yuv420p",
            str(output),
        ]
    )


def main() -> int:
    args = parse_args()
    repo = Path(args.repo).resolve()
    source_media = Path(args.source_video).resolve()
    driving_media = Path(args.driving_video).resolve() if args.driving_video else None
    audio = Path(args.audio).resolve()
    output_video = Path(args.output_video).resolve()
    output_video.parent.mkdir(parents=True, exist_ok=True)
    duration = max(1.0, min(float(args.duration or 8), 20.0))

    with tempfile.TemporaryDirectory(prefix="liveportrait-") as temp_dir:
        temp = Path(temp_dir)
        source_frame = temp / "source.png"
        driving_clip = temp / "driving.mp4"
        result_dir = temp / "animations"
        visual = temp / "visual.mp4"
        prepare_source(source_media, source_frame)
        prepare_driving(source_media, driving_media, driving_clip, duration)

        env = os.environ.copy()
        env["PYTHONPATH"] = str(repo) + os.pathsep + env.get("PYTHONPATH", "")
        env["PYTHONIOENCODING"] = "utf-8"
        env["PYTHONUTF8"] = "1"
        run(
            [
                sys.executable,
                str(repo / "inference.py"),
                "-s",
                str(source_frame),
                "-d",
                str(driving_clip),
                "-o",
                str(result_dir),
            ],
            cwd=repo,
            env=env,
        )

        candidates = sorted(result_dir.rglob("*.mp4"), key=lambda path: path.stat().st_mtime, reverse=True)
        if not candidates:
            raise FileNotFoundError(f"LivePortrait did not create an mp4 in {result_dir}")
        shutil.copyfile(candidates[0], visual)

        run(
            [
                "ffmpeg",
                "-y",
                "-i",
                str(visual),
                "-i",
                str(audio),
                "-t",
                f"{duration:.2f}",
                "-map",
                "0:v:0",
                "-map",
                "1:a:0",
                "-c:v",
                "libx264",
                "-pix_fmt",
                "yuv420p",
                "-c:a",
                "aac",
                "-shortest",
                str(output_video),
            ]
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
