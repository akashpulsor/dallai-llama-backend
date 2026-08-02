"""Smoke test ai-service voice generation through the CosyVoice2 command runner."""
from __future__ import annotations

import base64
import os
import sys
from pathlib import Path


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    workspace = Path(r"C:\Users\Akash\workspace\v4\dalai-llama")
    model_root = workspace / ".avatar-models"
    os.chdir(root)
    os.environ["DEBUG"] = "false"
    os.environ["AVATAR_GENERATION_ENABLED"] = "true"
    os.environ["AVATAR_LOCAL_RUNTIME_ENABLED"] = "true"
    os.environ["AVATAR_ALLOW_TEST_FALLBACK"] = "false"
    os.environ["AVATAR_WORK_ROOT"] = str(workspace / ".avatar-smoke" / "work")
    os.environ["AVATAR_MODEL_ROOT"] = str(model_root)
    os.environ["AVATAR_COSYVOICE_REPO"] = str(model_root / "CosyVoice")
    os.environ["AVATAR_COSYVOICE2_MODEL_DIR"] = str(model_root / "CosyVoice2-0.5B")
    os.environ["AVATAR_COSYVOICE2_COMMAND"] = (
        f'"{workspace / ".avatar-envs" / "cosyvoice" / "Scripts" / "python.exe"}" '
        f'"{root / "scripts" / "run_cosyvoice2_zero_shot.py"}" '
        f'--repo "{model_root / "CosyVoice"}" '
        '--model-dir "{cosyvoice_model_dir}" '
        '--text-file "{text_file}" '
        '--prompt-wav "{prompt_wav}" '
        '--prompt-text "{prompt_text}" '
        '--output-audio "{output_audio}"'
    )
    sys.path.insert(0, str(root))

    from services.avatar_local_runtime import local_avatar_runtime

    result = local_avatar_runtime._generate_voice_sync(
        {
            "text": "Namaste, this is a founder voice path test.",
            "sourceUrl": str(workspace / ".avatar-smoke" / "stt-tone.wav"),
            "promptText": "hello founder reference",
            "languageCode": "hi-IN",
        }
    )
    print(result["status"], result["model"], len(base64.b64decode(result["audioContent"])), result["workDir"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
