"""Smoke test fal.ai founder voice and lip-sync orchestration without API spend."""
from __future__ import annotations

import base64
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any


class FakeFalClient:
    def __init__(self, voice_output: Path, video_output: Path) -> None:
        self.voice_output = voice_output
        self.video_output = video_output
        self.calls: list[dict[str, Any]] = []
        self.uploads: list[Path] = []

    class StorageSettings:
        def __init__(self, *, expires_in: int) -> None:
            self.expires_in = expires_in

    def upload_file(self, path: str, **kwargs: Any) -> str:
        resolved = Path(path).resolve()
        if not resolved.exists() or resolved.stat().st_size == 0:
            raise AssertionError(f"fal upload input is missing: {resolved}")
        if kwargs.get("lifecycle").expires_in != 3600:
            raise AssertionError("fal upload lifecycle is not configured")
        self.uploads.append(resolved)
        return resolved.as_uri()

    def subscribe(self, endpoint: str, *, arguments: dict[str, Any], with_logs: bool, **kwargs: Any) -> dict[str, Any]:
        if kwargs.get("headers") != {"X-Fal-Store-IO": "0"}:
            raise AssertionError("fal privacy header is not configured")
        self.calls.append({"endpoint": endpoint, "arguments": arguments, "withLogs": with_logs})
        if endpoint.endswith("minimax/voice-clone"):
            return {
                "custom_voice_id": "minimax-demo-voice",
                "audio": {"url": str(self.voice_output)},
            }
        if endpoint.endswith("minimax/speech-02-hd"):
            return {"audio": {"url": str(self.voice_output)}}
        if endpoint.endswith("latentsync"):
            return {"video": {"url": str(self.video_output)}}
        raise AssertionError(f"Unexpected fal endpoint: {endpoint}")


