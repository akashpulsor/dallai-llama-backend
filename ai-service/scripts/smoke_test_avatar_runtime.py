"""Smoke test the local founder-avatar runtime without calling creator-service.

This uses AVATAR_ALLOW_TEST_FALLBACK=true so ffmpeg can exercise the wiring even
before CosyVoice/LivePortrait/LatentSync weights are configured.
"""
from __future__ import annotations

import base64
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path


def main() -> int:
    launch_cwd = Path.cwd()
    root = Path(__file__).resolve().parents[1]
    os.chdir(root)
    smoke_root = Path(os.environ.get("AVATAR_SMOKE_ROOT", str(launch_cwd / ".avatar-smoke"))).resolve()
    smoke_root.mkdir(parents=True, exist_ok=True)
    os.environ["DEBUG"] = "false"
    os.environ["AVATAR_GENERATION_ENABLED"] = "true"
    os.environ["AVATAR_LOCAL_RUNTIME_ENABLED"] = "true"
    os.environ["AVATAR_ALLOW_TEST_FALLBACK"] = "true"
    os.environ["AVATAR_WORK_ROOT"] = str(smoke_root / "work")
    os.environ["AVATAR_MODEL_ROOT"] = str(root / "models" / "avatar")

    sys.path.insert(0, str(root))
    from services.avatar_local_runtime import LocalAvatarRuntimeError, local_avatar_runtime

    with tempfile.TemporaryDirectory(prefix="avatar-smoke-", dir=smoke_root, ignore_cleanup_errors=True) as temp_dir:
        temp = Path(temp_dir)
        source = temp / "founder-source.mp4"
        command = [
            "ffmpeg",
            "-y",
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=360x640:rate=25",
            "-f",
            "lavfi",
            "-i",
            "sine=frequency=440:sample_rate=24000",
            "-t",
            "2.5",
            "-pix_fmt",
            "yuv420p",
            str(source),
        ]
        subprocess.run(command, check=True, capture_output=True, text=True)
        source_b64 = base64.b64encode(source.read_bytes()).decode("ascii")

    try:
        voice = local_avatar_runtime._generate_voice_sync(
            {
                "text": "Namaste, main founder hoon. Yeh product aapka kaam fast aur simple banata hai.",
                "sourceContent": source_b64,
                "sourceContentType": "video/mp4",
                "language": "Hinglish",
                "languageCode": "hi-IN",
                "voiceModel": "cosy_voice2",
            }
        )
        scene = local_avatar_runtime._generate_scene_sync(
            {
                "sceneId": "smoke-scene-1",
                "sceneNumber": 1,
                "durationSeconds": 3,
                "sourceContent": source_b64,
                "sourceContentType": "video/mp4",
                "audioContent": voice["audioContent"],
                "exactDialogue": "Namaste, main founder hoon. Yeh product aapka kaam fast aur simple banata hai.",
                "language": "Hinglish",
                "languageCode": "hi-IN",
                "lipSyncModel": "musetalk",
            }
        )
    except LocalAvatarRuntimeError as exc:
        print(json.dumps({"ok": False, "stage": exc.stage, "message": str(exc), "setupHint": exc.setup_hint}, indent=2))
        return 1

    result = {
        "ok": True,
        "voiceBytes": len(base64.b64decode(voice["audioContent"])),
        "videoBytes": len(base64.b64decode(scene["outputVideo"]["data"])),
        "voiceWorkDir": voice["workDir"],
        "sceneWorkDir": scene["workDir"],
    }
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
