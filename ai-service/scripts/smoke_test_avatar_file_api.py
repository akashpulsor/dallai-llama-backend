"""Smoke test founder-avatar multipart API contracts without provider calls."""
from __future__ import annotations

import base64
import os
import sys
from pathlib import Path
from typing import Any


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    os.chdir(root)
    os.environ["DEBUG"] = "false"
    os.environ["AVATAR_GENERATION_ENABLED"] = "true"
    os.environ["AVATAR_LOCAL_RUNTIME_ENABLED"] = "true"
    os.environ["AVATAR_GENERATION_RUNNER_URL"] = ""
    sys.path.insert(0, str(root))

    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from api.avatar_generation import router
    from services.avatar_local_runtime import local_avatar_runtime

    captured: dict[str, dict[str, Any]] = {}

    async def fake_voice(payload: dict[str, Any]) -> dict[str, Any]:
        captured["voice"] = payload
        return {"status": "COMPLETED", "voiceModel": payload["voiceModel"], "audioContent": base64.b64encode(b"wav").decode("ascii")}

    async def fake_scene(payload: dict[str, Any]) -> dict[str, Any]:
        captured["scene"] = payload
        return {"status": "COMPLETED", "lipSyncModel": payload["lipSyncModel"], "outputVideo": {"data": base64.b64encode(b"mp4").decode("ascii")}}

    async def fake_lip_sync(payload: dict[str, Any]) -> dict[str, Any]:
        captured["lipSync"] = payload
        return {"status": "COMPLETED", "lipSyncModel": payload["lipSyncModel"], "outputVideo": {"data": base64.b64encode(b"mp4").decode("ascii")}}

    local_avatar_runtime.generate_voice = fake_voice
    local_avatar_runtime.generate_scene = fake_scene
    local_avatar_runtime.lip_sync = fake_lip_sync

    app = FastAPI()
    app.include_router(router)
    client = TestClient(app)
    consent = {"consentConfirmed": "true"}

    voice_response = client.post(
        "/creator/avatar/voice/files",
        data={
            **consent,
            "text": "Founder voice clone test",
            "spokenText": "Founder voice klohn test",
            "captionText": "Founder voice clone test",
            "pronunciationGuide": "clone = klohn",
            "referenceTranscript": "This is the exact reference transcript.",
            "preview": "true",
        },
        files={"sample": ("founder.mov", b"founder-video", "video/quicktime")},
    )
    scene_response = client.post(
        "/creator/avatar/scenes/files",
        data={
            **consent,
            "exactDialogue": "Founder scene test",
            "productionEnhancementEnabled": "true",
            "productionEnhancementPrompt": "Navy blazer and clean studio",
        },
        files={
            "sourceVideo": ("founder.mov", b"founder-video", "video/quicktime"),
            "dialogueAudio": ("dialogue.wav", b"dialogue-audio", "audio/wav"),
        },
    )
    lip_sync_response = client.post(
        "/creator/avatar/lip-sync/files",
        data=consent,
        files={
            "video": ("founder.mov", b"founder-video", "video/quicktime"),
            "audio": ("dialogue.wav", b"dialogue-audio", "audio/wav"),
        },
    )

    for response in (voice_response, scene_response, lip_sync_response):
        assert response.status_code == 200, response.text
    assert captured["voice"]["voiceModel"] == "fal_minimax_voice_clone"
    assert captured["voice"]["spokenText"] == "Founder voice klohn test"
    assert captured["voice"]["captionText"] == "Founder voice clone test"
    assert captured["voice"]["pronunciationGuide"] == "clone = klohn"
    assert captured["voice"]["referenceTranscript"] == "This is the exact reference transcript."
    assert captured["voice"]["preview"] is True
    assert captured["scene"]["voiceModel"] == "fal_minimax_voice_clone"
    assert captured["scene"]["lipSyncModel"] == "fal_latentsync"
    assert captured["scene"]["productionEnhancementEnabled"] is True
    assert captured["scene"]["productionEnhancementPrompt"] == "Navy blazer and clean studio"
    assert captured["lipSync"]["lipSyncModel"] == "fal_latentsync"
    assert captured["voice"]["sourceContentType"] == "video/quicktime"
    assert captured["scene"]["audioContentType"] == "audio/wav"
    assert base64.b64decode(captured["lipSync"]["sourceContent"]) == b"founder-video"
    assert base64.b64decode(captured["lipSync"]["audioContent"]) == b"dialogue-audio"
    print("avatar-file-api-smoke-ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