def run_ffmpeg(command: list[str]) -> None:
    subprocess.run(["ffmpeg", "-y", *command], check=True, capture_output=True, text=True)


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    os.chdir(root)
    smoke_root = Path(os.environ.get("AVATAR_SMOKE_ROOT", tempfile.gettempdir())).resolve()
    smoke_root.mkdir(parents=True, exist_ok=True)
    os.environ["DEBUG"] = "false"
    os.environ["FAL_KEY"] = "test-only-fal-key"
    os.environ["AVATAR_GENERATION_ENABLED"] = "true"
    os.environ["AVATAR_LOCAL_RUNTIME_ENABLED"] = "true"
    os.environ["AVATAR_VOICE_MODEL"] = "fal_minimax_voice_clone"
    os.environ["AVATAR_TALKING_MODEL"] = "source_video"
    os.environ["AVATAR_LIPSYNC_MODEL"] = "fal_latentsync"
    os.environ["AVATAR_WORK_ROOT"] = str(smoke_root / "fal-work")
    sys.path.insert(0, str(root))

    from services.avatar_local_runtime import LocalAvatarRuntime

    with tempfile.TemporaryDirectory(prefix="fal-avatar-smoke-", dir=smoke_root, ignore_cleanup_errors=True) as temp_dir:
        temp = Path(temp_dir)
        source_video = temp / "founder.mp4"
        fake_voice = temp / "fal-voice.wav"
        fake_video = temp / "fal-lipsync.mp4"
        run_ffmpeg([
            "-f", "lavfi", "-i", "testsrc2=size=360x640:rate=25",
            "-f", "lavfi", "-i", "sine=frequency=310:sample_rate=24000",
            "-t", "12", "-pix_fmt", "yuv420p", "-c:a", "aac", str(source_video),
        ])
        run_ffmpeg(["-f", "lavfi", "-i", "sine=frequency=240:sample_rate=24000", "-t", "2", str(fake_voice)])
        shutil.copy2(source_video, fake_video)

        runtime = LocalAvatarRuntime()
        fake_client = FakeFalClient(fake_voice, fake_video)
        runtime._fal_client = lambda: fake_client

        def copy_result(result: Any, keys: tuple[str, ...], output: Path, *, stage: str) -> None:
            value = result
            for key in keys:
                value = value[key]
            source_url = value["url"] if isinstance(value, dict) else value
            shutil.copy2(Path(source_url), output)

        runtime._download_fal_result = copy_result
        source_content = base64.b64encode(source_video.read_bytes()).decode("ascii")
        caption_script = "Procrastination willpower ki kami nahi, nervous system ka protection response ho sakta hai."
        spoken_script = "Pro-kras-ti-nay-shun willpower ki kami nahi, nervous system ka protection response ho sakta hai."
        voice = runtime._generate_voice_sync({
            "text": caption_script,
            "captionText": caption_script,
            "spokenText": spoken_script,
            "pronunciationGuide": "nervous system = nervous sis-tem",
            "sourceContent": source_content,
            "sourceContentType": "video/mp4",
            "voiceModel": "fal_minimax_voice_clone",
            "preview": True,
            "consentConfirmed": True,
        })
        reused_voice = runtime._generate_voice_sync({
            "text": "La procrastinacion no es falta de fuerza de voluntad.",
            "captionText": "La procrastinacion no es falta de fuerza de voluntad.",
            "spokenText": "La procrastinacion no es falta de fuerza de voluntad.",
            "language": "Spanish",
            "languageCode": "es-ES",
            "voiceModel": "fal_minimax_voice_clone",
            "founderAvatarProfile": {"minimaxVoiceId": voice["minimaxVoiceId"]},
            "consentConfirmed": True,
        })
        scene = runtime._generate_scene_sync({
            "sceneId": "fal-smoke-scene",
            "sceneNumber": 1,
            "durationSeconds": 8,
            "sourceContent": source_content,
            "sourceContentType": "video/mp4",
            "audioContent": reused_voice["audioContent"],
            "audioContentType": "audio/wav",
            "exactDialogue": caption_script,
            "voiceModel": "fal_minimax_voice_clone",
            "talkingAvatarModel": "source_video",
            "lipSyncModel": "fal_latentsync",
            "productionEnhancementEnabled": False,
            "consentConfirmed": True,
        })
        segmented_audio = temp / "founder-final-scene.wav"
        runtime._convert_audio_segment_to_wav(fake_voice, segmented_audio, 0.5, 1.0)
        segmented_duration = runtime._media_duration_seconds(segmented_audio)

    assert voice["voiceModel"] == "fal_minimax_voice_clone"
    assert voice["minimaxVoiceId"] == "minimax-demo-voice"
    assert voice["captionText"] == caption_script
    assert voice["spokenText"] == spoken_script.replace("nervous system", "nervous sis-tem")
    assert voice["costMetadata"]["usage"]["voiceCloneCreated"] is True
    assert voice["costMetadata"]["provider"] == "fal.ai"
    assert reused_voice["minimaxVoiceId"] == "minimax-demo-voice"
    assert reused_voice["languageBoost"] == "Spanish"
    assert reused_voice["costMetadata"]["usage"]["voiceCloneCreated"] is False
    assert scene["lipSyncModel"] == "fal_latentsync"
    assert scene["avatarModel"] == "source_video"
    assert scene["stages"]["avatar"] == "source_video"
    assert len(fake_client.calls) == 3
    assert [call["endpoint"] for call in fake_client.calls] == [
        "fal-ai/minimax/voice-clone",
        "fal-ai/minimax/speech-02-hd",
        "fal-ai/latentsync",
    ]
    assert fake_client.calls[-1]["endpoint"].endswith("latentsync")
    assert fake_client.uploads[-2].name == "founder-scene-source.mp4"
    clone_arguments = fake_client.calls[0]["arguments"]
    assert clone_arguments["model"] == "speech-02-hd"
    assert clone_arguments["noise_reduction"] is True
    tts_arguments = fake_client.calls[1]["arguments"]
    assert tts_arguments["voice_setting"]["voice_id"] == "minimax-demo-voice"
    assert tts_arguments["language_boost"] == "Spanish"
    assert tts_arguments["output_format"] == "url"
    assert 0.8 <= segmented_duration <= 1.2
    print(json.dumps({
        "ok": True,
        "voiceModel": voice["voiceModel"],
        "lipSyncModel": scene["lipSyncModel"],
        "falCalls": [call["endpoint"] for call in fake_client.calls],
        "voiceBytes": len(base64.b64decode(voice["audioContent"])),
        "videoBytes": len(base64.b64decode(scene["outputVideo"]["data"])),
        "providerVoiceId": voice["minimaxVoiceId"],
        "avatarStage": scene["stages"]["avatar"],
        "uploadedAudioSegmentSeconds": round(segmented_duration, 3),
        "estimatedCostUsd": round(float(voice["cost"]) + float(reused_voice["cost"]) + float(scene["cost"]), 6),
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
