import base64
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import AsyncMock, MagicMock, Mock, patch

from fastapi import FastAPI
from fastapi.testclient import TestClient

os.environ["DEBUG"] = "false"

from api.avatar_generation import local_avatar_runtime, router, settings
from services.avatar_local_runtime import LocalAvatarRuntime, LocalAvatarRuntimeError


class AvatarVoiceFileApiTest(unittest.TestCase):
    def test_scene_file_endpoint_uses_disk_paths_and_streams_video_response(self):
        app = FastAPI()
        app.include_router(router)
        source_bytes = b"source-video-" * (512 * 1024)
        audio_bytes = b"audio-preview"

        with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as directory:
            root = Path(directory)

            async def generate_scene(payload):
                self.assertFalse(payload.get("sourceContent"))
                self.assertFalse(payload.get("audioContent"))
                self.assertEqual(source_bytes, Path(payload["_sourceFilePath"]).read_bytes())
                self.assertEqual(audio_bytes, Path(payload["_audioFilePath"]).read_bytes())
                self.assertTrue(payload["_returnFilePath"])
                result_dir = root / "scene-result"
                result_dir.mkdir()
                output = result_dir / "preview.mp4"
                output.write_bytes(b"lip-synced-video")
                return {
                    "status": "COMPLETED",
                    "provider": "fal.ai",
                    "requestId": payload["runId"],
                    "lipSyncModel": "fal_latentsync",
                    "lipSyncStatus": "completed",
                    "falRequestId": "fal-scene-123",
                    "costMetadata": {
                        "modelApiInteracted": True,
                        "actualTotalCost": 0.0,
                        "totalCost": 0.0,
                    },
                    "outputFilePath": str(output),
                    "workDir": str(result_dir),
                }

            with (
                patch.object(settings, "avatar_generation_enabled", True),
                patch.object(settings, "avatar_local_runtime_enabled", True),
                patch.object(settings, "avatar_work_root", str(root)),
                patch.object(
                    local_avatar_runtime,
                    "generate_scene",
                    AsyncMock(side_effect=generate_scene),
                ) as runtime,
            ):
                with TestClient(app) as client:
                    response = client.post(
                        "/creator/avatar/scenes/files",
                        files={
                            "sourceVideo": ("founder.mov", source_bytes, "video/quicktime"),
                            "dialogueAudio": ("dialogue.wav", audio_bytes, "audio/wav"),
                        },
                        data={
                            "runId": "avatar-stream-test",
                            "lipSyncModel": "fal_latentsync",
                            "talkingAvatarModel": "source_video",
                            "consentConfirmed": "true",
                        },
                    )

        self.assertEqual(200, response.status_code)
        self.assertEqual("video/mp4", response.headers["content-type"])
        self.assertEqual(b"lip-synced-video", response.content)
        metadata = json.loads(
            base64.urlsafe_b64decode(response.headers["x-dalai-avatar-metadata"])
        )
        self.assertEqual("fal-scene-123", metadata["falRequestId"])
        runtime.assert_awaited_once()

    def test_voice_file_endpoint_passes_uploaded_media_inline(self):
        app = FastAPI()
        app.include_router(router)
        runtime_result = {
            "audioContent": base64.b64encode(b"voice-preview").decode("ascii"),
            "contentType": "audio/wav",
            "providerVoiceId": "clone-1",
        }

        with (
            patch.object(settings, "avatar_generation_enabled", True),
            patch.object(settings, "avatar_local_runtime_enabled", True),
            patch.object(local_avatar_runtime, "generate_voice", AsyncMock(return_value=runtime_result)) as generate_voice,
        ):
            with TestClient(app) as client:
                response = client.post(
                    "/creator/avatar/voice/files",
                    files={"sample": ("founder.mov", b"uploaded-video", "video/quicktime")},
                    data={
                        "requestId": "voice-test-123",
                        "text": "Hello founder",
                        "voiceModel": "fal_minimax_voice_clone",
                        "referenceLanguage": "English",
                        "consentConfirmed": "true",
                        "founderAvatarProfileJson": '{"consentConfirmed": true}',
                    },
                )

        self.assertEqual(200, response.status_code)
        payload = generate_voice.await_args.args[0]
        self.assertEqual("voice-test-123", payload["requestId"])
        self.assertEqual("voice-test-123", response.json()["requestId"])
        self.assertEqual("", payload["sourceUrl"])
        self.assertEqual("video/quicktime", payload["sourceContentType"])
        self.assertEqual("English", payload["referenceLanguage"])
        self.assertEqual(b"uploaded-video", base64.b64decode(payload["sourceContent"]))

    def test_elevenlabs_without_existing_voice_id_stops_before_fal(self):
        runtime = object.__new__(LocalAvatarRuntime)
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as directory:
            root = Path(directory)
            text_file = root / "script.txt"
            text_file.write_text("Hello founder", encoding="utf-8")
            with patch.object(runtime, "_fal_client") as fal_client:
                with self.assertRaisesRegex(LocalAvatarRuntimeError, "requires an existing ElevenLabs voice ID"):
                    runtime._run_fal_elevenlabs_v3_voice(
                        {
                            "request_id": "voice-no-charge",
                            "text_file": str(text_file),
                            "prompt_wav": str(root / "reference.wav"),
                            "proprietary_voice_id": "",
                            "elevenlabs_voice_id": "",
                        },
                        root / "output.wav",
                    )
            fal_client.assert_not_called()

    def test_chatterbox_uses_documented_endpoint_and_reference_language(self):
        runtime = object.__new__(LocalAvatarRuntime)
        fal_client = Mock()
        fal_client.subscribe.return_value = {"audio": {"url": "https://example.test/voice.wav"}}
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as directory:
            root = Path(directory)
            text_file = root / "script.txt"
            text_file.write_text("Hello founder", encoding="utf-8")
            prompt_wav = root / "reference.wav"
            prompt_wav.write_bytes(b"reference")

            def fake_ffmpeg(*_args, **_kwargs):
                prompt_wav.with_name("chatterbox-reference.wav").write_bytes(b"ten-seconds")

            with (
                patch.object(runtime, "_fal_client", return_value=fal_client),
                patch.object(runtime, "_fal_upload_file", return_value="https://fal.media/reference.wav"),
                patch.object(runtime, "_download_fal_result"),
                patch("services.avatar_local_runtime.subprocess.run", side_effect=fake_ffmpeg),
            ):
                runtime._run_fal_chatterbox_voice(
                    {
                        "request_id": "voice-chatterbox",
                        "text_file": str(text_file),
                        "prompt_wav": str(prompt_wav),
                        "reference_language": "English",
                    },
                    root / "output.wav",
                )

        self.assertEqual(settings.avatar_fal_chatterbox_endpoint, fal_client.subscribe.call_args.args[0])
        arguments = fal_client.subscribe.call_args.kwargs["arguments"]
        self.assertEqual("english", arguments["custom_audio_language"])
        self.assertEqual("https://fal.media/reference.wav", arguments["voice"])

    def test_legacy_minimax_selection_is_routed_to_chatterbox(self):
        runtime = object.__new__(LocalAvatarRuntime)
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as directory:
            output = Path(directory) / "voice.wav"

            def write_chatterbox_output(_variables, destination):
                destination.write_bytes(b"chatterbox-audio")

            with (
                patch.object(runtime, "_run_fal_chatterbox_voice", side_effect=write_chatterbox_output) as chatterbox,
                patch.object(runtime, "_run_fal_minimax_voice") as minimax,
            ):
                status, model, error = runtime._run_voice_api_stage(
                    "fal_minimax_voice_clone",
                    {"request_id": "legacy-minimax"},
                    output,
                )

        self.assertEqual("completed", status)
        self.assertEqual("fal_chatterbox_multilingual", model)
        self.assertEqual("", error)
        chatterbox.assert_called_once()
        minimax.assert_not_called()

    def test_inline_media_takes_precedence_over_profile_url(self):
        runtime = object.__new__(LocalAvatarRuntime)
        payload = {
            "sourceContent": base64.b64encode(b"uploaded-video").decode("ascii"),
            "sourceContentType": "video/quicktime",
            "founderAvatarProfile": {
                "sourceUrl": "http://127.0.0.1:9/unreachable.mov",
            },
        }

        with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as directory:
            with patch("services.avatar_local_runtime.httpx.stream") as http_stream:
                source = runtime._resolve_source_media(payload, Path(directory))

            http_stream.assert_not_called()
            self.assertIsNotNone(source)
            self.assertEqual("video/quicktime", source.content_type)
            self.assertEqual(b"uploaded-video", source.path.read_bytes())

    def test_direct_elevenlabs_clones_then_generates_with_v3(self):
        runtime = object.__new__(LocalAvatarRuntime)
        client = MagicMock()
        client.__enter__.return_value = client
        clone_response = Mock()
        clone_response.is_success = True
        clone_response.json.return_value = {"voice_id": "eleven-clone-123"}
        speech_response = Mock()
        speech_response.is_success = True
        speech_response.content = b"mp3-audio"
        speech_response.headers = {
            "character-cost": "37",
            "request-id": "eleven-request-123",
            "x-trace-id": "eleven-trace-123",
        }
        client.post.side_effect = [clone_response, speech_response]

        with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as directory:
            root = Path(directory)
            text_file = root / "script.txt"
            text_file.write_text("Hello from the founder", encoding="utf-8")
            prompt_wav = root / "reference.wav"
            prompt_wav.write_bytes(b"voice-reference")
            output = root / "output.wav"

            def copy_provider_audio(source, destination):
                destination.write_bytes(source.read_bytes())

            with (
                patch.object(settings, "avatar_elevenlabs_api_key", "test-key"),
                patch.object(settings, "avatar_elevenlabs_model_id", "eleven_v3"),
                patch("services.avatar_local_runtime.httpx.Client", return_value=client),
                patch.object(runtime, "_convert_audio_to_wav", side_effect=copy_provider_audio),
            ):
                variables = {
                    "request_id": "voice-eleven-direct",
                    "text_file": str(text_file),
                    "prompt_wav": str(prompt_wav),
                    "voice_clone_name": "Founder test",
                    "language": "English",
                    "language_code": "en-IN",
                    "elevenlabs_voice_id": "",
                    "proprietary_voice_id": "",
                }
                runtime._run_elevenlabs_v3_voice(variables, output)
                generated_audio = output.read_bytes()

        self.assertEqual(b"mp3-audio", generated_audio)
        self.assertEqual("eleven-clone-123", variables["elevenlabs_voice_id"])
        self.assertTrue(variables["voice_was_cloned"])
        self.assertEqual(2, client.post.call_count)
        self.assertTrue(client.post.call_args_list[0].args[0].endswith("/v1/voices/add"))
        synthesis_call = client.post.call_args_list[1]
        self.assertTrue(synthesis_call.args[0].endswith("/v1/text-to-speech/eleven-clone-123"))
        self.assertEqual("eleven_v3", synthesis_call.kwargs["json"]["model_id"])
        self.assertEqual("en", synthesis_call.kwargs["json"]["language_code"])
        self.assertEqual(37.0, variables["elevenlabs_character_cost"])
        self.assertEqual("eleven-request-123", variables["elevenlabs_request_id"])
        self.assertEqual("eleven-trace-123", variables["elevenlabs_trace_id"])

        cost_metadata = runtime._cost_metadata(
            "elevenlabs",
            "eleven_v3",
            0.001,
            {
                "providerReportedCharacters": variables["elevenlabs_character_cost"],
                "providerUsageSource": variables["elevenlabs_usage_source"],
            },
        )
        self.assertTrue(cost_metadata["modelApiInteracted"])
        self.assertEqual("CHARACTER_CREDIT", cost_metadata["rateUnit"])

    def test_sarvam_without_private_clone_endpoint_stops_before_http(self):
        runtime = object.__new__(LocalAvatarRuntime)
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as directory:
            root = Path(directory)
            text_file = root / "script.txt"
            text_file.write_text("Namaste", encoding="utf-8")
            with (
                patch.object(settings, "avatar_sarvam_api_key", "test-key"),
                patch.object(settings, "avatar_sarvam_voice_clone_path", ""),
                patch("services.avatar_local_runtime.httpx.Client") as http_client,
                self.assertRaisesRegex(LocalAvatarRuntimeError, "public developer API does not expose"),
            ):
                runtime._run_sarvam_voice(
                    {
                        "request_id": "voice-sarvam-no-charge",
                        "text_file": str(text_file),
                        "prompt_wav": str(root / "reference.wav"),
                        "sarvam_voice_id": "",
                    },
                    root / "output.wav",
                )
            http_client.assert_not_called()

    def test_sarvam_existing_voice_generates_bulbul_audio(self):
        runtime = object.__new__(LocalAvatarRuntime)
        client = MagicMock()
        client.__enter__.return_value = client
        speech_response = Mock()
        speech_response.is_success = True
        speech_response.json.return_value = {
            "audios": [base64.b64encode(b"RIFFsarvam-wav").decode("ascii")]
        }
        client.post.return_value = speech_response

        with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as directory:
            root = Path(directory)
            text_file = root / "script.txt"
            text_file.write_text("Aap kaise hain?", encoding="utf-8")
            output = root / "output.wav"
            with (
                patch.object(settings, "avatar_sarvam_api_key", "test-key"),
                patch.object(settings, "avatar_sarvam_voice_id", ""),
                patch.object(settings, "avatar_sarvam_model_id", "bulbul:v3"),
                patch("services.avatar_local_runtime.httpx.Client", return_value=client),
            ):
                variables = {
                    "request_id": "voice-sarvam-existing",
                    "text_file": str(text_file),
                    "prompt_wav": str(root / "reference.wav"),
                    "sarvam_voice_id": "private-speaker-123",
                    "language": "Hinglish",
                    "language_code": "hi-IN",
                }
                runtime._run_sarvam_voice(variables, output)

            self.assertEqual(b"RIFFsarvam-wav", output.read_bytes())

        request_payload = client.post.call_args.kwargs["json"]
        self.assertEqual("private-speaker-123", request_payload["speaker"])
        self.assertEqual("bulbul:v3", request_payload["model"])
        self.assertEqual("hi-IN", request_payload["target_language_code"])
        self.assertFalse(variables["voice_was_cloned"])


if __name__ == "__main__":
    unittest.main()
