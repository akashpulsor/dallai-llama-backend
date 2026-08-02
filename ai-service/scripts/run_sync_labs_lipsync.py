"""Run Sync Labs proprietary lip-sync generation for avatar scenes."""
from __future__ import annotations

import argparse
import os
import time
from pathlib import Path

import httpx


TERMINAL_SUCCESS = {"COMPLETED", "SUCCEEDED", "SUCCESS"}
TERMINAL_FAILURE = {"FAILED", "REJECTED", "CANCELED", "CANCELLED", "ERROR"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input-video", required=True)
    parser.add_argument("--audio", required=True)
    parser.add_argument("--output-video", required=True)
    parser.add_argument("--model", default=os.environ.get("AVATAR_SYNC_LABS_MODEL", "lipsync-2-pro"))
    parser.add_argument("--base-url", default=os.environ.get("AVATAR_SYNC_LABS_BASE_URL", "https://api.sync.so"))
    parser.add_argument("--api-key-env", default="AVATAR_SYNC_LABS_API_KEY")
    parser.add_argument("--timeout", type=float, default=float(os.environ.get("AVATAR_PROPRIETARY_API_TIMEOUT_SECONDS", "900")))
    parser.add_argument("--poll-interval", type=float, default=float(os.environ.get("AVATAR_SYNC_LABS_POLL_INTERVAL_SECONDS", "2")))
    parser.add_argument("--max-direct-upload-mb", type=float, default=float(os.environ.get("AVATAR_SYNC_LABS_MAX_DIRECT_UPLOAD_MB", "20")))
    return parser.parse_args()


def require_file(path: Path, label: str) -> None:
    if not path.exists() or path.stat().st_size == 0:
        raise FileNotFoundError(f"{label} is required: {path}")


def assert_direct_upload_size(path: Path, limit_mb: float, label: str) -> None:
    size_mb = path.stat().st_size / (1024 * 1024)
    if size_mb > limit_mb:
        raise RuntimeError(
            f"{label} is {size_mb:.1f} MB; Sync Labs direct upload limit is {limit_mb:.1f} MB. "
            "Use URL/asset based fallback for larger clips."
        )


def main() -> int:
    args = parse_args()
    api_key = os.environ.get(args.api_key_env, "").strip()
    if not api_key:
        raise RuntimeError(f"{args.api_key_env} is required for Sync Labs lip sync.")

    input_video = Path(args.input_video).resolve()
    audio = Path(args.audio).resolve()
    output_video = Path(args.output_video).resolve()
    output_video.parent.mkdir(parents=True, exist_ok=True)
    require_file(input_video, "Input video")
    require_file(audio, "Audio")
    assert_direct_upload_size(input_video, args.max_direct_upload_mb, "Input video")
    assert_direct_upload_size(audio, args.max_direct_upload_mb, "Audio")

    headers = {"x-api-key": api_key}
    base_url = args.base_url.rstrip("/")
    deadline = time.monotonic() + args.timeout
    with httpx.Client(timeout=min(args.timeout, 120.0)) as client:
        with input_video.open("rb") as video_file, audio.open("rb") as audio_file:
            files = {
                "video": (input_video.name, video_file, "video/mp4"),
                "audio": (audio.name, audio_file, "audio/wav"),
            }
            data = {"model": args.model}
            response = client.post(f"{base_url}/v2/generate", headers=headers, data=data, files=files)
            response.raise_for_status()
        generation = response.json()
        generation_id = generation.get("id")
        if not generation_id:
            raise RuntimeError(f"Sync Labs did not return a generation id: {generation}")

        latest = generation
        while time.monotonic() < deadline:
            status = str(latest.get("status", "")).upper()
            if status in TERMINAL_SUCCESS:
                output_url = latest.get("outputUrl") or latest.get("output_url")
                if not output_url:
                    raise RuntimeError(f"Sync Labs completed without outputUrl: {latest}")
                media = client.get(output_url)
                media.raise_for_status()
                output_video.write_bytes(media.content)
                if output_video.stat().st_size == 0:
                    raise RuntimeError("Sync Labs output download was empty.")
                return 0
            if status in TERMINAL_FAILURE:
                raise RuntimeError(f"Sync Labs generation failed: {latest.get('error') or latest.get('errorCode') or latest}")
            wait_timeout = min(10.0, max(1.0, min(args.poll_interval, deadline - time.monotonic())))
            response = client.get(f"{base_url}/v2/generate/{generation_id}", headers=headers, params={"wait": "true", "timeout": wait_timeout})
            response.raise_for_status()
            latest = response.json()
            if str(latest.get("status", "")).upper() not in TERMINAL_SUCCESS | TERMINAL_FAILURE:
                time.sleep(max(0.5, args.poll_interval))

    raise TimeoutError(f"Sync Labs generation did not complete within {args.timeout:.0f}s.")


if __name__ == "__main__":
    raise SystemExit(main())
