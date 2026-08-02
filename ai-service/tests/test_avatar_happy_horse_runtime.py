from pathlib import Path
import os
import tempfile
import unittest
from unittest.mock import Mock, patch

os.environ["DEBUG"] = "false"

from config import settings
from services.avatar_local_runtime import LocalAvatarRuntime


class AvatarHappyHorseRuntimeTest(unittest.TestCase):
    def test_fal_heygen_avatar4_uses_cloned_audio_and_documented_scene_fields(self):
        runtime = object.__new__(LocalAvatarRuntime)
        fal_client = Mock()

        def subscribe(_endpoint, **kwargs):
            kwargs["on_enqueue"]("heygen-request")
            return {"video": {"url": "https://example.test/heygen.mp4"}}

        fal_client.subscribe.side_effect = subscribe
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            portrait = root / "founder.png"
            portrait.write_bytes(b"portrait")
            audio = root / "approved.wav"
            audio.write_bytes(b"approved-cloned-audio")
            output = root / "heygen.mp4"

            def upload(_client, path):
                return f"https://fal.media/{Path(path).name}"

            def download_result(_result, _keys, destination, **_kwargs):
                destination.write_bytes(b"heygen-video")

            with (
                patch.object(runtime, "_fal_client", return_value=fal_client),
                patch.object(runtime, "_fal_upload_file", side_effect=upload),
                patch.object(runtime, "_download_fal_result", side_effect=download_result),
            ):
                request_id = runtime._run_fal_heygen_avatar4(
                    portrait,
                    audio,
                    output,
                    aspect_ratio="9:16",
                    resolution="720p",
                    talking_style="stable",
                    expression="Empathetic, inquisitive, slightly concerned then understanding.",
                    background={"type": "color", "value": "#102030"},
                )

        self.assertEqual("heygen-request", request_id)
        self.assertEqual(settings.avatar_fal_heygen_avatar4_endpoint, fal_client.subscribe.call_args.args[0])
        self.assertEqual(
            {
                "image_url": "https://fal.media/founder-heygen-source.png",
                "audio_url": "https://fal.media/approved.wav",
                "talking_style": "expressive",
                "resolution": "720p",
                "aspect_ratio": "9:16",
                "caption": False,
                "background": {"type": "color", "value": "#102030"},
            },
            fal_client.subscribe.call_args.kwargs["arguments"],
        )

    def test_fal_heygen_avatar4_maps_supported_positive_expression_to_happy(self):
        runtime = object.__new__(LocalAvatarRuntime)
        self.assertEqual("happy", runtime._heygen_expression("Warm, happy and confident"))
        self.assertEqual("", runtime._heygen_expression("Empathetic and slightly concerned"))

    def test_heygen_scene_uses_native_lip_sync_without_second_provider_call(self):
        runtime = object.__new__(LocalAvatarRuntime)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runtime.work_root = root / "work"
            runtime.work_root.mkdir()
            runtime.model_root = root / "models"
            runtime.rvc_profile_root = root / "profiles"
            portrait = root / "founder.png"
            portrait.write_bytes(b"portrait")
            approved_audio = root / "approved.wav"
            approved_audio.write_bytes(b"approved-cloned-audio")

            def generate_avatar(_source, audio, output, **kwargs):
                self.assertEqual(b"approved-cloned-audio", audio.read_bytes())
                self.assertEqual("9:16", kwargs["aspect_ratio"])
                self.assertEqual("720p", kwargs["resolution"])
                output.write_bytes(b"heygen-native-video")
                return "heygen-request"

            with (
                patch.object(runtime, "_run_fal_heygen_avatar4", side_effect=generate_avatar),
                patch.object(runtime, "_media_duration_seconds", return_value=5.0),
                patch.object(runtime, "_run_fal_lipsync") as latent_sync,
                patch.object(runtime, "_run_fal_musetalk") as muse_talk,
                patch.object(runtime, "_postprocess_video", side_effect=lambda candidate, *_args: candidate),
                patch.object(runtime, "_is_video_file", return_value=True),
            ):
                response = runtime._generate_scene_sync({
                    "runId": "heygen-native-chain",
                    "_sourceFilePath": str(portrait),
                    "_sourceFileContentType": "image/png",
                    "_audioFilePath": str(approved_audio),
                    "_audioFileContentType": "audio/wav",
                    "_returnFilePath": True,
                    "durationSeconds": 5,
                    "aspectRatio": "9:16",
                    "talkingAvatarModel": "fal_heygen_avatar4",
                    "lipSyncModel": "fal_latentsync",
                    "localModels": {"avatarResolution": "720p"},
                    "manualApprovalRequiredForFallback": True,
                })

        latent_sync.assert_not_called()
        muse_talk.assert_not_called()
        self.assertEqual("fal_heygen_avatar4", response["stages"]["avatar"])
        self.assertEqual("completed", response["stages"]["lipSync"])
        self.assertEqual("avatar_native", response["lipSyncModel"])
        self.assertEqual("heygen-request", response["avatarRequestId"])
        self.assertEqual("", response["lipSyncRequestId"])
        self.assertEqual(0.5, response["cost"])
        self.assertEqual(
            "fal-ai/heygen/avatar4/image-to-video",
            response["costMetadata"]["model"],
        )

    def test_fal_musetalk_uses_documented_video_and_audio_fields(self):
        runtime = object.__new__(LocalAvatarRuntime)
        fal_client = Mock()

        def subscribe(_endpoint, **kwargs):
            kwargs["on_enqueue"]("musetalk-request")
            return {"video": {"url": "https://example.test/musetalk.mp4"}}

        fal_client.subscribe.side_effect = subscribe
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            video = root / "silent-avatar.mp4"
            video.write_bytes(b"silent-video")
            audio = root / "approved.wav"
            audio.write_bytes(b"approved-audio")
            output = root / "musetalk.mp4"

            def upload(_client, path):
                return f"https://fal.media/{Path(path).name}"

            def download_result(_result, _keys, destination, **_kwargs):
                destination.write_bytes(b"musetalk-video")

            with (
                patch.object(runtime, "_fal_client", return_value=fal_client),
                patch.object(runtime, "_fal_upload_file", side_effect=upload),
                patch.object(runtime, "_download_fal_result", side_effect=download_result),
            ):
                request_id = runtime._run_fal_musetalk(video, audio, output)

        self.assertEqual("musetalk-request", request_id)
        self.assertEqual(settings.avatar_fal_musetalk_endpoint, fal_client.subscribe.call_args.args[0])
        self.assertEqual(
            {
                "source_video_url": "https://fal.media/silent-avatar.mp4",
                "audio_url": "https://fal.media/approved.wav",
            },
            fal_client.subscribe.call_args.kwargs["arguments"],
        )

    def test_fal_musetalk_selection_never_calls_latentsync(self):
        runtime = object.__new__(LocalAvatarRuntime)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            video = root / "silent-avatar.mp4"
            video.write_bytes(b"silent-video")
            audio = root / "approved.wav"
            audio.write_bytes(b"approved-audio")
            output = root / "musetalk.mp4"
            variables = {"audio": str(audio)}

            def run_musetalk(_video, _audio, destination):
                destination.write_bytes(b"musetalk-video")
                return "musetalk-request"

            with (
                patch.object(runtime, "_run_fal_musetalk", side_effect=run_musetalk) as muse,
                patch.object(runtime, "_run_fal_lipsync") as latent,
            ):
                candidate, status, model, error, request_id = runtime._run_lip_sync_stage(
                    "fal_musetalk",
                    variables,
                    video,
                    output,
                )

        self.assertEqual(output, candidate)
        self.assertEqual("completed", status)
        self.assertEqual("fal_musetalk", model)
        self.assertEqual("", error)
        self.assertEqual("musetalk-request", request_id)
        muse.assert_called_once()
        latent.assert_not_called()

    def test_request_has_no_audio_and_strips_generated_soundtrack(self):
        runtime = object.__new__(LocalAvatarRuntime)
        fal_client = Mock()

        def subscribe(_endpoint, **kwargs):
            kwargs["on_enqueue"]("happy-horse-request")
            return {"video": {"url": "https://example.test/happy-horse.mp4"}}

        fal_client.subscribe.side_effect = subscribe
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            portrait = root / "founder.png"
            portrait.write_bytes(b"portrait")
            output = root / "silent-avatar.mp4"

            def download_result(_result, _keys, destination, **_kwargs):
                destination.write_bytes(b"native-video-with-generated-audio")

            def strip_audio(native_video, silent_video):
                self.assertIn("native-audio", native_video.name)
                self.assertEqual(b"native-video-with-generated-audio", native_video.read_bytes())
                silent_video.write_bytes(b"silent-happy-horse-video")

            with (
                patch.object(runtime, "_fal_client", return_value=fal_client),
                patch.object(runtime, "_fal_upload_file", return_value="https://fal.media/founder.png"),
                patch.object(runtime, "_download_fal_result", side_effect=download_result),
                patch.object(runtime, "_strip_video_audio", side_effect=strip_audio) as strip,
            ):
                request_id = runtime._run_fal_happy_horse(
                    portrait,
                    output,
                    "Natural founder movement.",
                    5,
                    "1080p",
                )

        self.assertEqual("happy-horse-request", request_id)
        self.assertEqual(settings.avatar_fal_happy_horse_endpoint, fal_client.subscribe.call_args.args[0])
        arguments = fal_client.subscribe.call_args.kwargs["arguments"]
        self.assertNotIn("audio_url", arguments)
        self.assertEqual("1080p", arguments["resolution"])
        self.assertEqual(5, arguments["duration"])
        self.assertIn("Silent visual performance only", arguments["prompt"])
        strip.assert_called_once()

    def test_scene_uses_trimmed_approved_audio_for_latentsync(self):
        runtime = object.__new__(LocalAvatarRuntime)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runtime.work_root = root / "work"
            runtime.work_root.mkdir()
            runtime.model_root = root / "models"
            runtime.rvc_profile_root = root / "profiles"
            portrait = root / "founder.png"
            portrait.write_bytes(b"portrait")
            approved_audio = root / "approved.wav"
            approved_audio.write_bytes(b"approved-cloned-audio")
            stages = []

            def trim_audio(source, output, duration):
                self.assertIn("scene-dialogue.wav", source.name)
                self.assertEqual(5, duration)
                output.write_bytes(b"trimmed-approved-audio")
                stages.append("trim")

            def generate_visual(source, output, _prompt, duration, resolution, _seed):
                self.assertEqual(portrait, source)
                self.assertEqual(5, duration)
                self.assertEqual("1080p", resolution)
                output.write_bytes(b"silent-happy-horse-video")
                stages.append("happy-horse")
                return "happy-horse-request"

            def lip_sync(_model, variables, avatar_video, output, **_kwargs):
                self.assertEqual(b"silent-happy-horse-video", avatar_video.read_bytes())
                self.assertEqual(b"trimmed-approved-audio", Path(variables["audio"]).read_bytes())
                output.write_bytes(b"final-lip-synced-video")
                stages.append("latentsync")
                return output, "completed", "fal_latentsync", "", "latentsync-request"

            with (
                patch.object(runtime, "_trim_audio_for_duration", side_effect=trim_audio),
                patch.object(runtime, "_run_fal_happy_horse", side_effect=generate_visual),
                patch.object(runtime, "_run_lip_sync_stage", side_effect=lip_sync),
                patch.object(runtime, "_postprocess_video", side_effect=lambda candidate, *_args: candidate),
                patch.object(runtime, "_is_video_file", return_value=True),
            ):
                response = runtime._generate_scene_sync({
                    "runId": "happy-horse-chain",
                    "_sourceFilePath": str(portrait),
                    "_sourceFileContentType": "image/png",
                    "_audioFilePath": str(approved_audio),
                    "_audioFileContentType": "audio/wav",
                    "_returnFilePath": True,
                    "durationSeconds": 5,
                    "talkingAvatarModel": "fal_happy_horse_v1_1",
                    "lipSyncModel": "fal_latentsync",
                    "localModels": {"avatarResolution": "1080p"},
                    "manualApprovalRequiredForFallback": True,
                })

        self.assertEqual(["trim", "happy-horse", "latentsync"], stages)
        self.assertEqual("fal_happy_horse_v1_1", response["stages"]["avatar"])
        self.assertEqual("completed", response["stages"]["lipSync"])
        self.assertEqual("happy-horse-request", response["avatarRequestId"])
        self.assertEqual("latentsync-request", response["lipSyncRequestId"])
        self.assertEqual(1.1, response["cost"])
        self.assertEqual(
            "alibaba/happy-horse/v1.1/image-to-video+fal-ai/latentsync",
            response["costMetadata"]["model"],
        )


if __name__ == "__main__":
    unittest.main()
