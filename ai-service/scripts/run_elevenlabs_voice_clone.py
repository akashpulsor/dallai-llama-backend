"""Clone a founder voice and synthesize dialogue with ElevenLabs."""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
import uuid
from pathlib import Path

import httpx


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--text-file", required=True)
    parser.add_argument("--prompt-wav", required=True)
    parser.add_argument("--output-audio", required=True)
    parser.add_argument("--voice-id", default="")
    parser.add_argument("--voice-name", default="")
    parser.add_argument("--base-url", default=os.environ.get("AVATAR_ELEVENLABS_BASE_URL", "https://api.elevenlabs.io"))
    parser.add_argument("--api-key-env", default="AVATAR_ELEVENLABS_API_KEY")
    parser.add_argument("--model-id", default=os.environ.get("AVATAR_ELEVENLABS_MODEL_ID", "eleven_multilingual_v2"))
    parser.add_argument("--output-format", default=os.environ.get("AVATAR_ELEVENLABS_OUTPUT_FORMAT", "mp3_44100_128"))
    parser.add_argument("--stability", type=float, default=float(os.environ.get("AVATAR_ELEVENLABS_STABILITY", "0.45")))
    parser.add_argument("--similarity-boost", type=float, default=float(os.environ.get("AVATAR_ELEVENLABS_SIMILARITY_BOOST", "0.85")))
    parser.add_argument("--remove-background-noise", action=argparse.BooleanOptionalAction, default=True)
    parser.add_argument("--timeout", type=float, default=float(os.environ.get("AVATAR_PROPRIETARY_API_TIMEOUT_SECONDS", "900")))
    return parser.parse_args()


def require_file(path: Path, label: str) -> None:
    if not path.exists() or path.stat().st_size == 0:
        raise FileNotFoundError(f"{label} is required: {path}")


def clone_voice(client: httpx.Client, base_url: str, api_key: str, prompt_wav: Path, voice_name: str, remove_noise: bool) -> str:
    headers = {"xi-api-key": api_key}
    with prompt_wav.open("rb") as sample:
        files = [("files[]", (prompt_wav.name, sample, "audio/wav"))]
        data = {
            "name": voice_name or f"founder-{uuid.uuid4().hex[:10]}",
            "remove_background_noise": str(remove_noise).lower(),
            "description": "Founder-led video reference voice generated from an approved source clip.",
        }
        response = client.post(f"{base_url.rstrip('/')}/v1/voices/add", headers=headers, data=data, files=files)
        response.raise_for_status()
    voice_id = response.json().get("voice_id", "")
    if not voice_id:
        raise RuntimeError("ElevenLabs clone response did not include voice_id.")
    return voice_id


def synthesize(client: httpx.Client, base_url: str, api_key: str, voice_id: str, text: str, args: argparse.Namespace, temp_audio: Path) -> None:
    headers = {"xi-api-key": api_key, "Content-Type": "application/json"}
    payload = {
        "text": text,
        "model_id": args.model_id,
        "voice_settings": {
            "stability": args.stability,
            "similarity_boost": args.similarity_boost,
        },
    }
    url = f"{base_url.rstrip('/')}/v1/text-to-speech/{voice_id}?output_format={args.output_format}"
    response = client.post(url, headers=headers, json=payload)
    response.raise_for_status()
    temp_audio.write_bytes(response.content)


def convert_to_wav(input_audio: Path, output_audio: Path) -> None:
    command = [
        "ffmpeg",
        "-y",
        "-i",
        str(input_audio),
        "-ar",
        "44100",
        "-ac",
        "1",
        str(output_audio),
    ]
    subprocess.run(command, check=True, capture_output=True, text=True)


def main() -> int:
    args = parse_args()
    api_key = os.environ.get(args.api_key_env, "").strip()
    if not api_key:
        raise RuntimeError(f"{args.api_key_env} is required for ElevenLabs voice clone.")

    text_file = Path(args.text_file).resolve()
    prompt_wav = Path(args.prompt_wav).resolve()
    output_audio = Path(args.output_audio).resolve()
    output_audio.parent.mkdir(parents=True, exist_ok=True)
    require_file(text_file, "Text file")
    require_file(prompt_wav, "Prompt wav")
    text = text_file.read_text(encoding="utf-8").strip()
    if not text:
        raise RuntimeError("Text file is empty.")

    temp_audio = output_audio.with_suffix(".elevenlabs.mp3")
    with httpx.Client(timeout=args.timeout) as client:
        voice_id = args.voice_id.strip()
        if not voice_id:
            voice_id = clone_voice(client, args.base_url, api_key, prompt_wav, args.voice_name, args.remove_background_noise)
        synthesize(client, args.base_url, api_key, voice_id, text, args, temp_audio)

    convert_to_wav(temp_audio, output_audio)
    if not output_audio.exists() or output_audio.stat().st_size == 0:
        raise RuntimeError("ElevenLabs did not produce a usable audio file.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
