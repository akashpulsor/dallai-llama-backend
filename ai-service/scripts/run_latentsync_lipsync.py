"""Run local LatentSync lip-sync generation for the avatar runtime."""
from __future__ import annotations

import argparse
import hashlib
import os
import subprocess
import sys
from pathlib import Path


LATENTSYNC_15_CHECKPOINT_SIZE = 5_072_348_184
LATENTSYNC_15_CHECKPOINT_SHA256 = "6440b49a7ccceff56cdc001f5f17605216337f5bbd66fa360139768926e23f51"
WHISPER_TINY_SIZE = 75_572_083
WHISPER_TINY_SHA256 = "65147644a518d12f04e32d6f3b26facc3f8dd46e5390956a9424a650c0ce22b9"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", required=True)
    parser.add_argument("--input-video", required=True)
    parser.add_argument("--audio", required=True)
    parser.add_argument("--output-video", required=True)
    parser.add_argument("--config", default="configs/unet/stage2_efficient.yaml")
    parser.add_argument("--checkpoint", default="checkpoints/latentsync_unet.pt")
    parser.add_argument("--steps", type=int, default=20)
    parser.add_argument("--guidance-scale", type=float, default=1.2)
    parser.add_argument("--enable-deepcache", action="store_true")
    parser.add_argument("--verify-checksum", action="store_true")
    return parser.parse_args()


def require_file(path: Path, label: str, minimum_size: int = 1) -> None:
    if not path.is_file():
        raise RuntimeError(f"{label} is missing: {path}")
    if path.stat().st_size < minimum_size:
        raise RuntimeError(f"{label} is incomplete: {path} ({path.stat().st_size} bytes)")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(8 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    args = parse_args()
    repo = Path(args.repo).resolve()
    input_video = Path(args.input_video).resolve()
    audio = Path(args.audio).resolve()
    output_video = Path(args.output_video).resolve()
    output_video.parent.mkdir(parents=True, exist_ok=True)

    config_path = (repo / args.config).resolve()
    checkpoint_path = (repo / args.checkpoint).resolve()
    whisper_path = repo / "checkpoints" / "whisper" / "tiny.pt"
    require_file(input_video, "Input video")
    require_file(audio, "Input audio")
    require_file(config_path, "LatentSync config")
    require_file(checkpoint_path, "LatentSync 1.5 checkpoint", LATENTSYNC_15_CHECKPOINT_SIZE)
    if checkpoint_path.stat().st_size != LATENTSYNC_15_CHECKPOINT_SIZE:
        raise RuntimeError(
            "LatentSync checkpoint is not the RTX 4060-compatible 1.5 release: "
            f"expected {LATENTSYNC_15_CHECKPOINT_SIZE} bytes, got {checkpoint_path.stat().st_size}."
        )
    require_file(whisper_path, "LatentSync Whisper tiny checkpoint", WHISPER_TINY_SIZE)
    if whisper_path.stat().st_size != WHISPER_TINY_SIZE:
        raise RuntimeError("LatentSync Whisper tiny checkpoint size validation failed.")
    if args.verify_checksum and sha256(checkpoint_path) != LATENTSYNC_15_CHECKPOINT_SHA256:
        raise RuntimeError("LatentSync 1.5 checkpoint checksum validation failed.")
    if args.verify_checksum and sha256(whisper_path) != WHISPER_TINY_SHA256:
        raise RuntimeError("LatentSync Whisper tiny checkpoint checksum validation failed.")

    from omegaconf import OmegaConf
    import torch

    config = OmegaConf.load(config_path)
    resolution = int(config.data.resolution)
    if not torch.cuda.is_available():
        raise RuntimeError("LatentSync requires a CUDA GPU, but CUDA is not available in this environment.")
    gpu_memory_gb = torch.cuda.get_device_properties(0).total_memory / (1024**3)
    if resolution > 256 and gpu_memory_gb < 16:
        raise RuntimeError(
            f"LatentSync {resolution}px requires a larger GPU; detected {gpu_memory_gb:.1f} GB. "
            "Use LatentSync 1.5 with configs/unet/stage2_efficient.yaml on this machine."
        )

    env = os.environ.copy()
    env["PYTHONPATH"] = str(repo) + os.pathsep + env.get("PYTHONPATH", "")
    env["PYTHONIOENCODING"] = "utf-8"
    env["PYTHONUTF8"] = "1"
    env.setdefault(
        "PYTORCH_CUDA_ALLOC_CONF",
        "max_split_size_mb:64,garbage_collection_threshold:0.8",
    )
    env.setdefault("CUDA_MODULE_LOADING", "LAZY")
    env.setdefault("TOKENIZERS_PARALLELISM", "false")
    env.setdefault("NO_ALBUMENTATIONS_UPDATE", "1")
    env.setdefault("HF_HUB_DISABLE_SYMLINKS_WARNING", "1")
    env.setdefault("HF_HUB_OFFLINE", "1")
    env.setdefault("TRANSFORMERS_OFFLINE", "1")
    env.setdefault("DIFFUSERS_OFFLINE", "1")
    local_vae = repo.parent / "MuseTalk" / "models" / "sd-vae"
    if (local_vae / "config.json").is_file() and (
        (local_vae / "diffusion_pytorch_model.bin").is_file()
        or (local_vae / "diffusion_pytorch_model.safetensors").is_file()
    ):
        env["LATENTSYNC_VAE_PATH"] = str(local_vae)
    command = [
        sys.executable,
        "-m",
        "scripts.inference",
        "--unet_config_path",
        str(config_path),
        "--inference_ckpt_path",
        str(checkpoint_path),
        "--inference_steps",
        str(args.steps),
        "--guidance_scale",
        str(args.guidance_scale),
        "--video_path",
        str(input_video),
        "--audio_path",
        str(audio),
        "--video_out_path",
        str(output_video),
        "--temp_dir",
        str(output_video.parent / "latentsync-temp"),
    ]
    if args.enable_deepcache:
        command.append("--enable_deepcache")
    subprocess.run(
        command,
        cwd=repo,
        env=env,
        check=True,
    )
    require_file(output_video, "LatentSync output video")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
