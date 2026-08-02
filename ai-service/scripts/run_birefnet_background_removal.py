"""Run local BiRefNet background removal for the avatar runtime."""
from __future__ import annotations

import argparse
from pathlib import Path

import torch
from PIL import Image
from torchvision import transforms
from transformers import AutoModelForImageSegmentation


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", required=True)
    parser.add_argument("--input-image", required=True)
    parser.add_argument("--output-image", required=True)
    parser.add_argument("--device", default="auto")
    parser.add_argument("--resolution", type=int, default=1024)
    return parser.parse_args()


def choose_device(device: str) -> str:
    if device and device != "auto":
        return device
    return "cuda" if torch.cuda.is_available() else "cpu"


def main() -> int:
    args = parse_args()
    model_dir = Path(args.model_dir).resolve()
    input_image = Path(args.input_image).resolve()
    output_image = Path(args.output_image).resolve()
    output_image.parent.mkdir(parents=True, exist_ok=True)

    device = choose_device(args.device)
    image = Image.open(input_image).convert("RGB")
    original_size = image.size

    transform = transforms.Compose(
        [
            transforms.Resize((args.resolution, args.resolution)),
            transforms.ToTensor(),
            transforms.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225]),
        ]
    )
    tensor = transform(image).unsqueeze(0).to(device)

    model = AutoModelForImageSegmentation.from_pretrained(
        str(model_dir),
        trust_remote_code=True,
        local_files_only=True,
    )
    model.to(device)
    model.eval()
    use_half = device == "cuda"
    if use_half:
        model.half()
        tensor = tensor.half()

    with torch.no_grad():
        prediction = model(tensor)[-1].sigmoid().cpu()[0].squeeze()

    mask = transforms.ToPILImage()(prediction).resize(original_size, Image.Resampling.LANCZOS)
    output = image.copy()
    output.putalpha(mask)
    output.save(output_image)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
