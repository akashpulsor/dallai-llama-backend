"""Run CosyVoice2 zero-shot TTS for the local founder-avatar runtime."""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", required=True)
    parser.add_argument("--model-dir", required=True)
    parser.add_argument("--text-file", required=True)
    parser.add_argument("--prompt-wav", required=True)
    parser.add_argument("--prompt-text", default="")
    parser.add_argument("--output-audio", required=True)
    parser.add_argument("--load-jit", action="store_true")
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    repo = Path(args.repo).resolve()
    sys.path.insert(0, str(repo))
    sys.path.insert(0, str(repo / "third_party" / "Matcha-TTS"))

    import torch
    import torchaudio
    from cosyvoice.cli.cosyvoice import CosyVoice2

    torch.manual_seed(args.seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(args.seed)

    text = Path(args.text_file).read_text(encoding="utf-8").strip()
    prompt_text = args.prompt_text.strip() or "Natural Hinglish founder speech."
    output = Path(args.output_audio)
    output.parent.mkdir(parents=True, exist_ok=True)

    cosyvoice = CosyVoice2(args.model_dir, load_jit=args.load_jit, load_trt=False, load_vllm=False, fp16=False)
    sample_rate = getattr(cosyvoice, "sample_rate", 22050)
    sentence_chunks = [part.strip() for part in re.split(r"(?<=[.!?])\s+", text) if part.strip()]
    speech_chunks = []
    with torch.inference_mode():
        for sentence in sentence_chunks:
            sentence_audio = []
            for item in cosyvoice.inference_zero_shot(sentence, prompt_text, args.prompt_wav, stream=False):
                speech = item.get("tts_speech") if isinstance(item, dict) else None
                if speech is not None:
                    sentence_audio.append(speech.cpu())
            if sentence_audio:
                speech_chunks.append(torch.cat(sentence_audio, dim=-1))
    if speech_chunks:
        pause = torch.zeros((speech_chunks[0].shape[0], int(sample_rate * 0.12)))
        parts = []
        for index, speech in enumerate(speech_chunks):
            if index:
                parts.append(pause)
            parts.append(speech)
        torchaudio.save(str(output), torch.cat(parts, dim=-1), sample_rate)
    if not output.exists() or output.stat().st_size == 0:
        raise RuntimeError("CosyVoice2 produced no output audio.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
