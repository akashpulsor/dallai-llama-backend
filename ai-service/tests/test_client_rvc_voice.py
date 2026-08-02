from __future__ import annotations

import tempfile
import unittest
import os
from pathlib import Path
from unittest.mock import patch

os.environ["DEBUG"] = "false"

from services.avatar_local_runtime import LocalAvatarRuntime, RuntimeFile


class ClientRvcVoiceTest(unittest.TestCase):
    def test_client_profile_returns_stable_reusable_voice_id(self) -> None:
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as temporary:
            root = Path(temporary)
            runtime = LocalAvatarRuntime()
            runtime.work_root = root / "work"
            runtime.work_root.mkdir()
            runtime.rvc_profile_root = root / "profiles"
            profile_root = runtime.rvc_profile_root / "founder_female_v1"
            profile_root.mkdir(parents=True)
            (profile_root / "voice.pth").write_bytes(b"model")
            (profile_root / "voice.index").write_bytes(b"index")
            source = root / "source.wav"
            source.write_bytes(b"source speech")

            def prepare_source(_source: Path, output: Path) -> dict:
                output.write_bytes(b"prepared source")
                return {
                    "selectionMode": "desired_speech_source",
                    "durationSeconds": 3.0,
                    "meetsRecommendedMinimum": True,
                }

            def apply_adapter(_payload: dict, _variables: dict, _source: Path, output: Path) -> None:
                output.write_bytes(b"RIFF" + (b"\0" * 2048))

            payload = {
                "requestId": "voice-rvc-test",
                "text": "This is the prepared English dialogue.",
                "spokenText": "This is the prepared English dialogue.",
                "voiceModel": "client_rvc_english",
                "voiceProfileId": "founder_female_v1",
                "sourcePurpose": "desired_speech",
                "language": "English",
                "languageCode": "en-IN",
                "consentConfirmed": True,
            }
            with (
                patch.object(
                    runtime,
                    "_resolve_source_media",
                    return_value=RuntimeFile(source, "audio/wav"),
                ),
                patch.object(runtime, "_prepare_rvc_source_audio", side_effect=prepare_source),
                patch.object(runtime, "_run_client_rvc_voice", side_effect=apply_adapter),
                patch.object(
                    runtime,
                    "_master_voice_output",
                    return_value={
                        "status": "completed",
                        "applied": True,
                        "profile": "studio_voice_v1",
                    },
                ),
            ):
                result = runtime._generate_voice_sync(payload)

            self.assertEqual("COMPLETED", result["status"])
            self.assertEqual("client_rvc_english", result["voiceModel"])
            self.assertEqual("founder_female_v1", result["voiceProfileId"])
            self.assertEqual("rvc:founder_female_v1", result["providerVoiceId"])
            self.assertTrue(result["adapterApplied"])
            self.assertTrue(result["voiceEnhancementApplied"])
            self.assertEqual("studio_voice_v1", result["voiceEnhancementProfile"])
            self.assertTrue(result["audioContent"])

    def test_client_profile_rejects_non_english_dialogue(self) -> None:
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as temporary:
            runtime = LocalAvatarRuntime()
            runtime.rvc_profile_root = Path(temporary)
            source = Path(temporary) / "source.wav"
            source.write_bytes(b"source speech")
            with self.assertRaisesRegex(RuntimeError, "English dialogue only"):
                runtime._run_client_rvc_voice(
                    {
                        "voiceProfileId": "founder_female_v1",
                        "sourcePurpose": "desired_speech",
                        "language": "Hinglish",
                        "languageCode": "hi-IN",
                    },
                    {},
                    source,
                    Path(temporary) / "output.wav",
                )


if __name__ == "__main__":
    unittest.main()
