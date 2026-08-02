"""Local founder-avatar runtime.

This module keeps heavyweight model integrations optional. The FastAPI app can
start without CosyVoice/LivePortrait/MuseTalk/LatentSync/Flux/etc. installed, while a
configured GPU worker can enable them through command templates or Python APIs.
"""
from __future__ import annotations

import asyncio
import base64
import hashlib
import importlib.util
import json
import logging
import os
import re
import shutil
import subprocess
import sys
import textwrap
import uuid
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import httpx

from config import settings

logger = logging.getLogger(__name__)


class LocalAvatarRuntimeError(RuntimeError):
    """Raised when a local model stage cannot run."""

    def __init__(self, message: str, *, stage: str = "local_runtime", setup_hint: str = ""):
        super().__init__(message)
        self.stage = stage
        self.setup_hint = setup_hint


@dataclass(frozen=True)
class RuntimeFile:
    path: Path
    content_type: str


class LocalAvatarRuntime:
    def __init__(self) -> None:
        self.model_root = Path(settings.avatar_model_root).expanduser().resolve()
        self.work_root = Path(settings.avatar_work_root).expanduser().resolve()
        self.rvc_profile_root = Path(settings.avatar_rvc_profile_root).expanduser().resolve()
        self.work_root.mkdir(parents=True, exist_ok=True)

    def capabilities(self) -> dict[str, Any]:
        return {
            "provider": "dalai_llama",
            "enabled": settings.avatar_generation_enabled,
            "localRuntimeEnabled": settings.avatar_local_runtime_enabled,
            "runnerConfigured": bool(settings.avatar_generation_runner_url),
            "gpuProfile": settings.avatar_generation_gpu_profile,
            "modelRoot": str(self.model_root),
            "workRoot": str(self.work_root),
            "stages": {
                "voice": {
                    "defaultModel": settings.avatar_voice_model,
                    "fal_minimax_voice_clone": {
                        "cloneModel": settings.avatar_fal_voice_endpoint,
                        "speechModel": settings.avatar_fal_tts_endpoint,
                        "configured": bool(settings.fal_api_key),
                        "source": "dalai_llama",
                    },
                    "fal_chatterbox_multilingual": {
                        "speechModel": settings.avatar_fal_chatterbox_endpoint,
                        "referenceVoice": True,
                        "reusableVoiceId": False,
                        "configured": bool(settings.fal_api_key),
                        "source": "dalai_llama",
                    },
                    "fal_elevenlabs_v3": {
                        "speechModel": settings.avatar_fal_elevenlabs_tts_endpoint,
                        "requiresExistingVoiceId": True,
                        "configured": bool(settings.fal_api_key),
                        "source": "fal.ai",
                    },
                    "elevenlabs_v3_voice_clone": {
                        "cloneEndpoint": "/v1/voices/add",
                        "speechModel": settings.avatar_elevenlabs_model_id,
                        "requiresExistingVoiceId": False,
                        "configured": bool(settings.avatar_elevenlabs_api_key),
                        "apiKeyConfigured": bool(settings.avatar_elevenlabs_api_key),
                        "source": "elevenlabs",
                    },
                    "sarvam_voice_clone": {
                        "cloneEndpoint": settings.avatar_sarvam_voice_clone_path,
                        "speechModel": settings.avatar_sarvam_model_id,
                        "requiresPrivateCloneAccess": True,
                        "cloneEndpointConfigured": bool(settings.avatar_sarvam_voice_clone_path.strip()),
                        "existingVoiceConfigured": bool(settings.avatar_sarvam_voice_id.strip()),
                        "configured": bool(
                            settings.avatar_sarvam_api_key
                            and (
                                settings.avatar_sarvam_voice_clone_path.strip()
                                or settings.avatar_sarvam_voice_id.strip()
                            )
                        ),
                        "apiKeyConfigured": bool(settings.avatar_sarvam_api_key),
                        "source": "sarvam",
                    },
                    "client_rvc_english": self._client_rvc_capability(),
                    "cosy_voice2": self._stage_capability(
                        "cosy_voice2",
                        settings.avatar_cosyvoice2_model_dir,
                        settings.avatar_cosyvoice2_command,
                        python_import="cosyvoice",
                    ),
                    "elevenlabs_professional": self._stage_capability(
                        "elevenlabs_professional",
                        settings.avatar_elevenlabs_base_url,
                        settings.avatar_elevenlabs_voice_command,
                    ) | {"apiKeyConfigured": bool(settings.avatar_elevenlabs_api_key)},
                    "fallback": self._stage_capability(
                        "api_fallback",
                        "",
                        settings.avatar_voice_fallback_command,
                    ),
                },
                "avatar": {
                    "defaultModel": settings.avatar_talking_model,
                    "fal_heygen_avatar4": {
                        "model": settings.avatar_fal_heygen_avatar4_endpoint,
                        "configured": bool(settings.fal_api_key),
                        "source": "fal.ai",
                        "requiresLipSync": False,
                        "acceptsClonedAudio": True,
                    },
                    "fal_happy_horse_v1_1": {
                        "model": settings.avatar_fal_happy_horse_endpoint,
                        "configured": bool(settings.fal_api_key),
                        "source": "dalai_llama",
                        "requiresLipSync": True,
                    },
                    "fal_echomimic_v3": {
                        "model": settings.avatar_fal_echomimic_endpoint,
                        "configured": bool(settings.fal_api_key),
                        "source": "dalai_llama",
                    },
                    "fal_liveportrait": {
                        "model": settings.avatar_fal_liveportrait_endpoint,
                        "configured": bool(settings.fal_api_key),
                        "source": "dalai_llama",
                    },
                    "source_video": {
                        "model": "source_video",
                        "configured": True,
                        "source": "uploaded_creator_video",
                    },
                    "echomimic_v2": self._stage_capability(
                        "echomimic_v2",
                        settings.avatar_echomimic_repo,
                        settings.avatar_echomimic_command,
                    ),
                    "liveportrait": self._stage_capability(
                        "liveportrait",
                        settings.avatar_liveportrait_repo,
                        settings.avatar_liveportrait_command,
                    ),
                },
                "lipSync": {
                    "defaultModel": settings.avatar_lipsync_model,
                    "fal_latentsync": {
                        "model": settings.avatar_fal_lipsync_endpoint,
                        "configured": bool(settings.fal_api_key),
                        "source": "dalai_llama",
                    },
                    "fal_musetalk": {
                        "model": settings.avatar_fal_musetalk_endpoint,
                        "configured": bool(settings.fal_api_key),
                        "source": "dalai_llama",
                    },
                    "musetalk": self._stage_capability(
                        "musetalk",
                        settings.avatar_musetalk_repo,
                        settings.avatar_musetalk_command,
                    ),
                    "latentsync": self._latentsync_capability(),
                    "fallback": self._stage_capability(
                        "api_fallback",
                        "",
                        settings.avatar_lipsync_fallback_command,
                    ),
                    "sync_labs": self._stage_capability(
                        "sync_labs",
                        settings.avatar_sync_labs_base_url,
                        settings.avatar_sync_labs_command,
                    ) | {"apiKeyConfigured": bool(settings.avatar_sync_labs_api_key)},
                },
                "productionVideoEdit": {
                    "model": settings.avatar_fal_video_edit_endpoint,
                    "configured": bool(settings.fal_api_key),
                    "source": "dalai_llama",
                    "optional": True,
                },
                "images": {
                    "defaultModel": settings.avatar_image_model,
                    "gemini_storyboard": {
                        "model": "gemini_storyboard",
                        "configured": True,
                        "source": "creator-service",
                    },
                    "flux_1_dev": self._stage_capability(
                        "flux_1_dev",
                        settings.avatar_flux_model_id,
                        settings.avatar_flux_command,
                        python_import="diffusers",
                    ),
                },
                "stt": self._stage_capability(
                    "faster_whisper",
                    settings.avatar_stt_model,
                    "",
                    python_import="faster_whisper",
                ),
                "captions": self._stage_capability(
                    "whisperx",
                    settings.avatar_caption_engine,
                    settings.avatar_whisperx_command,
                    python_import="whisperx",
                ),
                "backgroundRemoval": self._stage_capability(
                    "birefnet",
                    settings.avatar_birefnet_model_id,
                    settings.avatar_birefnet_command,
                    python_import="transformers",
                ),
                "upscaling": self._stage_capability(
                    "real_esrgan",
                    settings.avatar_realesrgan_executable,
                    settings.avatar_realesrgan_command,
                ),
                "faceRestoration": self._stage_capability(
                    "codeformer",
                    settings.avatar_codeformer_repo,
                    settings.avatar_codeformer_command,
                ),
            },
            "testFallbackEnabled": settings.avatar_allow_test_fallback,
        }

    async def generate_voice(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await asyncio.to_thread(self._generate_voice_sync, payload)

    async def generate_scene(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await asyncio.to_thread(self._generate_scene_sync, payload)

    async def lip_sync(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await asyncio.to_thread(self._lip_sync_sync, payload)

    async def transcribe(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await asyncio.to_thread(self._transcribe_sync, payload)

    async def generate_image(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await asyncio.to_thread(self._generate_image_sync, payload)

    async def postprocess_image(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await asyncio.to_thread(self._postprocess_image_sync, payload)

    def _generate_voice_sync(self, payload: dict[str, Any]) -> dict[str, Any]:
        request_id = self._first_text(payload.get("requestId"), f"voice-{uuid.uuid4()}")
        started_at = time.monotonic()
        caption_text = self._first_text(
            payload.get("captionText"),
            payload.get("text"),
            payload.get("dialogueScript"),
            payload.get("exactDialogue"),
        )
        text = self._apply_pronunciation_guide(
            self._first_text(payload.get("spokenText"), caption_text),
            payload.get("pronunciationGuide"),
        )
        if not text:
            raise LocalAvatarRuntimeError("Voice text is required.", stage="voice")
        logger.info(
            "Founder voice runtime started request_id=%s model=%s text_chars=%s preview=%s inline_source=%s existing_voice=%s",
            request_id,
            self._first_text(payload.get("voiceModel"), payload.get("model"), settings.avatar_voice_model),
            len(text),
            self._boolean(payload.get("preview"), False),
            bool(self._first_text(payload.get("sourceContent"), payload.get("sourceBase64"))),
            bool(self._first_text(
                payload.get("minimaxVoiceId"),
                payload.get("providerVoiceId"),
                payload.get("elevenLabsVoiceId"),
                payload.get("sarvamVoiceId"),
            )),
        )

        work_dir = self._new_work_dir("voice", payload)
        local_models = self._first_dict(payload.get("localModels"), payload.get("localAvatarModels"))
        founder_profile = self._first_dict(payload.get("founderAvatarProfile"), payload.get("founderKit"))
        selected_voice_model = self._normalize(self._first_text(
            payload.get("voiceModel"),
            local_models.get("voiceModel"),
            payload.get("model"),
            settings.avatar_voice_model,
            "fal_minimax_voice_clone",
        ))
        if selected_voice_model == "fal_minimax_voice_clone":
            logger.info(
                "Founder voice runtime migrated request_id=%s from fal_minimax_voice_clone "
                "to fal_chatterbox_multilingual",
                request_id,
            )
            selected_voice_model = "fal_chatterbox_multilingual"
        existing_minimax_voice_id = self._first_text(
            payload.get("minimaxVoiceId"),
            local_models.get("minimaxVoiceId"),
            founder_profile.get("minimaxVoiceId"),
            payload.get("providerVoiceId") if selected_voice_model == "fal_minimax_voice_clone" else "",
            local_models.get("providerVoiceId") if selected_voice_model == "fal_minimax_voice_clone" else "",
            founder_profile.get("providerVoiceId") if selected_voice_model == "fal_minimax_voice_clone" else "",
        )
        text_file = work_dir / "script.txt"
        text_file.write_text(text, encoding="utf-8")
        existing_elevenlabs_voice_id = self._first_text(
            payload.get("elevenLabsVoiceId"),
            payload.get("proprietaryVoiceId"),
            local_models.get("elevenLabsVoiceId"),
            local_models.get("proprietaryVoiceId"),
            founder_profile.get("elevenLabsVoiceId"),
            founder_profile.get("proprietaryVoiceId"),
            payload.get("providerVoiceId") if "elevenlabs" in selected_voice_model else "",
            local_models.get("providerVoiceId") if "elevenlabs" in selected_voice_model else "",
            founder_profile.get("providerVoiceId") if "elevenlabs" in selected_voice_model else "",
        )
        existing_sarvam_voice_id = self._first_text(
            payload.get("sarvamVoiceId"),
            local_models.get("sarvamVoiceId"),
            founder_profile.get("sarvamVoiceId"),
            payload.get("providerVoiceId") if selected_voice_model == "sarvam_voice_clone" else "",
            local_models.get("providerVoiceId") if selected_voice_model == "sarvam_voice_clone" else "",
            founder_profile.get("providerVoiceId") if selected_voice_model == "sarvam_voice_clone" else "",
            settings.avatar_sarvam_voice_id,
        )
        selected_voice_id = (
            existing_elevenlabs_voice_id
            if selected_voice_model in {
                "fal_elevenlabs_v3",
                "elevenlabs_v3_voice_clone",
                "elevenlabs_professional",
            }
            else existing_sarvam_voice_id
            if selected_voice_model == "sarvam_voice_clone"
            else existing_minimax_voice_id
            if selected_voice_model == "fal_minimax_voice_clone"
            else ""
        )
        source = None if selected_voice_id else self._resolve_source_media(payload, work_dir)
        logger.info(
            "Founder voice source resolved request_id=%s source_present=%s source_bytes=%s content_type=%s",
            request_id,
            bool(source),
            source.path.stat().st_size if source and source.path.exists() else 0,
            source.content_type if source else "",
        )
        prompt_wav = work_dir / "founder-reference.wav"
        reference_preparation: dict[str, Any] = {}
        if source:
            reference_preparation = (
                self._prepare_rvc_source_audio(source.path, prompt_wav)
                if selected_voice_model == "client_rvc_english"
                else self._extract_voice_reference_audio(source.path, prompt_wav)
            )
            logger.info(
                "Founder voice reference prepared request_id=%s duration_seconds=%s bytes=%s meets_recommended_minimum=%s",
                request_id,
                reference_preparation.get("durationSeconds", 0),
                prompt_wav.stat().st_size if prompt_wav.exists() else 0,
                reference_preparation.get("meetsRecommendedMinimum", False),
            )

        output_audio = work_dir / "voice.wav"
        variables = self._template_variables(payload, work_dir)
        variables.update({
            "request_id": request_id,
            "text": text,
            "text_file": str(text_file),
            "prompt_wav": str(prompt_wav),
            "prompt_text": self._reference_prompt_text(payload, prompt_wav),
            "output_audio": str(output_audio),
            "cosyvoice_model_dir": str(self._cosyvoice_model_dir()),
            "language": self._first_text(payload.get("language"), "Hinglish"),
            "language_code": self._first_text(payload.get("languageCode"), "hi-IN"),
            "reference_language": self._first_text(payload.get("referenceLanguage"), "English"),
            "language_boost": self._first_text(payload.get("languageBoost")),
            "voice_id": self._first_text(payload.get("voiceId"), founder_profile.get("voiceId")),
            "minimax_voice_id": existing_minimax_voice_id,
            "preview": self._boolean(payload.get("preview"), False),
            "proprietary_voice_id": self._first_text(existing_elevenlabs_voice_id, settings.avatar_elevenlabs_voice_id),
            "elevenlabs_voice_id": self._first_text(existing_elevenlabs_voice_id, settings.avatar_elevenlabs_voice_id),
            "sarvam_voice_id": existing_sarvam_voice_id,
            "voice_clone_name": self._first_text(payload.get("voiceId"), founder_profile.get("voiceId"), f"founder-{uuid.uuid4().hex[:10]}"),
        })

        voice_model = selected_voice_model
        if self._normalize(voice_model) in {"cosy_voice2", "cosyvoice2", "xtts_v2", "fs_tts", "spark_tts"}:
            voice_model = "fal_minimax_voice_clone"
        voice_status = "completed"
        voice_model_used = self._normalize(voice_model)
        voice_error = ""

        if voice_model_used == "client_rvc_english":
            logger.info(
                "Client RVC voice stage started request_id=%s profile_id=%s",
                request_id,
                self._rvc_profile_id(payload),
            )
            self._run_client_rvc_voice(payload, variables, prompt_wav, output_audio)
        elif self._is_proprietary_voice_model(voice_model):
            logger.info(
                "Founder voice provider stage started request_id=%s model=%s",
                request_id,
                self._normalize(voice_model),
            )
            voice_status, voice_model_used, voice_error = self._run_voice_api_stage(
                voice_model,
                variables,
                output_audio,
            )
        else:
            try:
                if settings.avatar_cosyvoice2_command:
                    self._run_template_command(settings.avatar_cosyvoice2_command, variables, stage="voice:cosy_voice2")
                else:
                    self._try_cosyvoice_python(text, prompt_wav, output_audio, payload)
                voice_model_used = "cosy_voice2"
            except LocalAvatarRuntimeError as exc:
                voice_error = str(exc)
                logger.warning("Local voice clone failed model=cosy_voice2 reason=%s", exc)

        if not output_audio.exists() or output_audio.stat().st_size == 0:
            allow_proprietary_fallback = not self._boolean(
                payload.get("manualApprovalRequiredForFallback"),
                True,
            )
            if allow_proprietary_fallback:
                fallback_status, fallback_model, fallback_error = self._run_voice_api_stage(
                    "api_fallback",
                    variables,
                    output_audio,
                )
                if output_audio.exists() and output_audio.stat().st_size > 0:
                    voice_status = fallback_status
                    voice_model_used = fallback_model
                    voice_error = voice_error or fallback_error
            if not output_audio.exists() or output_audio.stat().st_size == 0:
                if settings.avatar_allow_test_fallback and prompt_wav.exists():
                    self._voice_test_fallback(prompt_wav, output_audio, text)
                    voice_status = "test_fallback"
                    voice_model_used = "test_fallback"
                else:
                    elevenlabs_selected = "eleven" in self._normalize(voice_model)
                    raise LocalAvatarRuntimeError(
                        voice_error or "Voice clone did not produce audio. Select the proprietary voice model explicitly after approval, or configure local CosyVoice2.",
                        stage="voice",
                        setup_hint=(
                            "Create and verify the Professional Voice Clone in the founder's ElevenLabs account, then enter its voice ID."
                            if elevenlabs_selected
                            else "Set AVATAR_COSYVOICE2_COMMAND or select an approved managed provider."
                        ),
                    )

        voice_enhancement_enabled = self._boolean(
            payload.get("voiceEnhancementEnabled"),
            self._boolean(founder_profile.get("voiceEnhancementEnabled"), True),
        )
        voice_enhancement = (
            self._master_voice_output(output_audio)
            if voice_enhancement_enabled
            else {
                "status": "disabled",
                "applied": False,
                "profile": "studio_voice_v1",
            }
        )
        provider_reported_character_cost = max(
            0.0,
            float(variables.get("elevenlabs_character_cost") or 0),
        )
        voice_cost = (
            self._fal_minimax_voice_cost(text, self._boolean(variables.get("voice_was_cloned"), False))
            if voice_model_used == "fal_minimax_voice_clone"
            else round(len(text) * 0.025 / 1000, 6)
            if voice_model_used == "fal_chatterbox_multilingual"
            else round(
                provider_reported_character_cost
                * max(0.0, float(settings.avatar_elevenlabs_usd_per_million_credits or 0))
                / 1_000_000,
                6,
            )
            if voice_model_used == "elevenlabs_v3_voice_clone"
            else 0.0
        )
        fal_voice_model = voice_model_used in {"fal_minimax_voice_clone", "fal_chatterbox_multilingual", "fal_elevenlabs_v3"}
        billing_provider = (
            "fal.ai"
            if fal_voice_model
            else "elevenlabs"
            if voice_model_used == "elevenlabs_v3_voice_clone"
            else "sarvam"
            if voice_model_used == "sarvam_voice_clone"
            else "dalai_llama"
        )
        client_voice_profile_id = self._rvc_profile_id(payload) if voice_model_used == "client_rvc_english" else ""
        stable_provider_voice_id = f"rvc:{client_voice_profile_id}" if client_voice_profile_id else ""
        generated_provider_voice_id = self._first_text(
            stable_provider_voice_id,
            variables.get("minimax_voice_id"),
            variables.get("elevenlabs_voice_id"),
            variables.get("sarvam_voice_id"),
        )
        result = {
            "status": "COMPLETED",
            "provider": "dalai_llama",
            "model": voice_model_used or self._first_text(payload.get("voiceModel"), payload.get("model"), "cosy_voice2"),
            "voiceModel": voice_model_used or self._first_text(payload.get("voiceModel"), payload.get("model"), "cosy_voice2"),
            "voiceCloneStatus": voice_status,
            "voiceCloneError": voice_error,
            "contentType": "audio/wav",
            "audioContent": self._base64_file(output_audio),
            "audioUrl": "",
            "workDir": str(work_dir),
            "voiceId": self._first_text(generated_provider_voice_id, payload.get("voiceId")),
            "providerVoiceId": generated_provider_voice_id,
            "customVoiceId": generated_provider_voice_id,
            "minimaxVoiceId": self._first_text(variables.get("minimax_voice_id")),
            "elevenLabsVoiceId": self._first_text(variables.get("elevenlabs_voice_id")),
            "sarvamVoiceId": self._first_text(variables.get("sarvam_voice_id")),
            "voiceProfileId": client_voice_profile_id,
            "adapterApplied": bool(client_voice_profile_id),
            "voiceEmbeddingId": self._first_text(payload.get("voiceEmbeddingId")),
            "language": self._first_text(payload.get("language"), "Hinglish"),
            "languageCode": self._first_text(payload.get("languageCode"), "hi-IN"),
            "languageBoost": self._first_text(variables.get("minimax_language_boost")),
            "spokenText": text,
            "captionText": caption_text or text,
            "referenceTranscriptProvided": bool(self._first_text(payload.get("promptText"), payload.get("referenceTranscript"))),
            "referencePreparation": reference_preparation,
            "voiceEnhancementStatus": voice_enhancement.get("status"),
            "voiceEnhancementApplied": voice_enhancement.get("applied", False),
            "voiceEnhancementProfile": voice_enhancement.get("profile", "studio_voice_v1"),
            "voiceEnhancement": voice_enhancement,
            "preview": self._boolean(payload.get("preview"), False),
            "cost": voice_cost,
            "costMetadata": self._cost_metadata(
                billing_provider,
                voice_model_used,
                voice_cost,
                {
                    "characters": len(text),
                    "billableCharacters": len(text),
                    "providerReportedCharacters": provider_reported_character_cost,
                    "providerUsageSource": self._first_text(
                        variables.get("elevenlabs_usage_source"),
                        "COMPLETED_OUTPUT_FALLBACK",
                    ),
                    "providerRequestId": self._first_text(variables.get("elevenlabs_request_id")),
                    "providerTraceId": self._first_text(variables.get("elevenlabs_trace_id")),
                    "falRequestIds": list(variables.get("fal_request_ids") or []),
                    "voiceCloneCreated": self._boolean(variables.get("voice_was_cloned"), False),
                    "languageBoost": self._first_text(variables.get("minimax_language_boost")),
                    "voiceEnhancementApplied": voice_enhancement.get("applied", False),
                    "voiceEnhancementProfile": voice_enhancement.get("profile", "studio_voice_v1"),
                },
            ),
        }
        logger.info(
            "Founder voice runtime completed request_id=%s model=%s status=%s elapsed_ms=%s output_bytes=%s clone_created=%s",
            request_id,
            voice_model_used,
            voice_status,
            round((time.monotonic() - started_at) * 1000),
            output_audio.stat().st_size if output_audio.exists() else 0,
            self._boolean(variables.get("voice_was_cloned"), False),
        )
        return result

    def _run_voice_api_stage(self, requested_model: str, variables: dict[str, str], output_audio: Path) -> tuple[str, str, str]:
        request_id = self._first_text(variables.get("request_id"), "voice-unknown")
        selected = self._normalize(requested_model or "api_fallback")
        routes = {
            "fal_minimax_voice_clone": ["fal_chatterbox_multilingual"],
            "fal_elevenlabs_v3": ["fal_elevenlabs_v3"],
            "fal_chatterbox_multilingual": ["fal_chatterbox_multilingual"],
            "elevenlabs_v3_voice_clone": ["elevenlabs_v3_voice_clone"],
            "sarvam_voice_clone": ["sarvam_voice_clone"],
            "elevenlabs_professional": ["elevenlabs_professional"],
            "api_fallback": ["api_fallback"],
        }
        preferred = routes.get(selected)
        if preferred is None:
            raise LocalAvatarRuntimeError(
                f"Unsupported voice provider selection: {selected or 'empty'}.",
                stage="voice:provider_selection",
                setup_hint="Select a supported voice provider from the UI.",
            )

        attempted: set[str] = set()
        last_error = ""
        for model_name in preferred:
            if model_name in attempted:
                continue
            attempted.add(model_name)
            if output_audio.exists():
                output_audio.unlink()
            variables["voice_clone_model"] = model_name
            logger.info(
                "Founder voice provider attempt request_id=%s model=%s attempt=%s",
                request_id,
                model_name,
                len(attempted),
            )
            try:
                if model_name == "fal_minimax_voice_clone":
                    self._run_fal_minimax_voice(variables, output_audio)
                elif model_name == "fal_elevenlabs_v3":
                    self._run_fal_elevenlabs_v3_voice(variables, output_audio)
                elif model_name == "fal_chatterbox_multilingual":
                    self._run_fal_chatterbox_voice(variables, output_audio)
                elif model_name == "elevenlabs_v3_voice_clone":
                    self._run_elevenlabs_v3_voice(variables, output_audio)
                elif model_name == "sarvam_voice_clone":
                    self._run_sarvam_voice(variables, output_audio)
                elif model_name == "elevenlabs_professional" and not variables.get("proprietary_voice_id", "").strip():
                    raise LocalAvatarRuntimeError(
                        "An approved ElevenLabs Professional voice ID is required.",
                        stage="voice:elevenlabs_professional",
                        setup_hint="Create and verify the PVC in the founder's ElevenLabs account, then enter its voice ID.",
                    )
                else:
                    command = self._voice_api_command(model_name)
                    if not command:
                        continue
                    self._run_template_command(command, variables, stage=f"voice:{model_name}")
                if output_audio.exists() and output_audio.stat().st_size > 0:
                    logger.info(
                        "Founder voice provider attempt completed request_id=%s model=%s output_bytes=%s",
                        request_id,
                        model_name,
                        output_audio.stat().st_size,
                    )
                    return "completed" if model_name != "api_fallback" else "fallback_completed", model_name, last_error
                last_error = f"{model_name} completed without writing {output_audio.name}."
            except LocalAvatarRuntimeError as exc:
                last_error = str(exc)
                logger.warning(
                    "Founder voice provider attempt failed request_id=%s model=%s stage=%s error_type=%s reason=%s",
                    request_id,
                    model_name,
                    exc.stage,
                    type(exc).__name__,
                    exc,
                )
        return "skipped_after_error" if last_error else "skipped", selected or "api_fallback", last_error

    def _run_fal_chatterbox_voice(self, variables: dict[str, Any], output_audio: Path) -> None:
        request_id = self._first_text(variables.get("request_id"), "voice-unknown")
        text = Path(variables["text_file"]).read_text(encoding="utf-8").strip()
        prompt_wav = Path(variables["prompt_wav"])
        if not text:
            raise LocalAvatarRuntimeError(
                "Voice text is required for the multilingual voice preview.",
                stage="voice:fal_chatterbox_multilingual",
            )
        if len(text) > 300:
            raise LocalAvatarRuntimeError(
                "The multilingual voice preview accepts at most 300 characters.",
                stage="voice:fal_chatterbox_multilingual",
            )
        if not prompt_wav.exists() or prompt_wav.stat().st_size == 0:
            raise LocalAvatarRuntimeError(
                "Upload a creator video before generating a multilingual voice preview.",
                stage="voice:fal_chatterbox_multilingual",
            )

        reference_wav = prompt_wav.with_name("chatterbox-reference.wav")
        command = [
            "ffmpeg", "-y", "-i", str(prompt_wav), "-t", "10",
            "-ac", "1", "-ar", "24000", "-c:a", "pcm_s16le", str(reference_wav),
        ]
        try:
            subprocess.run(
                command,
                check=True,
                capture_output=True,
                text=True,
                timeout=settings.avatar_stage_timeout_seconds,
            )
        except Exception as exc:
            raise LocalAvatarRuntimeError(
                f"Could not prepare the 10-second creator voice reference: {exc}",
                stage="voice:fal_chatterbox_multilingual",
            ) from exc

        reference_language = "hindi" if self._normalize(variables.get("reference_language")) == "hindi" else "english"
        fal_client = self._fal_client()
        try:
            logger.info(
                "Multilingual reference voice submitted via fal.ai request_id=%s endpoint=%s "
                "reference_bytes=%s reference_language=%s text_chars=%s",
                request_id,
                settings.avatar_fal_chatterbox_endpoint,
                reference_wav.stat().st_size,
                reference_language,
                len(text),
            )
            result = fal_client.subscribe(
                settings.avatar_fal_chatterbox_endpoint,
                arguments={
                    "text": text,
                    "voice": self._fal_upload_file(fal_client, reference_wav),
                    "custom_audio_language": reference_language,
                    "exaggeration": 0.5,
                    "temperature": 0.8,
                    "cfg_scale": 0.5,
                },
                with_logs=True,
                on_enqueue=self._fal_enqueue_capture(variables),
                headers=self._fal_request_headers(),
                client_timeout=settings.avatar_proprietary_api_timeout_seconds,
            )
            variables["voice_was_cloned"] = False
            self._download_fal_result(
                result,
                ("audio",),
                output_audio,
                stage="voice:fal_chatterbox_multilingual",
            )
        except LocalAvatarRuntimeError:
            raise
        except Exception as exc:
            raise LocalAvatarRuntimeError(
                f"DalaiLlama multilingual voice preview failed: {exc}",
                stage="voice:fal_chatterbox_multilingual",
            ) from exc

    def _voice_api_command(self, model_name: str) -> str:
        normalized = self._normalize(model_name)
        if normalized in {"elevenlabs_voice_clone", "elevenlabs_professional"}:
            return settings.avatar_elevenlabs_voice_command
        if normalized == "api_fallback":
            return settings.avatar_voice_fallback_command
        return ""

    def _run_client_rvc_voice(
        self,
        payload: dict[str, Any],
        variables: dict[str, Any],
        source_speech: Path,
        output_audio: Path,
    ) -> None:
        language = self._normalize(self._first_text(payload.get("language"), "English"))
        language_code = self._normalize(self._first_text(payload.get("languageCode"), "en-IN"))
        if language != "english" and not language_code.startswith("en_"):
            raise LocalAvatarRuntimeError(
                "This client voice adapter currently accepts English dialogue only.",
                stage="voice:client_rvc_english",
                setup_hint="Translate the screenplay dialogue to English before generating the voice.",
            )
        if self._normalize(payload.get("sourcePurpose")) != "desired_speech":
            raise LocalAvatarRuntimeError(
                "Prepared English source speech is required for the client voice adapter.",
                stage="voice:client_rvc_english",
                setup_hint="Generate English source TTS in creator-service and submit it as desired_speech.",
            )
        if not source_speech.is_file() or source_speech.stat().st_size < 1000:
            raise LocalAvatarRuntimeError(
                "Prepared English source speech is missing.",
                stage="voice:client_rvc_english",
            )

        profile_id = self._rvc_profile_id(payload)
        model_path, index_path = self._rvc_profile_paths(profile_id)
        missing = [
            str(path)
            for path in (model_path, index_path)
            if not path.is_file() or path.stat().st_size == 0
        ]
        if missing:
            raise LocalAvatarRuntimeError(
                f"Client voice profile is not installed: {profile_id}. Missing: {', '.join(missing)}",
                stage="voice:client_rvc_english",
                setup_hint=(
                    f"Place voice.pth and voice.index under "
                    f"{self.rvc_profile_root / profile_id}."
                ),
            )

        variables.update({
            "rvc_profile_id": profile_id,
            "rvc_model_path": str(model_path),
            "rvc_index_path": str(index_path),
            "rvc_applio_root": settings.avatar_rvc_applio_root,
            "rvc_wsl_venv": settings.avatar_rvc_wsl_venv,
            "rvc_input_audio": str(source_speech),
            "rvc_output_audio": str(output_audio),
        })
        if settings.avatar_rvc_command:
            self._run_template_command(
                settings.avatar_rvc_command,
                variables,
                stage="voice:client_rvc_english",
            )
            return

        wrapper = Path(__file__).resolve().parents[1] / "scripts" / "run_rvc_voice_adapter.py"
        command = [
            sys.executable,
            str(wrapper),
            "--input",
            str(source_speech),
            "--output",
            str(output_audio),
            "--model",
            str(model_path),
            "--index",
            str(index_path),
            "--applio-root",
            settings.avatar_rvc_applio_root,
            "--wsl-venv",
            settings.avatar_rvc_wsl_venv,
        ]
        try:
            completed = subprocess.run(
                command,
                check=False,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=settings.avatar_stage_timeout_seconds,
            )
        except subprocess.TimeoutExpired as exc:
            raise LocalAvatarRuntimeError(
                "Client voice adapter timed out.",
                stage="voice:client_rvc_english",
            ) from exc
        if completed.returncode != 0:
            raise LocalAvatarRuntimeError(
                "Client voice adapter failed: "
                + (completed.stderr[-1200:] or completed.stdout[-1200:] or "unknown error"),
                stage="voice:client_rvc_english",
            )

    def _client_rvc_capability(self) -> dict[str, Any]:
        profile_id = self._rvc_profile_id({})
        model_path, index_path = self._rvc_profile_paths(profile_id)
        return {
            "configured": model_path.is_file() and index_path.is_file(),
            "profileId": profile_id,
            "profileRoot": str(self.rvc_profile_root),
            "modelPath": str(model_path),
            "indexPath": str(index_path),
            "language": "English",
            "languageCode": "en-IN",
            "sourceSpeechRequired": True,
            "speakerSpecific": True,
            "reusableVoiceId": True,
            "source": "dalai_llama",
        }

    def _rvc_profile_id(self, payload: dict[str, Any]) -> str:
        local_models = self._first_dict(payload.get("localModels"), payload.get("localAvatarModels"))
        founder_profile = self._first_dict(payload.get("founderAvatarProfile"), payload.get("founderKit"))
        profile_id = self._normalize(self._first_text(
            payload.get("voiceProfileId"),
            payload.get("clientVoiceProfileId"),
            local_models.get("voiceProfileId"),
            founder_profile.get("voiceProfileId"),
            settings.avatar_rvc_default_profile_id,
        ))
        if not profile_id or not re.fullmatch(r"[a-z0-9_]{1,80}", profile_id):
            raise LocalAvatarRuntimeError(
                "Invalid client voice profile ID.",
                stage="voice:client_rvc_english",
            )
        return profile_id

    def _rvc_profile_paths(self, profile_id: str) -> tuple[Path, Path]:
        profile_root = (self.rvc_profile_root / profile_id).resolve()
        if self.rvc_profile_root not in profile_root.parents:
            raise LocalAvatarRuntimeError(
                "Client voice profile resolved outside the configured profile root.",
                stage="voice:client_rvc_english",
            )
        return profile_root / "voice.pth", profile_root / "voice.index"

    def _run_fal_elevenlabs_v3_voice(self, variables: dict[str, Any], output_audio: Path) -> None:
        request_id = self._first_text(variables.get("request_id"), "voice-unknown")
        text = Path(variables["text_file"]).read_text(encoding="utf-8").strip()
        prompt_wav = Path(variables["prompt_wav"])
        if not text:
            raise LocalAvatarRuntimeError("Voice text is required for ElevenLabs Eleven v3.", stage="voice:fal_elevenlabs_v3")

        voice_id = self._first_text(variables.get("proprietary_voice_id"), variables.get("elevenlabs_voice_id"))
        try:
            if not voice_id:
                raise LocalAvatarRuntimeError(
                    "ElevenLabs Eleven v3 on fal.ai requires an existing ElevenLabs voice ID. "
                    "fal.ai does not expose a public ElevenLabs voice-cloning endpoint; "
                    "no provider request was sent.",
                    stage="voice:fal_elevenlabs_v3",
                )
            variables["voice_was_cloned"] = False
            fal_client = self._fal_client()

            variables["elevenlabs_voice_id"] = voice_id
            language_code = "en" if self._normalize(variables.get("language")) == "english" else "hi"
            logger.info(
                "ElevenLabs Eleven v3 speech submitted via fal.ai request_id=%s endpoint=%s language_code=%s existing_voice=%s text_chars=%s",
                request_id,
                settings.avatar_fal_elevenlabs_tts_endpoint,
                language_code,
                not self._boolean(variables.get("voice_was_cloned"), False),
                len(text),
            )
            result = fal_client.subscribe(
                settings.avatar_fal_elevenlabs_tts_endpoint,
                arguments={
                    "text": text,
                    "voice": voice_id,
                    "stability": 0.5,
                    "language_code": language_code,
                    "apply_text_normalization": "auto",
                },
                with_logs=True,
                on_enqueue=self._fal_enqueue_capture(variables),
                headers=self._fal_request_headers(),
                client_timeout=settings.avatar_proprietary_api_timeout_seconds,
            )
            self._download_fal_result(result, ("audio",), output_audio, stage="voice:fal_elevenlabs_v3")
        except LocalAvatarRuntimeError:
            raise
        except Exception as exc:
            raise LocalAvatarRuntimeError(
                f"fal.ai ElevenLabs Eleven v3 failed: {exc}",
                stage="voice:fal_elevenlabs_v3",
            ) from exc

    def _run_elevenlabs_v3_voice(self, variables: dict[str, Any], output_audio: Path) -> None:
        stage = "voice:elevenlabs_v3_voice_clone"
        request_id = self._first_text(variables.get("request_id"), "voice-unknown")
        api_key = self._first_text(settings.avatar_elevenlabs_api_key)
        if not api_key:
            raise LocalAvatarRuntimeError(
                "ElevenLabs voice cloning is not configured; no provider request was sent.",
                stage=stage,
                setup_hint="Set AVATAR_ELEVENLABS_API_KEY in the ai-service secret.",
            )

        text = Path(variables["text_file"]).read_text(encoding="utf-8").strip()
        if not text:
            raise LocalAvatarRuntimeError("Voice text is required for ElevenLabs v3.", stage=stage)
        prompt_wav = Path(variables["prompt_wav"])
        voice_id = self._first_text(
            variables.get("elevenlabs_voice_id"),
            variables.get("proprietary_voice_id"),
            settings.avatar_elevenlabs_voice_id,
        )
        base_url = settings.avatar_elevenlabs_base_url.rstrip("/")
        timeout = httpx.Timeout(settings.avatar_proprietary_api_timeout_seconds)
        headers = {"xi-api-key": api_key}

        try:
            with httpx.Client(timeout=timeout, follow_redirects=True) as client:
                if not voice_id:
                    if not prompt_wav.exists() or prompt_wav.stat().st_size == 0:
                        raise LocalAvatarRuntimeError(
                            "An enhanced creator voice reference is required for ElevenLabs cloning.",
                            stage=stage,
                        )
                    logger.info(
                        "ElevenLabs instant voice clone submitted request_id=%s reference_bytes=%s",
                        request_id,
                        prompt_wav.stat().st_size,
                    )
                    with prompt_wav.open("rb") as reference:
                        clone_response = client.post(
                            f"{base_url}/v1/voices/add",
                            headers=headers,
                            data={
                                "name": self._first_text(variables.get("voice_clone_name"), f"founder-{uuid.uuid4().hex[:10]}"),
                                "remove_background_noise": str(
                                    settings.avatar_elevenlabs_remove_background_noise
                                ).lower(),
                            },
                            files=[("files", (prompt_wav.name, reference, "audio/wav"))],
                        )
                    self._raise_provider_http_error(clone_response, "ElevenLabs voice clone", stage)
                    voice_id = self._provider_identifier(clone_response.json())
                    if not voice_id:
                        raise LocalAvatarRuntimeError(
                            "ElevenLabs voice cloning completed without a voice ID.",
                            stage=stage,
                        )
                    variables["voice_was_cloned"] = True
                    variables["elevenlabs_voice_id"] = voice_id
                    variables["proprietary_voice_id"] = voice_id
                    logger.info(
                        "ElevenLabs instant voice clone completed request_id=%s provider_voice_created=true",
                        request_id,
                    )
                else:
                    variables["voice_was_cloned"] = False
                    variables["elevenlabs_voice_id"] = voice_id

                language_code = self._elevenlabs_language_code(
                    variables.get("language"),
                    variables.get("language_code"),
                )
                logger.info(
                    "ElevenLabs v3 speech submitted request_id=%s model=%s language_code=%s existing_voice=%s text_chars=%s",
                    request_id,
                    settings.avatar_elevenlabs_model_id,
                    language_code,
                    not self._boolean(variables.get("voice_was_cloned"), False),
                    len(text),
                )
                speech_response = client.post(
                    f"{base_url}/v1/text-to-speech/{voice_id}",
                    params={"output_format": settings.avatar_elevenlabs_output_format},
                    headers={
                        **headers,
                        "Accept": "audio/mpeg",
                        "Content-Type": "application/json",
                    },
                    json={
                        "text": text,
                        "model_id": settings.avatar_elevenlabs_model_id,
                        "language_code": language_code,
                        "voice_settings": {
                            "stability": 0.5,
                            "similarity_boost": 0.8,
                            "style": 0.0,
                            "use_speaker_boost": True,
                        },
                    },
                )
                self._raise_provider_http_error(speech_response, "ElevenLabs v3 speech", stage)
                if not speech_response.content:
                    raise LocalAvatarRuntimeError(
                        "ElevenLabs v3 completed without audio.",
                        stage=stage,
                    )
                provider_audio = output_audio.with_name(f"{output_audio.stem}-elevenlabs.mp3")
                response_headers = getattr(speech_response, "headers", {}) or {}
                raw_character_cost = response_headers.get("character-cost")
                try:
                    character_cost = max(0.0, float(raw_character_cost or 0))
                except (TypeError, ValueError):
                    character_cost = 0.0
                variables["elevenlabs_character_cost"] = character_cost or float(len(text))
                variables["elevenlabs_usage_source"] = (
                    "ELEVENLABS_CHARACTER_COST_RESPONSE_HEADER"
                    if character_cost > 0
                    else "COMPLETED_TEXT_CHARACTER_FALLBACK"
                )
                variables["elevenlabs_request_id"] = self._first_text(
                    response_headers.get("request-id"),
                    response_headers.get("request_id"),
                )
                variables["elevenlabs_trace_id"] = self._first_text(
                    response_headers.get("x-trace-id"),
                    response_headers.get("x_trace_id"),
                )
                provider_audio.write_bytes(speech_response.content)
                self._convert_audio_to_wav(provider_audio, output_audio)
        except LocalAvatarRuntimeError:
            raise
        except Exception as exc:
            raise LocalAvatarRuntimeError(
                f"ElevenLabs v3 voice generation failed: {exc}",
                stage=stage,
            ) from exc

    def _run_sarvam_voice(self, variables: dict[str, Any], output_audio: Path) -> None:
        stage = "voice:sarvam_voice_clone"
        request_id = self._first_text(variables.get("request_id"), "voice-unknown")
        api_key = self._first_text(settings.avatar_sarvam_api_key)
        if not api_key:
            raise LocalAvatarRuntimeError(
                "Sarvam voice cloning is not configured; no provider request was sent.",
                stage=stage,
                setup_hint="Set AVATAR_SARVAM_API_KEY in the ai-service secret.",
            )

        text = Path(variables["text_file"]).read_text(encoding="utf-8").strip()
        if not text:
            raise LocalAvatarRuntimeError("Voice text is required for Sarvam.", stage=stage)
        if len(text) > 2500:
            raise LocalAvatarRuntimeError(
                "Sarvam Bulbul v3 accepts at most 2500 characters per request.",
                stage=stage,
            )

        voice_id = self._first_text(variables.get("sarvam_voice_id"), settings.avatar_sarvam_voice_id)
        clone_path = self._first_text(settings.avatar_sarvam_voice_clone_path)
        if not voice_id and not clone_path:
            raise LocalAvatarRuntimeError(
                "Sarvam's public developer API does not expose reusable voice cloning. "
                "No provider request was sent.",
                stage=stage,
                setup_hint=(
                    "Enable Sarvam private voice-cloning API access and set "
                    "AVATAR_SARVAM_VOICE_CLONE_PATH, or enter an existing Sarvam clone/speaker ID."
                ),
            )

        prompt_wav = Path(variables["prompt_wav"])
        base_url = settings.avatar_sarvam_base_url.rstrip("/")
        headers = {"api-subscription-key": api_key}
        timeout = httpx.Timeout(settings.avatar_proprietary_api_timeout_seconds)
        try:
            with httpx.Client(timeout=timeout, follow_redirects=True) as client:
                if not voice_id:
                    if not prompt_wav.exists() or prompt_wav.stat().st_size == 0:
                        raise LocalAvatarRuntimeError(
                            "An enhanced creator voice reference is required for Sarvam cloning.",
                            stage=stage,
                        )
                    clone_url = self._provider_url(base_url, clone_path)
                    logger.info(
                        "Sarvam private voice clone submitted request_id=%s reference_bytes=%s",
                        request_id,
                        prompt_wav.stat().st_size,
                    )
                    with prompt_wav.open("rb") as reference:
                        clone_response = client.post(
                            clone_url,
                            headers=headers,
                            data={
                                "name": self._first_text(variables.get("voice_clone_name"), f"founder-{uuid.uuid4().hex[:10]}"),
                                "language_code": self._sarvam_language_code(
                                    variables.get("language"),
                                    variables.get("language_code"),
                                ),
                                "consent_confirmed": "true",
                            },
                            files={"file": (prompt_wav.name, reference, "audio/wav")},
                        )
                    self._raise_provider_http_error(clone_response, "Sarvam voice clone", stage)
                    voice_id = self._provider_identifier(clone_response.json())
                    if not voice_id:
                        raise LocalAvatarRuntimeError(
                            "Sarvam voice cloning completed without a reusable voice ID.",
                            stage=stage,
                        )
                    variables["voice_was_cloned"] = True
                    variables["sarvam_voice_id"] = voice_id
                    logger.info(
                        "Sarvam private voice clone completed request_id=%s provider_voice_created=true",
                        request_id,
                    )
                else:
                    variables["voice_was_cloned"] = False
                    variables["sarvam_voice_id"] = voice_id

                language_code = self._sarvam_language_code(
                    variables.get("language"),
                    variables.get("language_code"),
                )
                logger.info(
                    "Sarvam speech submitted request_id=%s model=%s language_code=%s existing_voice=%s text_chars=%s",
                    request_id,
                    settings.avatar_sarvam_model_id,
                    language_code,
                    not self._boolean(variables.get("voice_was_cloned"), False),
                    len(text),
                )
                speech_response = client.post(
                    self._provider_url(base_url, settings.avatar_sarvam_tts_path),
                    headers={**headers, "Content-Type": "application/json"},
                    json={
                        "text": text,
                        "target_language_code": language_code,
                        "speaker": voice_id,
                        "model": settings.avatar_sarvam_model_id,
                        "pace": 1.0,
                        "speech_sample_rate": settings.avatar_sarvam_sample_rate,
                        "enable_preprocessing": True,
                    },
                )
                self._raise_provider_http_error(speech_response, "Sarvam speech", stage)
                speech_payload = speech_response.json()
                audios = speech_payload.get("audios") if isinstance(speech_payload, dict) else None
                audio_base64 = self._first_text(audios[0] if isinstance(audios, list) and audios else "")
                if "," in audio_base64 and audio_base64.lower().startswith("data:"):
                    audio_base64 = audio_base64.split(",", 1)[1]
                if not audio_base64:
                    raise LocalAvatarRuntimeError(
                        "Sarvam speech completed without audio.",
                        stage=stage,
                    )
                try:
                    audio_bytes = base64.b64decode(audio_base64, validate=True)
                except Exception as exc:
                    raise LocalAvatarRuntimeError(
                        "Sarvam speech returned invalid base64 audio.",
                        stage=stage,
                    ) from exc
                provider_audio = output_audio.with_name(
                    f"{output_audio.stem}-sarvam.{settings.avatar_sarvam_output_codec.strip().lower() or 'wav'}"
                )
                provider_audio.write_bytes(audio_bytes)
                if audio_bytes.startswith(b"RIFF"):
                    shutil.copyfile(provider_audio, output_audio)
                else:
                    self._convert_audio_to_wav(provider_audio, output_audio)
        except LocalAvatarRuntimeError:
            raise
        except Exception as exc:
            raise LocalAvatarRuntimeError(
                f"Sarvam voice generation failed: {exc}",
                stage=stage,
            ) from exc

    def _run_fal_minimax_voice(self, variables: dict[str, Any], output_audio: Path) -> None:
        request_id = self._first_text(variables.get("request_id"), "voice-unknown")
        started_at = time.monotonic()
        prompt_wav = Path(variables["prompt_wav"])
        text = Path(variables["text_file"]).read_text(encoding="utf-8").strip()
        if not text:
            raise LocalAvatarRuntimeError("Voice text is required for DalaiLlama Voice Clone.", stage="voice:fal_minimax_voice_clone")
        if len(text) > 5000:
            raise LocalAvatarRuntimeError(
                "DalaiLlama Voice Clone accepts at most 5000 characters per request.",
                stage="voice:fal_minimax_voice_clone",
            )

        fal_client = self._fal_client()
        try:
            provider_voice_id = self._first_text(variables.get("minimax_voice_id"))
            language_boost = self._minimax_language_boost(
                variables.get("language_boost"),
                variables.get("language"),
                variables.get("language_code"),
            )
            variables["minimax_language_boost"] = language_boost
            if provider_voice_id:
                logger.info(
                    "MiniMax speech generation submitted request_id=%s endpoint=%s language_boost=%s existing_voice=true text_chars=%s",
                    request_id,
                    settings.avatar_fal_tts_endpoint,
                    language_boost,
                    len(text),
                )
                result = fal_client.subscribe(
                    settings.avatar_fal_tts_endpoint,
                    arguments={
                        "text": text,
                        "voice_setting": {
                            "voice_id": provider_voice_id,
                            "speed": 1.0,
                            "vol": 1.0,
                            "pitch": 0,
                            "english_normalization": language_boost == "English",
                        },
                        "language_boost": language_boost,
                        "output_format": "url",
                    },
                    with_logs=True,
                    on_enqueue=self._fal_enqueue_capture(variables),
                    headers=self._fal_request_headers(),
                    client_timeout=settings.avatar_proprietary_api_timeout_seconds,
                )
                variables["voice_was_cloned"] = False
            else:
                if not prompt_wav.exists() or prompt_wav.stat().st_size == 0:
                    raise LocalAvatarRuntimeError(
                        "An enhanced creator voice reference is required for DalaiLlama Voice Clone.",
                        stage="voice:fal_minimax_voice_clone",
                    )
                if self._media_duration_seconds(prompt_wav) < 10:
                    raise LocalAvatarRuntimeError(
                        "The creator video must contain at least 10 seconds of clear speech for voice cloning.",
                        stage="voice:fal_minimax_voice_clone",
                    )
                reference_duration = self._media_duration_seconds(prompt_wav)
                logger.info(
                    "MiniMax voice clone submitted request_id=%s endpoint=%s reference_duration_seconds=%.3f reference_bytes=%s text_chars=%s",
                    request_id,
                    settings.avatar_fal_voice_endpoint,
                    reference_duration,
                    prompt_wav.stat().st_size,
                    len(text),
                )
                result = fal_client.subscribe(
                    settings.avatar_fal_voice_endpoint,
                    arguments={
                        "audio_url": self._fal_upload_file(fal_client, prompt_wav),
                        "noise_reduction": True,
                        "need_volume_normalization": True,
                        "text": text,
                        "model": "speech-02-hd",
                    },
                    with_logs=True,
                    on_enqueue=self._fal_enqueue_capture(variables),
                    headers=self._fal_request_headers(),
                    client_timeout=settings.avatar_proprietary_api_timeout_seconds,
                )
                result_map = result if isinstance(result, dict) else {}
                provider_voice_id = self._first_text(
                    result_map.get("custom_voice_id"),
                    result_map.get("customVoiceId"),
                )
                if not provider_voice_id:
                    raise LocalAvatarRuntimeError(
                        "DalaiLlama Voice Clone returned no reusable voice ID.",
                        stage="voice:fal_minimax_voice_clone",
                    )
                variables["minimax_voice_id"] = provider_voice_id
                variables["voice_was_cloned"] = True
                logger.info(
                    "MiniMax voice clone completed request_id=%s provider_voice_created=true elapsed_ms=%s",
                    request_id,
                    round((time.monotonic() - started_at) * 1000),
                )
                clone_audio = result_map.get("audio")
                clone_audio_url = self._first_text(
                    clone_audio.get("url") if isinstance(clone_audio, dict) else clone_audio
                )
                if not clone_audio_url.startswith(("https://", "http://")):
                    logger.info(
                        "MiniMax clone returned no preview audio; submitting speech generation request_id=%s endpoint=%s text_chars=%s",
                        request_id,
                        settings.avatar_fal_tts_endpoint,
                        len(text),
                    )
                    result = fal_client.subscribe(
                        settings.avatar_fal_tts_endpoint,
                        arguments={
                            "text": text,
                            "voice_setting": {
                                "voice_id": provider_voice_id,
                                "speed": 1.0,
                                "vol": 1.0,
                                "pitch": 0,
                                "english_normalization": language_boost == "English",
                            },
                            "language_boost": language_boost,
                            "output_format": "url",
                        },
                        with_logs=True,
                        on_enqueue=self._fal_enqueue_capture(variables),
                        headers=self._fal_request_headers(),
                        client_timeout=settings.avatar_proprietary_api_timeout_seconds,
                    )

            provider_audio = output_audio.with_name("minimax-provider-audio.mp3")
            self._download_fal_result(result, ("audio",), provider_audio, stage="voice:fal_minimax_voice_clone")
            self._convert_audio_to_wav(provider_audio, output_audio)
            logger.info(
                "MiniMax voice audio prepared request_id=%s output_bytes=%s elapsed_ms=%s",
                request_id,
                output_audio.stat().st_size if output_audio.exists() else 0,
                round((time.monotonic() - started_at) * 1000),
            )
        except LocalAvatarRuntimeError:
            raise
        except Exception as exc:
            raise LocalAvatarRuntimeError(
                f"DalaiLlama Voice Clone failed: {exc}",
                stage="voice:fal_minimax_voice_clone",
                setup_hint="Verify the DalaiLlama managed voice configuration.",
            ) from exc

    def _minimax_language_boost(self, requested_boost: Any, language: Any, language_code: Any) -> str:
        explicit = self._first_text(requested_boost)
        if explicit:
            if self._normalize(explicit) == "auto":
                requested_language = self._normalize(self._first_text(language))
                return "Hindi" if requested_language in {"hinglish", "hindi_english", "mixed_hindi_english"} else "auto"
            supported = {
                "english": "English",
                "hindi": "Hindi",
            }
            mapped = supported.get(self._normalize(explicit))
            if mapped:
                return mapped
        normalized = self._normalize(self._first_text(language))
        if normalized in {"hinglish", "mixed", "multilingual", "auto"}:
            return "auto"
        language_names = {
            "chinese": "Chinese",
            "mandarin": "Chinese",
            "cantonese": "Chinese,Yue",
            "english": "English",
            "arabic": "Arabic",
            "russian": "Russian",
            "spanish": "Spanish",
            "french": "French",
            "portuguese": "Portuguese",
            "german": "German",
            "turkish": "Turkish",
            "dutch": "Dutch",
            "ukrainian": "Ukrainian",
            "vietnamese": "Vietnamese",
            "indonesian": "Indonesian",
            "japanese": "Japanese",
            "italian": "Italian",
            "korean": "Korean",
            "thai": "Thai",
            "polish": "Polish",
            "romanian": "Romanian",
            "greek": "Greek",
            "czech": "Czech",
            "finnish": "Finnish",
            "hindi": "Hindi",
            "bulgarian": "Bulgarian",
            "danish": "Danish",
            "hebrew": "Hebrew",
            "malay": "Malay",
            "slovak": "Slovak",
            "swedish": "Swedish",
            "croatian": "Croatian",
            "hungarian": "Hungarian",
            "norwegian": "Norwegian",
            "slovenian": "Slovenian",
            "catalan": "Catalan",
            "nynorsk": "Nynorsk",
            "afrikaans": "Afrikaans",
        }
        if normalized in language_names:
            return language_names[normalized]
        code_prefix = self._first_text(language_code).lower().split("-", 1)[0]
        code_names = {
            "zh": "Chinese",
            "yue": "Chinese,Yue",
            "en": "English",
            "ar": "Arabic",
            "ru": "Russian",
            "es": "Spanish",
            "fr": "French",
            "pt": "Portuguese",
            "de": "German",
            "tr": "Turkish",
            "nl": "Dutch",
            "uk": "Ukrainian",
            "vi": "Vietnamese",
            "id": "Indonesian",
            "ja": "Japanese",
            "it": "Italian",
            "ko": "Korean",
            "th": "Thai",
            "pl": "Polish",
            "ro": "Romanian",
            "el": "Greek",
            "cs": "Czech",
            "fi": "Finnish",
            "hi": "Hindi",
            "bg": "Bulgarian",
            "da": "Danish",
            "he": "Hebrew",
            "ms": "Malay",
            "sk": "Slovak",
            "sv": "Swedish",
            "hr": "Croatian",
            "hu": "Hungarian",
            "no": "Norwegian",
            "sl": "Slovenian",
            "ca": "Catalan",
            "nn": "Nynorsk",
            "af": "Afrikaans",
        }
        return code_names.get(code_prefix, "auto")

    def _run_fal_happy_horse(
            self,
            source_media: Path,
            output_video: Path,
            prompt: str,
            duration_seconds: int,
            resolution: str = "1080p",
            seed: Any = None,
    ) -> str:
        source_suffix = source_media.suffix.lower()
        portrait_suffix = source_suffix if source_suffix in {".jpg", ".jpeg", ".png", ".webp"} else ".jpg"
        portrait = output_video.with_name(f"founder-happy-horse-source{portrait_suffix}")
        native_video = output_video.with_name("happy-horse-native-audio.mp4")
        self._extract_avatar_portrait(source_media, portrait)
        fal_client = self._fal_client()
        fal_request_id = ""
        duration = max(3, min(15, int(duration_seconds or 5)))
        normalized_resolution = "720p" if self._normalize(resolution) == "720p" else "1080p"

        def on_enqueue(request_id: str) -> None:
            nonlocal fal_request_id
            fal_request_id = self._first_text(request_id)
            logger.info(
                "Fal HappyHorse submitted request_id=%s endpoint=%s duration_seconds=%s resolution=%s",
                fal_request_id,
                settings.avatar_fal_happy_horse_endpoint,
                duration,
                normalized_resolution,
            )

        motion_prompt = self._first_text(
            prompt,
            "A founder looks directly into the camera with natural, restrained head and shoulder movement. "
            "Keep identity, hairstyle, clothing, lighting, background, and camera framing stable.",
        )
        arguments: dict[str, Any] = {
            "image_url": self._fal_upload_file(fal_client, portrait),
            "prompt": (
                f"{motion_prompt} Silent visual performance only: no audible dialogue, no voice-over, "
                "no captions, and no on-screen text. Keep the face front-facing and suitable for later lip sync."
            )[:2000],
            "resolution": normalized_resolution,
            "duration": duration,
            "enable_safety_checker": True,
        }
        if seed is not None:
            try:
                arguments["seed"] = int(seed)
            except (TypeError, ValueError):
                pass
        try:
            result = fal_client.subscribe(
                settings.avatar_fal_happy_horse_endpoint,
                arguments=arguments,
                with_logs=True,
                on_enqueue=on_enqueue,
                headers=self._fal_request_headers(),
                client_timeout=settings.avatar_proprietary_api_timeout_seconds,
            )
            self._download_fal_result(
                result,
                ("video",),
                native_video,
                stage="avatar:fal_happy_horse_v1_1",
            )
            self._strip_video_audio(native_video, output_video)
            logger.info(
                "Fal HappyHorse visual completed request_id=%s native_bytes=%s silent_bytes=%s",
                fal_request_id,
                native_video.stat().st_size,
                output_video.stat().st_size,
            )
            return fal_request_id
        except LocalAvatarRuntimeError:
            raise
        except Exception as exc:
            raise LocalAvatarRuntimeError(
                f"DalaiLlama HappyHorse avatar motion failed: {exc}",
                stage="avatar:fal_happy_horse_v1_1",
                setup_hint="Verify the DalaiLlama managed HappyHorse configuration.",
            ) from exc

    def _run_fal_heygen_avatar4(
            self,
            source_media: Path,
            audio: Path,
            output_video: Path,
            *,
            aspect_ratio: str = "9:16",
            resolution: str = "720p",
            talking_style: str = "stable",
            expression: str = "",
            background: dict[str, Any] | None = None,
            caption: bool = False,
    ) -> str:
        if not audio.exists() or audio.stat().st_size == 0:
            raise LocalAvatarRuntimeError(
                "Approved cloned audio is required for HeyGen Avatar IV.",
                stage="avatar:fal_heygen_avatar4",
            )
        source_suffix = source_media.suffix.lower()
        portrait_suffix = source_suffix if source_suffix in {".jpg", ".jpeg", ".png", ".webp"} else ".jpg"
        portrait = output_video.with_name(f"founder-heygen-source{portrait_suffix}")
        self._extract_avatar_portrait(source_media, portrait)

        allowed_ratios = {"16:9", "9:16", "4:5", "5:4", "1:1", "auto"}
        normalized_ratio = self._first_text(aspect_ratio, "9:16").replace(" ", "")
        if normalized_ratio not in allowed_ratios:
            normalized_ratio = "9:16"
        allowed_resolutions = {"360p", "480p", "540p", "720p", "1080p"}
        normalized_resolution = self._normalize(self._first_text(resolution, "720p"))
        if normalized_resolution not in allowed_resolutions:
            normalized_resolution = "720p"
        normalized_style = "expressive" if self._normalize(talking_style) == "expressive" else "stable"
        clean_expression = self._first_text(expression)
        supported_expression = self._heygen_expression(clean_expression)
        if clean_expression and not supported_expression and normalized_style == "stable":
            normalized_style = "expressive"

        fal_client = self._fal_client()
        fal_request_id = ""

        def on_enqueue(request_id: str) -> None:
            nonlocal fal_request_id
            fal_request_id = self._first_text(request_id)
            logger.info(
                "Fal HeyGen Avatar IV submitted request_id=%s endpoint=%s aspect_ratio=%s resolution=%s style=%s",
                fal_request_id,
                settings.avatar_fal_heygen_avatar4_endpoint,
                normalized_ratio,
                normalized_resolution,
                normalized_style,
            )

        arguments: dict[str, Any] = {
            "image_url": self._fal_upload_file(fal_client, portrait),
            "audio_url": self._fal_upload_file(fal_client, audio),
            "talking_style": normalized_style,
            "resolution": normalized_resolution,
            "aspect_ratio": normalized_ratio,
            "caption": bool(caption),
        }
        if supported_expression:
            arguments["expression"] = supported_expression
        elif clean_expression:
            logger.info(
                "Fal HeyGen Avatar IV omitted unsupported free-form expression expression_chars=%s style=%s",
                len(clean_expression),
                normalized_style,
            )
        clean_background = self._heygen_background(background)
        if clean_background:
            arguments["background"] = clean_background

        try:
            result = fal_client.subscribe(
                settings.avatar_fal_heygen_avatar4_endpoint,
                arguments=arguments,
                with_logs=True,
                on_enqueue=on_enqueue,
                headers=self._fal_request_headers(),
                client_timeout=settings.avatar_proprietary_api_timeout_seconds,
            )
            self._download_fal_result(
                result,
                ("video",),
                output_video,
                stage="avatar:fal_heygen_avatar4",
            )
            logger.info(
                "Fal HeyGen Avatar IV completed request_id=%s output_bytes=%s",
                fal_request_id,
                output_video.stat().st_size,
            )
            return fal_request_id
        except LocalAvatarRuntimeError:
            raise
        except Exception as exc:
            raise LocalAvatarRuntimeError(
                f"HeyGen Avatar IV failed"
                f"{' for fal request ' + fal_request_id if fal_request_id else ''}: {exc}",
                stage="avatar:fal_heygen_avatar4",
                setup_hint="Verify FAL_KEY and AVATAR_FAL_HEYGEN_AVATAR4_ENDPOINT.",
            ) from exc

    def _heygen_expression(self, value: Any) -> str:
        tokens = set(re.findall(r"[a-z]+", self._first_text(value).lower()))
        supported_happy_terms = {
            "happy",
            "joyful",
            "cheerful",
            "smile",
            "smiling",
            "delighted",
            "upbeat",
        }
        return "happy" if tokens.intersection(supported_happy_terms) else ""

    def _heygen_background(self, value: Any) -> dict[str, str]:
        background = self._first_dict(value)
        background_type = self._normalize(background.get("type"))
        background_value = self._first_text(background.get("value"))
        if background_type not in {"color", "image", "video"} or not background_value:
            return {}
        if background_type == "color":
            if not re.fullmatch(r"#[0-9a-fA-F]{6}", background_value):
                return {}
        elif not background_value.lower().startswith(("https://", "http://")):
            return {}
        return {"type": background_type, "value": background_value}

    def _run_fal_echomimic(
            self,
            source_video: Path,
            audio: Path,
            output_video: Path,
            prompt: str,
            seed: Any = None,
    ) -> str:
        portrait = output_video.with_name("founder-avatar-portrait.jpg")
        self._extract_avatar_portrait(source_video, portrait)
        fal_client = self._fal_client()
        fal_request_id = ""

        def on_enqueue(request_id: str) -> None:
            nonlocal fal_request_id
            fal_request_id = self._first_text(request_id)
            logger.info(
                "Fal EchoMimic submitted request_id=%s endpoint=%s",
                fal_request_id,
                settings.avatar_fal_echomimic_endpoint,
            )

        arguments: dict[str, Any] = {
            "image_url": self._fal_upload_file(fal_client, portrait),
            "audio_url": self._fal_upload_file(fal_client, audio),
            "prompt": self._first_text(
                prompt,
                "A founder speaks naturally to camera with restrained gestures, stable identity, consistent lighting, and a clean background.",
            )[:2000],
        }
        if seed is not None:
            try:
                arguments["seed"] = int(seed)
            except (TypeError, ValueError):
                pass
        try:
            result = fal_client.subscribe(
                settings.avatar_fal_echomimic_endpoint,
                arguments=arguments,
                with_logs=True,
                on_enqueue=on_enqueue,
                headers=self._fal_request_headers(),
                client_timeout=settings.avatar_proprietary_api_timeout_seconds,
            )
            self._download_fal_result(result, ("video",), output_video, stage="avatar:fal_echomimic_v3")
            logger.info(
                "Fal EchoMimic completed request_id=%s output_bytes=%s",
                fal_request_id,
                output_video.stat().st_size,
            )
            return fal_request_id
        except LocalAvatarRuntimeError:
            raise
        except Exception as exc:
            raise LocalAvatarRuntimeError(
                f"DalaiLlama Echo Avatar failed: {exc}",
                stage="avatar:fal_echomimic_v3",
                setup_hint="Verify the DalaiLlama managed Echo Avatar configuration.",
            ) from exc

    def _run_fal_liveportrait(self, source_video: Path, output_video: Path) -> None:
        portrait = output_video.with_name("founder-liveportrait-source.jpg")
        self._extract_avatar_portrait(source_video, portrait)
        fal_client = self._fal_client()
        try:
            result = fal_client.subscribe(
                settings.avatar_fal_liveportrait_endpoint,
                arguments={
                    "video_url": self._fal_upload_file(fal_client, source_video),
                    "image_url": self._fal_upload_file(fal_client, portrait),
                    "flag_stitching": True,
                    "flag_relative": True,
                    "flag_pasteback": True,
                },
                with_logs=True,
                headers=self._fal_request_headers(),
                client_timeout=settings.avatar_proprietary_api_timeout_seconds,
            )
            self._download_fal_result(result, ("video",), output_video, stage="avatar:fal_liveportrait")
        except LocalAvatarRuntimeError:
            raise
        except Exception as exc:
            raise LocalAvatarRuntimeError(
                f"DalaiLlama Portrait Motion failed: {exc}",
                stage="avatar:fal_liveportrait",
                setup_hint="Verify the DalaiLlama managed Portrait Motion configuration.",
            ) from exc

    def _run_fal_lipsync(self, video: Path, audio: Path, output_video: Path) -> str:
        if not video.exists() or video.stat().st_size == 0:
            raise LocalAvatarRuntimeError("Creator video is required for DalaiLlama Lip Sync.", stage="lip_sync:fal_latentsync")
        if not audio.exists() or audio.stat().st_size == 0:
            raise LocalAvatarRuntimeError("Dialogue audio is required for DalaiLlama Lip Sync.", stage="lip_sync:fal_latentsync")

        fal_client = self._fal_client()
        fal_request_id = ""

        def on_enqueue(request_id: str) -> None:
            nonlocal fal_request_id
            fal_request_id = self._first_text(request_id)
            logger.info(
                "Fal LatentSync submitted request_id=%s endpoint=%s",
                fal_request_id,
                settings.avatar_fal_lipsync_endpoint,
            )

        try:
            logger.info(
                "Fal LatentSync upload started video_bytes=%s audio_bytes=%s",
                video.stat().st_size,
                audio.stat().st_size,
            )
            video_url = self._fal_upload_file(fal_client, video)
            audio_url = self._fal_upload_file(fal_client, audio)
            logger.info("Fal LatentSync upload completed")
            result = fal_client.subscribe(
                settings.avatar_fal_lipsync_endpoint,
                arguments={
                    "video_url": video_url,
                    "audio_url": audio_url,
                    "guidance_scale": 1,
                    "loop_mode": "loop",
                },
                with_logs=True,
                on_enqueue=on_enqueue,
                headers=self._fal_request_headers(),
                client_timeout=settings.avatar_proprietary_api_timeout_seconds,
            )
            self._download_fal_result(result, ("video",), output_video, stage="lip_sync:fal_latentsync")
            logger.info(
                "Fal LatentSync completed request_id=%s output_bytes=%s",
                fal_request_id,
                output_video.stat().st_size,
            )
            return fal_request_id
        except LocalAvatarRuntimeError:
            raise
        except Exception as exc:
            raise LocalAvatarRuntimeError(
                f"DalaiLlama Lip Sync failed"
                f"{' for fal request ' + fal_request_id if fal_request_id else ''}: {exc}",
                stage="lip_sync:fal_latentsync",
                setup_hint="Verify the DalaiLlama managed lip-sync configuration.",
            ) from exc

    def _run_fal_musetalk(self, video: Path, audio: Path, output_video: Path) -> str:
        if not video.exists() or video.stat().st_size == 0:
            raise LocalAvatarRuntimeError(
                "Creator video is required for DalaiLlama MuseTalk.",
                stage="lip_sync:fal_musetalk",
            )
        if not audio.exists() or audio.stat().st_size == 0:
            raise LocalAvatarRuntimeError(
                "Dialogue audio is required for DalaiLlama MuseTalk.",
                stage="lip_sync:fal_musetalk",
            )

        fal_client = self._fal_client()
        fal_request_id = ""

        def on_enqueue(request_id: str) -> None:
            nonlocal fal_request_id
            fal_request_id = self._first_text(request_id)
            logger.info(
                "Fal MuseTalk submitted request_id=%s endpoint=%s",
                fal_request_id,
                settings.avatar_fal_musetalk_endpoint,
            )

        try:
            logger.info(
                "Fal MuseTalk upload started video_bytes=%s audio_bytes=%s",
                video.stat().st_size,
                audio.stat().st_size,
            )
            source_video_url = self._fal_upload_file(fal_client, video)
            audio_url = self._fal_upload_file(fal_client, audio)
            logger.info("Fal MuseTalk upload completed")
            result = fal_client.subscribe(
                settings.avatar_fal_musetalk_endpoint,
                arguments={
                    "source_video_url": source_video_url,
                    "audio_url": audio_url,
                },
                with_logs=True,
                on_enqueue=on_enqueue,
                headers=self._fal_request_headers(),
                client_timeout=settings.avatar_proprietary_api_timeout_seconds,
            )
            self._download_fal_result(result, ("video",), output_video, stage="lip_sync:fal_musetalk")
            logger.info(
                "Fal MuseTalk completed request_id=%s output_bytes=%s",
                fal_request_id,
                output_video.stat().st_size,
            )
            return fal_request_id
        except LocalAvatarRuntimeError:
            raise
        except Exception as exc:
            raise LocalAvatarRuntimeError(
                f"DalaiLlama MuseTalk failed"
                f"{' for fal request ' + fal_request_id if fal_request_id else ''}: {exc}",
                stage="lip_sync:fal_musetalk",
                setup_hint="Verify FAL_KEY and AVATAR_FAL_MUSETALK_ENDPOINT.",
            ) from exc

    def _run_fal_video_edit(self, video: Path, output_video: Path, prompt: str) -> None:
        fal_client = self._fal_client()
        try:
            result = fal_client.subscribe(
                settings.avatar_fal_video_edit_endpoint,
                arguments={
                    "prompt": prompt,
                    "video_url": self._fal_upload_file(fal_client, video),
                    "keep_audio": False,
                },
                with_logs=True,
                headers=self._fal_request_headers(),
                client_timeout=settings.avatar_proprietary_api_timeout_seconds,
            )
            self._download_fal_result(
                result,
                ("video",),
                output_video,
                stage="production_enhancement:fal_kling_o1_edit",
            )
        except LocalAvatarRuntimeError:
            raise
        except Exception as exc:
            raise LocalAvatarRuntimeError(
                f"DalaiLlama production enhancement failed: {exc}",
                stage="production_enhancement:fal_kling_o1_edit",
                setup_hint="Verify the DalaiLlama managed enhancement configuration.",
            ) from exc

    def _fal_client(self) -> Any:
        api_key = self._first_text(settings.fal_api_key, os.environ.get("FAL_KEY"))
        if not api_key:
            raise LocalAvatarRuntimeError(
                "DalaiLlama managed media is not configured.",
                stage="dalai_llama_managed",
                setup_hint="Configure the DalaiLlama managed media credential in ai-service.",
            )
        os.environ.setdefault("FAL_KEY", api_key)
        try:
            import fal_client
        except ImportError as exc:
            raise LocalAvatarRuntimeError(
                "The DalaiLlama managed media client is unavailable.",
                stage="dalai_llama_managed",
                setup_hint="Install the ai-service requirements and rebuild its image.",
            ) from exc
        return fal_client

    def _fal_upload_file(self, fal_client: Any, path: Path) -> str:
        expiration_seconds = max(60, int(settings.avatar_fal_media_expiration_seconds or 3600))
        lifecycle = fal_client.StorageSettings(expires_in=expiration_seconds)
        return fal_client.upload_file(str(path), lifecycle=lifecycle)

    def _fal_enqueue_capture(self, variables: dict[str, Any]):
        def capture(request_id: str) -> None:
            value = self._first_text(request_id)
            if not value:
                return
            request_ids = list(variables.get("fal_request_ids") or [])
            if value not in request_ids:
                request_ids.append(value)
            variables["fal_request_ids"] = request_ids

        return capture

    def _fal_request_headers(self) -> dict[str, str]:
        return {"X-Fal-Store-IO": "1" if settings.avatar_fal_store_io else "0"}

    def _download_fal_result(
            self,
            result: Any,
            keys: tuple[str, ...],
            output: Path,
            *,
            stage: str,
    ) -> None:
        value = result
        for key in keys:
            if not isinstance(value, dict):
                value = None
                break
            value = value.get(key)
        if isinstance(value, dict):
            value = value.get("url")
        url = self._first_text(value)
        if not url.startswith(("https://", "http://")):
            raise LocalAvatarRuntimeError("DalaiLlama completed without a downloadable media URL.", stage=stage)

        try:
            with httpx.stream(
                    "GET",
                    url,
                    timeout=settings.avatar_download_timeout_seconds,
                    follow_redirects=True,
            ) as response:
                response.raise_for_status()
                with output.open("wb") as target:
                    for chunk in response.iter_bytes():
                        target.write(chunk)
        except Exception as exc:
            raise LocalAvatarRuntimeError(f"Could not download DalaiLlama output: {exc}", stage=stage) from exc
        if not output.exists() or output.stat().st_size == 0:
            raise LocalAvatarRuntimeError("Downloaded DalaiLlama output is empty.", stage=stage)

    def _generate_scene_sync(self, payload: dict[str, Any]) -> dict[str, Any]:
        work_dir = self._new_work_dir("scene", payload)
        source = self._resolve_source_media(payload, work_dir)
        if not source:
            raise LocalAvatarRuntimeError(
                "Founder source video is required for avatar scene generation.",
                stage="scene",
            )
        founder_profile = self._first_dict(payload.get("founderAvatarProfile"), payload.get("founderKit"))

        scene_audio = work_dir / "scene-dialogue.wav"
        audio_from_payload = self._audio_from_payload(payload, scene_audio)
        voice_cost = 0.0
        if not audio_from_payload:
            scene_dialogue = self._scene_dialogue(payload)
            if scene_dialogue:
                voice_response = self._generate_voice_sync({**payload, "text": scene_dialogue})
                self._write_base64_to_file(voice_response.get("audioContent"), scene_audio)
                voice_cost = float(voice_response.get("cost") or 0.0)
            else:
                self._write_silence_audio(scene_audio, int(payload.get("durationSeconds") or 8))

        local_models = self._first_dict(payload.get("localModels"), payload.get("localAvatarModels"))
        source_for_avatar = source.path
        prepared_scene_source = work_dir / "founder-scene-source.mp4"
        source_is_image = source.content_type.startswith("image/") or source.path.suffix.lower() in {
            ".jpg",
            ".jpeg",
            ".png",
            ".webp",
        }
        if not source_is_image:
            try:
                self._prepare_founder_scene_source(
                    source.path,
                    prepared_scene_source,
                    int(payload.get("durationSeconds") or 8),
                    int(payload.get("startSeconds") or 0),
                    self._first_text(payload.get("aspectRatio"), "9:16"),
                )
                source_for_avatar = prepared_scene_source
            except LocalAvatarRuntimeError as exc:
                logger.warning("Founder scene source preparation skipped reason=%s", exc)
        enhancement_status = "skipped"
        enhancement_error = ""
        enhancement_cost = 0.0
        enhancement_enabled = self._boolean(
            payload.get("productionEnhancementEnabled"),
            self._boolean(founder_profile.get("productionEnhancementEnabled"), False),
        )
        enhancement_prompt = self._production_enhancement_prompt(payload, founder_profile)
        if enhancement_enabled:
            prepared_source = work_dir / "founder-production-input.mp4"
            enhanced_source = work_dir / "founder-production-enhanced.mp4"
            edit_duration = max(3, min(10, int(payload.get("durationSeconds") or 8)))
            try:
                self._prepare_fal_video_edit_source(
                    source.path,
                    prepared_source,
                    edit_duration,
                    self._first_text(payload.get("aspectRatio"), "9:16"),
                )
                self._run_fal_video_edit(prepared_source, enhanced_source, enhancement_prompt)
                if not self._is_video_file(enhanced_source):
                    raise LocalAvatarRuntimeError(
                        "DalaiLlama production enhancement returned an invalid video.",
                        stage="production_enhancement",
                    )
                source_for_avatar = enhanced_source
                enhancement_status = "completed"
                enhancement_cost = self._fal_video_edit_cost(edit_duration)
            except LocalAvatarRuntimeError as exc:
                enhancement_status = "skipped_after_error"
                enhancement_error = str(exc)
                logger.warning("Founder production enhancement skipped reason=%s", exc)
        avatar_video = work_dir / "avatar-motion.mp4"
        lip_synced_video = work_dir / "avatar-lipsync.mp4"
        final_video = work_dir / "avatar-final.mp4"
        variables = self._template_variables(payload, work_dir)
        variables.update({
            "source_video": str(source_for_avatar),
            "source_media": str(source_for_avatar),
            "audio": str(scene_audio),
            "dialogue_audio": str(scene_audio),
            "output_video": str(avatar_video),
            "avatar_video": str(avatar_video),
            "lip_synced_video": str(lip_synced_video),
            "final_video": str(final_video),
            "liveportrait_repo": str(self._path(settings.avatar_liveportrait_repo)),
            "echomimic_repo": str(self._path(settings.avatar_echomimic_repo)),
            "musetalk_repo": str(self._path(settings.avatar_musetalk_repo)),
            "latentsync_repo": str(self._path(settings.avatar_latentsync_repo)),
        })

        lip_sync_model = self._first_text(
            payload.get("lipSyncModel"),
            payload.get("lipsyncModel"),
            local_models.get("lipSyncModel"),
            local_models.get("lipsyncModel"),
            settings.avatar_lipsync_model,
            "fal_latentsync",
        )
        normalized_lip_sync_model = self._normalize(lip_sync_model)
        if normalized_lip_sync_model in {"musetalk", "fal_musetalk"}:
            lip_sync_model = "fal_musetalk"
        elif normalized_lip_sync_model in {"latentsync", "latentsync_1_5", "local_latentsync"}:
            lip_sync_model = "fal_latentsync"
        avatar_model = self._normalize(self._first_text(
            payload.get("talkingAvatarModel"),
            local_models.get("talkingAvatarModel"),
            payload.get("model"),
            settings.avatar_talking_model,
            "source_video",
        ))
        avatar_status = "completed"
        avatar_error = ""
        avatar_cost = 0.0
        avatar_duration_seconds = 0.0
        avatar_request_id = ""
        avatar_resolution = self._first_text(
            payload.get("avatarResolution"),
            local_models.get("avatarResolution"),
            "720p" if avatar_model in {"fal_heygen_avatar4", "heygen_avatar4", "heygen_avatar_iv"} else "1080p",
        )
        use_source_video = avatar_model in {"source_video", "uploaded_video", "creator_video"}
        try:
            if use_source_video:
                avatar_status = "source_video"
                variables["avatar_video"] = str(source_for_avatar)
            elif avatar_model in {
                "fal_heygen_avatar4",
                "heygen_avatar4",
                "heygen_avatar_iv",
                "heygen_avatar_4",
            }:
                requested_duration = max(1, min(60, int(payload.get("durationSeconds") or 5)))
                heygen_audio = work_dir / "heygen-dialogue.wav"
                if self._normalize(payload.get("generationMode")) == "avatar_test":
                    self._trim_audio_for_duration(scene_audio, heygen_audio, requested_duration)
                else:
                    shutil.copyfile(scene_audio, heygen_audio)
                variables["audio"] = str(heygen_audio)
                variables["dialogue_audio"] = str(heygen_audio)
                avatar_duration_seconds = self._media_duration_seconds(heygen_audio) or float(requested_duration)
                avatar_request_id = self._run_fal_heygen_avatar4(
                    source_for_avatar,
                    heygen_audio,
                    avatar_video,
                    aspect_ratio=self._first_text(payload.get("aspectRatio"), "9:16"),
                    resolution=avatar_resolution,
                    talking_style=self._first_text(
                        payload.get("talkingStyle"),
                        local_models.get("talkingStyle"),
                        founder_profile.get("talkingStyle"),
                        "stable",
                    ),
                    expression=self._first_text(
                        payload.get("expression"),
                        payload.get("avatarExpression"),
                        founder_profile.get("avatarExpression"),
                    ),
                    background=self._first_dict(
                        payload.get("avatarBackground"),
                        founder_profile.get("avatarBackground"),
                    ),
                    caption=self._boolean(payload.get("avatarCaption"), False),
                )
                avatar_cost = self._fal_heygen_avatar4_cost(avatar_duration_seconds)
                avatar_status = "fal_heygen_avatar4"
                lip_sync_model = "avatar_native"
            elif avatar_model in {
                "fal_happy_horse_v1_1",
                "happy_horse_v1_1",
                "happyhorse_v1_1",
                "happy_horse_1_1",
            }:
                avatar_duration_seconds = float(max(3, min(15, int(payload.get("durationSeconds") or 5))))
                happy_horse_audio = work_dir / "happy-horse-dialogue.wav"
                self._trim_audio_for_duration(
                    scene_audio,
                    happy_horse_audio,
                    int(avatar_duration_seconds),
                )
                variables["audio"] = str(happy_horse_audio)
                variables["dialogue_audio"] = str(happy_horse_audio)
                avatar_request_id = self._run_fal_happy_horse(
                    source_for_avatar,
                    avatar_video,
                    self._first_text(
                        payload.get("avatarMotionPrompt"),
                        payload.get("founderMotionPrompt"),
                        payload.get("prompt"),
                    ),
                    int(avatar_duration_seconds),
                    avatar_resolution,
                    payload.get("seed"),
                )
                avatar_cost = self._fal_happy_horse_cost(avatar_duration_seconds, avatar_resolution)
                avatar_status = "fal_happy_horse_v1_1"
            elif avatar_model in {"fal_echomimic_v3", "echomimic_v3", "echo_mimic_v3"}:
                echo_audio = work_dir / "echomimic-dialogue.wav"
                self._trim_audio_for_duration(
                    scene_audio,
                    echo_audio,
                    max(1, min(60, int(payload.get("durationSeconds") or 5))),
                )
                variables["audio"] = str(echo_audio)
                variables["dialogue_audio"] = str(echo_audio)
                avatar_duration_seconds = self._media_duration_seconds(echo_audio) or float(
                    max(1, min(60, int(payload.get("durationSeconds") or 5)))
                )
                avatar_request_id = self._run_fal_echomimic(
                    source_for_avatar,
                    echo_audio,
                    avatar_video,
                    self._first_text(payload.get("avatarMotionPrompt"), payload.get("founderMotionPrompt"), payload.get("prompt")),
                    payload.get("seed"),
                )
                avatar_cost = self._fal_echomimic_cost(avatar_duration_seconds)
                avatar_status = "fal_echomimic_v3"
            elif avatar_model in {"fal_liveportrait", "fal_live_portrait"}:
                self._run_fal_liveportrait(source_for_avatar, avatar_video)
                avatar_status = "fal_liveportrait"
            elif "echo" in avatar_model and settings.avatar_echomimic_command:
                if not use_source_video:
                    self._run_template_command(settings.avatar_echomimic_command, variables, stage="avatar")
            elif settings.avatar_liveportrait_command:
                if not use_source_video:
                    self._run_template_command(settings.avatar_liveportrait_command, variables, stage="face_motion")
            elif not use_source_video:
                if settings.avatar_allow_test_fallback:
                    self._avatar_test_fallback(source_for_avatar, scene_audio, avatar_video, int(payload.get("durationSeconds") or 8))
                    avatar_status = "test_fallback"
                elif settings.avatar_sync_labs_command or settings.avatar_lipsync_fallback_command:
                    avatar_status = "source_video_fallback"
                    avatar_error = "No local avatar motion command is configured; using founder source video for proprietary lip sync."
                else:
                    raise LocalAvatarRuntimeError(
                        "The selected avatar generation model is not configured.",
                        stage="avatar",
                        setup_hint="Select DalaiLlama Echo Avatar, DalaiLlama Portrait Motion, or uploaded creator video.",
                    )
        except LocalAvatarRuntimeError as exc:
            if settings.avatar_sync_labs_command or settings.avatar_lipsync_fallback_command:
                avatar_status = "source_video_fallback"
                avatar_error = str(exc)
                logger.warning("Avatar motion failed; trying proprietary lip-sync with source video reason=%s", exc)
            else:
                raise

        lip_input = avatar_video if avatar_video.exists() else source_for_avatar
        variables["avatar_video"] = str(lip_input)
        candidate, lip_sync_status, lip_sync_model_used, lip_sync_error, fal_request_id = self._run_lip_sync_stage(
            lip_sync_model,
            variables,
            lip_input,
            lip_synced_video,
            allow_proprietary_fallback=not self._boolean(
                payload.get("manualApprovalRequiredForFallback"),
                True,
            ),
        )
        required_fal_lip_sync = self._normalize(lip_sync_model)
        if required_fal_lip_sync in {"fal_latentsync", "fal_musetalk"} and lip_sync_status != "completed":
            raise LocalAvatarRuntimeError(
                f"Fal {required_fal_lip_sync.removeprefix('fal_')} did not produce a lip-synced preview: "
                f"{lip_sync_error or lip_sync_status}.",
                stage=f"lip_sync:{required_fal_lip_sync}",
            )
        if avatar_status == "source_video_fallback" and lip_sync_status.startswith("skipped"):
            raise LocalAvatarRuntimeError(
                "Avatar motion failed and proprietary lip-sync fallback did not produce a video.",
                stage="scene",
                setup_hint=avatar_error or "Configure AVATAR_SYNC_LABS_COMMAND and AVATAR_SYNC_LABS_API_KEY.",
            )

        if not candidate.exists() or candidate.stat().st_size == 0:
            raise LocalAvatarRuntimeError("Avatar generation completed without a video file.", stage="scene")

        processed = self._postprocess_video(candidate, final_video, payload, variables)
        output = processed if processed.exists() else candidate
        if not self._is_video_file(output):
            raise LocalAvatarRuntimeError("Local avatar output is not a recognized video.", stage="scene")

        duration_seconds = max(1, int(payload.get("durationSeconds") or 8))
        lip_sync_cost = self._fal_lipsync_cost(duration_seconds) if lip_sync_model_used == "fal_latentsync" else 0.0
        total_cost = round(voice_cost + avatar_cost + enhancement_cost + lip_sync_cost, 6)
        provider_interacted = (
            avatar_status in {"fal_heygen_avatar4", "fal_happy_horse_v1_1", "fal_echomimic_v3"}
            or lip_sync_model_used in {"fal_latentsync", "fal_musetalk"}
            or enhancement_status == "completed"
        )
        if avatar_status == "fal_heygen_avatar4":
            billed_model = settings.avatar_fal_heygen_avatar4_endpoint
        elif avatar_status == "fal_happy_horse_v1_1":
            billed_model = settings.avatar_fal_happy_horse_endpoint
            if lip_sync_model_used == "fal_latentsync":
                billed_model = f"{billed_model}+{settings.avatar_fal_lipsync_endpoint}"
            elif lip_sync_model_used == "fal_musetalk":
                billed_model = f"{billed_model}+{settings.avatar_fal_musetalk_endpoint}"
        elif avatar_status == "fal_echomimic_v3":
            billed_model = settings.avatar_fal_echomimic_endpoint
        elif lip_sync_model_used == "fal_musetalk":
            billed_model = settings.avatar_fal_musetalk_endpoint
        elif lip_sync_model_used == "fal_latentsync":
            billed_model = settings.avatar_fal_lipsync_endpoint
        else:
            billed_model = lip_sync_model_used
        stream_output = self._boolean(payload.get("_returnFilePath"), False)
        response = {
            "status": "COMPLETED",
            "provider": "dalai_llama",
            "model": self._first_text(payload.get("talkingAvatarModel"), payload.get("model"), "source_video"),
            "avatarModel": avatar_model,
            "contentType": "video/mp4",
            "outputVideo": {"mimeType": "video/mp4"},
            "videoUrl": "",
            "workDir": str(work_dir),
            "requestId": self._first_text(payload.get("runId"), payload.get("requestId")),
            "sceneId": payload.get("sceneId") or "",
            "sceneNumber": payload.get("sceneNumber") or 1,
            "lipSyncModel": lip_sync_model_used,
            "lipSyncStatus": lip_sync_status,
            "lipSyncError": lip_sync_error,
            "falRequestId": self._first_text(fal_request_id, avatar_request_id),
            "avatarRequestId": avatar_request_id,
            "lipSyncRequestId": fal_request_id,
            "avatarResolution": avatar_resolution if avatar_status in {"fal_heygen_avatar4", "fal_happy_horse_v1_1"} else "",
            "stages": {
                "voice": "completed",
                "productionEnhancement": enhancement_status,
                "avatar": avatar_status,
                "lipSync": lip_sync_status,
                "postProcess": "completed" if output == final_video else "skipped",
            },
            "avatarError": avatar_error,
            "productionEnhancementModel": settings.avatar_fal_video_edit_endpoint if enhancement_enabled else "",
            "productionEnhancementError": enhancement_error,
            "cost": total_cost,
            "usage": {
                "durationSeconds": duration_seconds,
                "voiceCost": voice_cost,
                "avatarDurationSeconds": round(avatar_duration_seconds, 3),
                "avatarCost": avatar_cost,
                "productionEnhancementCost": enhancement_cost,
                "lipSyncCost": lip_sync_cost,
            },
            "costMetadata": self._cost_metadata(
                "fal.ai" if provider_interacted else "dalai_llama",
                billed_model,
                total_cost,
                {
                    "durationSeconds": duration_seconds,
                    "voiceCost": voice_cost,
                    "avatarDurationSeconds": round(avatar_duration_seconds, 3),
                    "avatarCost": avatar_cost,
                    "productionEnhancementCost": enhancement_cost,
                    "lipSyncCost": lip_sync_cost,
                    "falRequestId": self._first_text(fal_request_id, avatar_request_id),
                    "avatarRequestId": avatar_request_id,
                    "lipSyncRequestId": fal_request_id,
                    "falRequestIds": [
                        item
                        for item in (avatar_request_id, fal_request_id)
                        if item
                    ],
                },
            ),
        }
        if stream_output:
            response["outputFilePath"] = str(output)
        else:
            response["outputVideo"]["data"] = self._base64_file(output)
        return response

    def _run_lip_sync_stage(
            self,
            requested_model: str,
            variables: dict[str, str],
            avatar_video: Path,
            lip_synced_video: Path,
            allow_proprietary_fallback: bool = False,
    ) -> tuple[Path, str, str, str, str]:
        selected = self._normalize(requested_model or settings.avatar_lipsync_model or "musetalk")
        if selected in {"avatar_native", "native_avatar", "native", "none", "skip"}:
            return avatar_video, "completed", "avatar_native", "", ""
        if selected in {"", "auto"}:
            preferred = ["fal_latentsync", "fal_musetalk", "musetalk", "latentsync"]
        elif "fal" in selected and "muse" in selected:
            preferred = ["fal_musetalk"]
        elif "fal" in selected and "latent" in selected:
            preferred = ["fal_latentsync"]
        elif selected == "local_musetalk":
            preferred = ["musetalk"]
        elif "latent" in selected:
            preferred = ["latentsync", "musetalk"]
        elif "sync_lab" in selected or selected == "sync_labs":
            preferred = ["sync_labs"]
        elif selected in {"api", "api_fallback", "fallback"}:
            preferred = ["sync_labs", "api_fallback"]
        elif "muse" in selected:
            preferred = ["fal_musetalk"]
        else:
            preferred = [selected, "musetalk", "latentsync"]

        attempted: set[str] = set()
        last_error = ""
        fal_request_id = ""
        for model_name in preferred:
            if model_name in attempted:
                continue
            attempted.add(model_name)
            if lip_synced_video.exists():
                lip_synced_video.unlink()
            variables["lip_sync_model"] = model_name
            variables["lip_sync_input_video"] = str(avatar_video)
            variables["lip_sync_output_video"] = str(lip_synced_video)
            try:
                if model_name == "fal_latentsync":
                    fal_request_id = self._run_fal_lipsync(
                        avatar_video,
                        Path(variables["audio"]),
                        lip_synced_video,
                    )
                elif model_name == "fal_musetalk":
                    fal_request_id = self._run_fal_musetalk(
                        avatar_video,
                        Path(variables["audio"]),
                        lip_synced_video,
                    )
                else:
                    command = self._lip_sync_command(model_name)
                    if not command:
                        continue
                    self._run_template_command(command, variables, stage=f"lip_sync:{model_name}")
                if lip_synced_video.exists() and lip_synced_video.stat().st_size > 0:
                    return lip_synced_video, "completed", model_name, "", fal_request_id
                last_error = f"{model_name} completed without writing {lip_synced_video.name}."
            except LocalAvatarRuntimeError as exc:
                last_error = str(exc)
                logger.warning("Local lip-sync model failed model=%s reason=%s", model_name, exc)

        if allow_proprietary_fallback and "sync_labs" not in attempted and settings.avatar_sync_labs_command:
            if lip_synced_video.exists():
                lip_synced_video.unlink()
            variables["lip_sync_model"] = "sync_labs"
            variables["lip_sync_input_video"] = str(avatar_video)
            variables["lip_sync_output_video"] = str(lip_synced_video)
            try:
                self._run_template_command(settings.avatar_sync_labs_command, variables, stage="lip_sync:sync_labs")
                if lip_synced_video.exists() and lip_synced_video.stat().st_size > 0:
                    return lip_synced_video, "fallback_completed", "sync_labs", last_error, fal_request_id
                last_error = last_error or f"sync_labs completed without writing {lip_synced_video.name}."
            except LocalAvatarRuntimeError as exc:
                last_error = str(exc)
                logger.warning("Sync Labs lip-sync fallback failed reason=%s", exc)

        if allow_proprietary_fallback and "api_fallback" not in attempted and settings.avatar_lipsync_fallback_command:
            if lip_synced_video.exists():
                lip_synced_video.unlink()
            variables["lip_sync_model"] = "api_fallback"
            variables["lip_sync_input_video"] = str(avatar_video)
            variables["lip_sync_output_video"] = str(lip_synced_video)
            try:
                self._run_template_command(settings.avatar_lipsync_fallback_command, variables, stage="lip_sync:api_fallback")
                if lip_synced_video.exists() and lip_synced_video.stat().st_size > 0:
                    return lip_synced_video, "fallback_completed", "api_fallback", last_error, fal_request_id
                last_error = last_error or f"api_fallback completed without writing {lip_synced_video.name}."
            except LocalAvatarRuntimeError as exc:
                last_error = str(exc)
                logger.warning("Lip-sync API fallback failed reason=%s", exc)

        skipped_model = selected or "musetalk"
        if last_error:
            return avatar_video, "skipped_after_error", skipped_model, last_error, fal_request_id
        return avatar_video, "skipped", skipped_model, "", fal_request_id

    def _lip_sync_command(self, model_name: str) -> str:
        normalized = self._normalize(model_name)
        if normalized == "musetalk":
            return settings.avatar_musetalk_command
        if normalized == "latentsync":
            return settings.avatar_latentsync_command
        if normalized == "sync_labs":
            return settings.avatar_sync_labs_command
        if normalized == "api_fallback":
            return settings.avatar_lipsync_fallback_command or settings.avatar_sync_labs_command
        return ""

    def _is_proprietary_voice_model(self, value: str) -> bool:
        normalized = self._normalize(value)
        return (
            "fal" in normalized
            or "f5" in normalized
            or "eleven" in normalized
            or "sarvam" in normalized
            or normalized in {"api", "api_fallback", "fallback", "proprietary", "proprietary_api"}
        )

    def _lip_sync_sync(self, payload: dict[str, Any]) -> dict[str, Any]:
        work_dir = self._new_work_dir("lip-sync", payload)
        source = self._resolve_source_media(payload, work_dir)
        if not source or not source.content_type.startswith("video/"):
            raise LocalAvatarRuntimeError("Uploaded source video is required for direct lip sync.", stage="lip_sync")

        audio_file = work_dir / "dialogue.wav"
        if not self._audio_from_payload(payload, audio_file):
            raise LocalAvatarRuntimeError("Uploaded dialogue audio is required for direct lip sync.", stage="lip_sync")

        output_video = work_dir / "lip-sync-output.mp4"
        variables = self._template_variables(payload, work_dir)
        variables.update({
            "source_video": str(source.path),
            "source_media": str(source.path),
            "avatar_video": str(source.path),
            "audio": str(audio_file),
            "dialogue_audio": str(audio_file),
            "lip_synced_video": str(output_video),
            "lip_sync_output_video": str(output_video),
        })
        local_models = self._first_dict(payload.get("localModels"), payload.get("localAvatarModels"))
        lip_sync_model = self._first_text(
            payload.get("lipSyncModel"),
            payload.get("lipsyncModel"),
            local_models.get("lipSyncModel"),
            local_models.get("lipsyncModel"),
            payload.get("model"),
            settings.avatar_lipsync_model,
            "sync_labs",
        )
        candidate, status, model_used, error, fal_request_id = self._run_lip_sync_stage(
            lip_sync_model,
            variables,
            source.path,
            output_video,
            allow_proprietary_fallback=not self._boolean(
                payload.get("manualApprovalRequiredForFallback"),
                True,
            ),
        )
        if not candidate.exists() or candidate.stat().st_size == 0 or candidate == source.path:
            raise LocalAvatarRuntimeError(
                "Direct lip-sync did not produce a video.",
                stage="lip_sync",
                setup_hint=error or "Configure AVATAR_SYNC_LABS_COMMAND and AVATAR_SYNC_LABS_API_KEY, or MuseTalk/LatentSync.",
            )
        fal_lip_sync = model_used in {"fal_latentsync", "fal_musetalk"}
        lip_sync_cost = self._fal_lipsync_cost(
            int(payload.get("durationSeconds") or 8)
        ) if model_used == "fal_latentsync" else 0.0
        billed_model = (
            settings.avatar_fal_musetalk_endpoint
            if model_used == "fal_musetalk"
            else settings.avatar_fal_lipsync_endpoint
            if model_used == "fal_latentsync"
            else model_used
        )
        return {
            "status": "COMPLETED",
            "provider": "dalai_llama",
            "model": model_used,
            "lipSyncModel": model_used,
            "lipSyncStatus": status,
            "lipSyncError": error,
            "falRequestId": fal_request_id,
            "contentType": "video/mp4",
            "outputVideo": {"data": self._base64_file(candidate), "mimeType": "video/mp4"},
            "videoUrl": "",
            "workDir": str(work_dir),
            "sceneId": payload.get("sceneId") or "",
            "sceneNumber": payload.get("sceneNumber") or 1,
            "cost": lip_sync_cost,
            "usage": {"durationSeconds": int(payload.get("durationSeconds") or 8)},
            "costMetadata": self._cost_metadata(
                "fal.ai" if fal_lip_sync else "dalai_llama",
                billed_model,
                lip_sync_cost,
                {"durationSeconds": int(payload.get("durationSeconds") or 8)},
            ),
        }

    def _transcribe_sync(self, payload: dict[str, Any]) -> dict[str, Any]:
        work_dir = self._new_work_dir("stt", payload)
        source = self._resolve_source_media(payload, work_dir)
        audio_file = work_dir / "input.wav"
        if source and source.content_type.startswith("video/"):
            self._extract_enhanced_audio(source.path, audio_file)
        elif source:
            audio_file = source.path
        else:
            audio_payload = payload.get("audioContent") or payload.get("audioBase64")
            self._write_base64_to_file(audio_payload, audio_file)
        if not audio_file.exists():
            raise LocalAvatarRuntimeError("Audio/video input is required for STT.", stage="stt")

        segments, language = self._faster_whisper(audio_file, payload)
        srt = self._segments_to_srt(segments)
        return {
            "status": "COMPLETED",
            "provider": "dalai_llama",
            "model": "faster_whisper",
            "engine": "whisperx" if settings.avatar_caption_engine == "whisperx" else "faster_whisper",
            "language": language,
            "segments": segments,
            "srt": srt,
            "srtFile": {
                "filename": "captions.srt",
                "contentType": "application/x-subrip",
                "cueCount": len(segments),
                "content": srt,
            },
        }

    def _generate_image_sync(self, payload: dict[str, Any]) -> dict[str, Any]:
        image_model = self._normalize(self._first_text(payload.get("imageModel"), settings.avatar_image_model))
        if image_model in {"gemini", "gemini_storyboard", "storyboard"}:
            raise LocalAvatarRuntimeError(
                "Gemini storyboard image generation is handled by creator-service, not the local avatar runtime.",
                stage="images",
                setup_hint="Use the existing screenplay storyboard/reference-image flow, or configure AVATAR_FLUX_COMMAND for local Flux.",
            )
        work_dir = self._new_work_dir("image", payload)
        output_image = work_dir / "flux-output.png"
        variables = self._template_variables(payload, work_dir)
        variables.update({
            "prompt": self._first_text(payload.get("prompt")),
            "prompt_file": str(work_dir / "prompt.txt"),
            "output_image": str(output_image),
            "flux_model_id": settings.avatar_flux_model_id,
        })
        Path(variables["prompt_file"]).write_text(variables["prompt"], encoding="utf-8")
        if settings.avatar_flux_command:
            self._run_template_command(settings.avatar_flux_command, variables, stage="images")
        else:
            self._try_flux_python(payload, output_image)
        if not output_image.exists() or output_image.stat().st_size == 0:
            raise LocalAvatarRuntimeError(
                "Flux image generation is not configured.",
                stage="images",
                setup_hint="FLUX.1-dev needs diffusers plus accepted Hugging Face license/token.",
            )
        return {
            "status": "COMPLETED",
            "provider": "dalai_llama",
            "model": "flux_1_dev",
            "contentType": "image/png",
            "image": {"data": self._base64_file(output_image), "mimeType": "image/png"},
            "workDir": str(work_dir),
        }

    def _postprocess_image_sync(self, payload: dict[str, Any]) -> dict[str, Any]:
        work_dir = self._new_work_dir("postprocess", payload)
        source = self._resolve_source_media(payload, work_dir)
        if not source:
            raise LocalAvatarRuntimeError("Image input is required.", stage="postprocess")
        current = source.path
        steps: list[str] = []
        if payload.get("backgroundRemoval") is not False:
            removed = work_dir / "background-removed.png"
            if self._background_remove_image(current, removed, payload, work_dir):
                current = removed
                steps.append("birefnet")
        if payload.get("upscale") is not False:
            upscaled = work_dir / "upscaled.png"
            if self._upscale_image(current, upscaled, payload, work_dir):
                current = upscaled
                steps.append("real_esrgan")
        if payload.get("faceRestoration") is not False:
            restored = work_dir / "face-restored.png"
            if self._restore_face(current, restored, payload, work_dir):
                current = restored
                steps.append("codeformer")
        return {
            "status": "COMPLETED",
            "provider": "dalai_llama",
            "model": "+".join(steps) or "passthrough",
            "contentType": "image/png",
            "image": {"data": self._base64_file(current), "mimeType": "image/png"},
            "steps": steps,
            "workDir": str(work_dir),
        }

    def _try_cosyvoice_python(self, text: str, prompt_wav: Path, output_audio: Path, payload: dict[str, Any]) -> None:
        model_dir = self._cosyvoice_model_dir()
        if not model_dir.exists() or not prompt_wav.exists():
            return
        try:
            import sys
            import torch
            import torchaudio
            repo = self._path(settings.avatar_cosyvoice_repo)
            if repo.exists():
                sys.path.insert(0, str(repo))
                sys.path.insert(0, str(repo / "third_party" / "Matcha-TTS"))
            from cosyvoice.cli.cosyvoice import CosyVoice2
        except Exception as exc:  # noqa: BLE001
            logger.info("CosyVoice2 python API unavailable: %s", exc)
            return

        prompt_text = self._first_text(payload.get("promptText"), payload.get("referenceTranscript"))
        if not prompt_text:
            try:
                stt = self._faster_whisper(prompt_wav, {"languageCode": payload.get("languageCode")})
                prompt_text = " ".join(segment["text"] for segment in stt[0])[:300]
            except Exception:
                prompt_text = "Natural Hinglish founder speech."
        try:
            cosyvoice = CosyVoice2(str(model_dir), load_jit=True, load_trt=False, load_vllm=False, fp16=False)
            chunks = cosyvoice.inference_zero_shot(text, prompt_text, str(prompt_wav), stream=False)
            for item in chunks:
                speech = item.get("tts_speech") if isinstance(item, dict) else None
                if speech is not None:
                    torchaudio.save(str(output_audio), speech, getattr(cosyvoice, "sample_rate", 22050))
                    break
            if torch.cuda.is_available():
                torch.cuda.empty_cache()
        except Exception as exc:  # noqa: BLE001
            logger.warning("CosyVoice2 python inference failed: %s", exc)

    def _try_flux_python(self, payload: dict[str, Any], output_image: Path) -> None:
        try:
            import torch
            from diffusers import FluxPipeline
        except Exception as exc:  # noqa: BLE001
            logger.info("Flux python API unavailable: %s", exc)
            return
        try:
            pipe = FluxPipeline.from_pretrained(
                settings.avatar_flux_model_id,
                torch_dtype=torch.bfloat16 if torch.cuda.is_available() else torch.float32,
            )
            if torch.cuda.is_available():
                pipe.enable_model_cpu_offload()
            prompt = self._first_text(payload.get("prompt"), "Founder-led product video key visual")
            image = pipe(
                prompt,
                height=int(payload.get("height") or 768),
                width=int(payload.get("width") or 432),
                guidance_scale=float(payload.get("guidanceScale") or 3.5),
                num_inference_steps=int(payload.get("steps") or 28),
            ).images[0]
            image.save(output_image)
        except Exception as exc:  # noqa: BLE001
            logger.warning("Flux python inference failed: %s", exc)

    def _background_remove_image(self, input_path: Path, output_path: Path, payload: dict[str, Any], work_dir: Path) -> bool:
        variables = self._template_variables(payload, work_dir)
        variables.update({"input_image": str(input_path), "output_image": str(output_path)})
        if settings.avatar_birefnet_command:
            self._run_template_command(settings.avatar_birefnet_command, variables, stage="background_removal")
            return output_path.exists() and output_path.stat().st_size > 0
        try:
            from rembg import remove
            from PIL import Image
        except Exception:
            return False
        with Image.open(input_path) as image:
            remove(image).save(output_path)
        return output_path.exists() and output_path.stat().st_size > 0

    def _upscale_image(self, input_path: Path, output_path: Path, payload: dict[str, Any], work_dir: Path) -> bool:
        variables = self._template_variables(payload, work_dir)
        variables.update({
            "input_image": str(input_path),
            "output_image": str(output_path),
            "realesrgan_model": settings.avatar_realesrgan_model,
        })
        if settings.avatar_realesrgan_command:
            self._run_template_command(settings.avatar_realesrgan_command, variables, stage="upscaling")
            return output_path.exists() and output_path.stat().st_size > 0
        executable = self._first_text(settings.avatar_realesrgan_executable, shutil.which("realesrgan-ncnn-vulkan"))
        if executable:
            subprocess.run(
                [executable, "-i", str(input_path), "-o", str(output_path), "-n", settings.avatar_realesrgan_model],
                check=True,
                capture_output=True,
                text=True,
                timeout=settings.avatar_stage_timeout_seconds,
            )
            return output_path.exists() and output_path.stat().st_size > 0
        return False

    def _restore_face(self, input_path: Path, output_path: Path, payload: dict[str, Any], work_dir: Path) -> bool:
        variables = self._template_variables(payload, work_dir)
        variables.update({
            "input_image": str(input_path),
            "output_image": str(output_path),
            "codeformer_repo": str(self._path(settings.avatar_codeformer_repo)),
        })
        if not settings.avatar_codeformer_command:
            return False
        self._run_template_command(settings.avatar_codeformer_command, variables, stage="face_restoration")
        return output_path.exists() and output_path.stat().st_size > 0

    def _postprocess_video(self, input_video: Path, output_video: Path, payload: dict[str, Any], variables: dict[str, str]) -> Path:
        current = input_video
        for stage_name, command in (
            ("background_removal", settings.avatar_video_background_removal_command),
            ("upscaling", settings.avatar_video_upscale_command),
            ("face_restoration", settings.avatar_video_face_restore_command),
        ):
            if not command:
                continue
            stage_output = output_video.with_name(f"{stage_name}.mp4")
            variables.update({"input_video": str(current), "output_video": str(stage_output), "final_video": str(stage_output)})
            self._run_template_command(command, variables, stage=stage_name)
            if stage_output.exists() and stage_output.stat().st_size > 0:
                current = stage_output
        if current != input_video:
            shutil.copyfile(current, output_video)
            return output_video
        return input_video

    def _faster_whisper(self, audio_file: Path, payload: dict[str, Any]) -> tuple[list[dict[str, Any]], str]:
        try:
            from faster_whisper import WhisperModel
        except Exception as exc:  # noqa: BLE001
            raise LocalAvatarRuntimeError("faster-whisper is not installed.", stage="stt") from exc
        device = self._first_text(payload.get("device"), settings.avatar_stt_device, "cuda")
        compute_type = self._first_text(payload.get("computeType"), settings.avatar_stt_compute_type, "float16")
        language = self._language_code(payload)
        try:
            model = WhisperModel(settings.avatar_stt_model, device=device, compute_type=compute_type)
        except Exception:
            model = WhisperModel(settings.avatar_stt_model, device="cpu", compute_type="int8")
        segments_iter, info = model.transcribe(
            str(audio_file),
            language=language,
            beam_size=5,
            vad_filter=True,
            word_timestamps=True,
        )
        segments: list[dict[str, Any]] = []
        for segment in segments_iter:
            words = []
            for word in segment.words or []:
                words.append({"start": float(word.start), "end": float(word.end), "word": word.word})
            segments.append({
                "start": float(segment.start),
                "end": float(segment.end),
                "text": segment.text.strip(),
                "words": words,
            })
        return segments, getattr(info, "language", language or "")

    def _segments_to_srt(self, segments: list[dict[str, Any]]) -> str:
        rows: list[str] = []
        for index, segment in enumerate(segments, start=1):
            rows.append(str(index))
            rows.append(f"{self._srt_time(segment['start'])} --> {self._srt_time(segment['end'])}")
            rows.append(segment["text"])
            rows.append("")
        return "\n".join(rows).strip() + ("\n" if rows else "")

    def _resolve_source_media(self, payload: dict[str, Any], work_dir: Path) -> RuntimeFile | None:
        streamed_path = self._first_text(payload.get("_sourceFilePath"))
        if streamed_path:
            path = Path(streamed_path).expanduser().resolve()
            if not path.is_file() or path.stat().st_size == 0:
                raise LocalAvatarRuntimeError(
                    "Streamed founder source video is unavailable.",
                    stage="media:stream",
                )
            content_type = self._first_text(
                payload.get("_sourceFileContentType"),
                payload.get("sourceContentType"),
                "video/mp4",
            )
            return RuntimeFile(path, content_type)

        inline = self._first_text(payload.get("sourceContent"), payload.get("sourceBase64"))
        if inline:
            content_type = self._first_text(payload.get("sourceContentType"), "video/mp4")
            fallback_suffix = ".mp4" if "video" in content_type else ".wav"
            path = work_dir / f"source{self._suffix_for_content_type(content_type, fallback_suffix)}"
            self._write_base64_to_file(inline, path)
            return RuntimeFile(path, content_type)

        profile = self._first_dict(payload.get("founderAvatarProfile"), payload.get("founderKit"))
        source_asset = self._first_dict(payload.get("sourceAsset"), profile.get("sourceAsset"), profile.get("source_asset"))
        source = self._first_text(
            payload.get("sourceUrl"),
            payload.get("source_url"),
            payload.get("sourceVideoUrl"),
            payload.get("sourceAudioUrl"),
            source_asset.get("signedUrl"),
            source_asset.get("publicUrl"),
            source_asset.get("assetUrl"),
            source_asset.get("url"),
            profile.get("sourceUrl"),
            profile.get("source_url"),
            profile.get("signedUrl"),
            profile.get("publicUrl"),
        )
        if not source:
            return None

        if source.startswith("http://") or source.startswith("https://"):
            suffix = self._suffix_from_url(source)
            path = work_dir / f"source{suffix}"
            try:
                with httpx.stream("GET", source, timeout=settings.avatar_download_timeout_seconds, follow_redirects=True) as response:
                    response.raise_for_status()
                    with path.open("wb") as file:
                        for chunk in response.iter_bytes():
                            file.write(chunk)
                    content_type = response.headers.get("content-type", "")
            except Exception as exc:
                raise LocalAvatarRuntimeError(
                    f"Could not download uploaded founder media: {exc}",
                    stage="media:download",
                    setup_hint="Send the creator reference through the /creator/avatar/voice/files multipart endpoint.",
                ) from exc
            return RuntimeFile(path, self._content_type(path, content_type))

        path = Path(source).expanduser()
        if path.exists():
            return RuntimeFile(path.resolve(), self._content_type(path, ""))
        return None

    def _audio_from_payload(self, payload: dict[str, Any], output: Path) -> bool:
        streamed_path = self._first_text(payload.get("_audioFilePath"))
        if streamed_path:
            source = Path(streamed_path).expanduser().resolve()
            if not source.is_file() or source.stat().st_size == 0:
                raise LocalAvatarRuntimeError(
                    "Streamed founder dialogue audio is unavailable.",
                    stage="voice:stream",
                )
            content_type = self._first_text(
                payload.get("_audioFileContentType"),
                payload.get("audioContentType"),
                "audio/wav",
            )
            if "wav" in content_type.lower():
                shutil.copyfile(source, output)
            else:
                self._convert_audio_to_wav(source, output)
            return output.exists() and output.stat().st_size > 0

        encoded = self._first_text(
            payload.get("audioContent"),
            payload.get("audioBase64"),
            self._first_dict(payload.get("audio"), payload.get("dialogueAudio")).get("audioContent"),
        )
        if encoded:
            content_type = self._first_text(payload.get("audioContentType"), payload.get("audioMimeType"))
            if content_type and "wav" not in content_type.lower():
                raw_audio = output.with_suffix(self._suffix_for_content_type(content_type, ".audio"))
                self._write_base64_to_file(encoded, raw_audio)
                self._convert_audio_to_wav(raw_audio, output)
            else:
                self._write_base64_to_file(encoded, output)
            return output.exists()
        profile = self._first_dict(payload.get("founderAvatarProfile"), payload.get("founderKit"))
        exact_audio_asset = self._first_dict(
            payload.get("exactFounderAudioAsset"),
            profile.get("exactFounderAudioAsset"),
            profile.get("finalFounderAudioAsset"),
        )
        audio_url = self._first_text(
            payload.get("audioUrl"),
            payload.get("finalFounderAudioUrl"),
            exact_audio_asset.get("signedUrl"),
            exact_audio_asset.get("assetUrl"),
            exact_audio_asset.get("publicUrl"),
            profile.get("finalFounderAudioUrl"),
        )
        if audio_url.startswith(("http://", "https://")):
            raw_audio = output.with_suffix(self._suffix_from_url(audio_url))
            try:
                with httpx.stream(
                    "GET",
                    audio_url,
                    timeout=settings.avatar_download_timeout_seconds,
                    follow_redirects=True,
                ) as response:
                    response.raise_for_status()
                    with raw_audio.open("wb") as target:
                        for chunk in response.iter_bytes():
                            target.write(chunk)
                self._convert_audio_segment_to_wav(
                    raw_audio,
                    output,
                    float(payload.get("startSeconds") or 0),
                    float(payload.get("durationSeconds") or 0),
                )
            except Exception as exc:
                raise LocalAvatarRuntimeError(
                    f"Could not prepare uploaded founder audio: {exc}",
                    stage="voice:uploaded_founder_audio",
                ) from exc
            return output.exists() and output.stat().st_size > 0
        return False

    def _scene_dialogue(self, payload: dict[str, Any]) -> str:
        return self._first_text(payload.get("exactDialogue"), payload.get("dialogueScript"), payload.get("text"), payload.get("prompt"))

    def _reference_prompt_text(self, payload: dict[str, Any], prompt_wav: Path) -> str:
        prompt_text = self._first_text(payload.get("promptText"), payload.get("referenceTranscript"))
        if prompt_text or not prompt_wav.exists():
            return prompt_text or "Natural Hinglish founder speech."
        try:
            segments, _language = self._faster_whisper(prompt_wav, {"languageCode": payload.get("languageCode")})
            prompt_text = " ".join(segment["text"] for segment in segments).strip()
        except Exception:
            prompt_text = ""
        return prompt_text[:4000] if prompt_text else "Natural Hinglish founder speech."

    def _apply_pronunciation_guide(self, text: str, guide: Any) -> str:
        result = self._first_text(text)
        if not result or not guide:
            return result
        replacements: list[tuple[str, str]] = []
        if isinstance(guide, dict):
            replacements = [(str(key).strip(), str(value).strip()) for key, value in guide.items()]
        else:
            for row in str(guide).splitlines():
                separator = "=>" if "=>" in row else "=" if "=" in row else ""
                if not separator:
                    continue
                source, target = row.split(separator, 1)
                replacements.append((source.strip(), target.strip()))
        for source, target in replacements:
            if source and target:
                result = result.replace(source, target)
        return result

    def _master_voice_output(self, output_audio: Path) -> dict[str, Any]:
        profile = "studio_voice_v1"
        mastered_audio = output_audio.with_name(f"{output_audio.stem}-mastered.wav")
        command = [
            "ffmpeg",
            "-y",
            "-i",
            str(output_audio),
            "-vn",
            "-ac",
            "1",
            "-ar",
            "48000",
            "-af",
            (
                "highpass=f=70,lowpass=f=16000,afftdn=nf=-32,"
                "acompressor=threshold=0.125:ratio=2.5:attack=20:release=150:makeup=2,"
                "loudnorm=I=-16:TP=-1.0:LRA=8"
            ),
            "-c:a",
            "pcm_s16le",
            str(mastered_audio),
        ]
        try:
            subprocess.run(
                command,
                check=True,
                capture_output=True,
                text=True,
                timeout=settings.avatar_stage_timeout_seconds,
            )
            if not mastered_audio.exists() or mastered_audio.stat().st_size < 1000:
                raise RuntimeError("mastered audio was empty")
            mastered_audio.replace(output_audio)
            logger.info(
                "Founder voice mastering completed profile=%s output_bytes=%s",
                profile,
                output_audio.stat().st_size,
            )
            return {
                "status": "completed",
                "applied": True,
                "profile": profile,
                "sampleRateHz": 48000,
                "channels": 1,
                "integratedLoudnessLufs": -16,
                "truePeakDb": -1.0,
                "noiseReduction": True,
                "compression": True,
            }
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired, OSError, RuntimeError) as exc:
            if mastered_audio.exists():
                mastered_audio.unlink(missing_ok=True)
            logger.warning("Founder voice mastering skipped profile=%s reason=%s", profile, exc)
            return {
                "status": "skipped_after_error",
                "applied": False,
                "profile": profile,
                "error": str(exc),
            }

    def _prepare_rvc_source_audio(self, source: Path, output_wav: Path) -> dict[str, Any]:
        command = [
            "ffmpeg",
            "-y",
            "-i",
            str(source),
            "-vn",
            "-ac",
            "1",
            "-ar",
            "48000",
            "-af",
            "highpass=f=60,lowpass=f=17000,loudnorm=I=-20:TP=-1.5:LRA=11",
            "-c:a",
            "pcm_s16le",
            str(output_wav),
        ]
        try:
            subprocess.run(
                command,
                check=True,
                capture_output=True,
                text=True,
                timeout=settings.avatar_stage_timeout_seconds,
            )
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
            raise LocalAvatarRuntimeError(
                f"Could not prepare English source speech for the client voice adapter: {exc}",
                stage="voice:client_rvc_english",
            ) from exc
        duration = self._media_duration_seconds(output_wav)
        if not output_wav.exists() or output_wav.stat().st_size < 1000:
            raise LocalAvatarRuntimeError(
                "Prepared English source speech is empty.",
                stage="voice:client_rvc_english",
            )
        return {
            "selectionMode": "desired_speech_source",
            "durationSeconds": round(duration, 3),
            "channels": 1,
            "sampleRateHz": 48000,
            "noiseReduction": False,
            "silenceRemoval": False,
            "meetsRecommendedMinimum": True,
        }

    def _extract_voice_reference_audio(self, source: Path, output_wav: Path) -> dict[str, Any]:
        command = [
            "ffmpeg",
            "-y",
            "-i",
            str(source),
            "-vn",
            "-t",
            "90",
            "-ac",
            "1",
            "-ar",
            "24000",
            "-af",
            (
                "highpass=f=80,lowpass=f=9000,afftdn=nf=-25,"
                "silenceremove=start_periods=1:start_silence=0.2:start_threshold=-45dB:"
                "stop_periods=-1:stop_silence=0.35:stop_threshold=-45dB,"
                "loudnorm=I=-16:TP=-1.5:LRA=11"
            ),
            str(output_wav),
        ]
        try:
            subprocess.run(
                command,
                check=True,
                capture_output=True,
                text=True,
                timeout=settings.avatar_stage_timeout_seconds,
            )
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
            logger.warning("Speech-focused voice reference preparation failed; using standard enhancement reason=%s", exc)
            self._extract_enhanced_audio(source, output_wav)
        duration = self._media_duration_seconds(output_wav)
        if not output_wav.exists() or output_wav.stat().st_size == 0:
            raise LocalAvatarRuntimeError("Founder reference did not contain usable speech audio.", stage="voice:reference")
        return {
            "selectionMode": "speech_focused_silence_trimmed",
            "durationSeconds": round(duration, 3),
            "minimumRecommendedSeconds": 45,
            "maximumSeconds": 90,
            "meetsRecommendedMinimum": duration >= 45,
            "channels": 1,
            "sampleRateHz": 24000,
            "noiseReduction": True,
            "silenceRemoval": True,
        }

    def _media_duration_seconds(self, source: Path) -> float:
        if not source.exists():
            return 0.0
        try:
            completed = subprocess.run(
                [
                    "ffprobe",
                    "-v",
                    "error",
                    "-show_entries",
                    "format=duration",
                    "-of",
                    "default=noprint_wrappers=1:nokey=1",
                    str(source),
                ],
                check=True,
                capture_output=True,
                text=True,
                timeout=min(settings.avatar_stage_timeout_seconds, 60),
            )
            return max(0.0, float(completed.stdout.strip() or 0))
        except (ValueError, subprocess.CalledProcessError, subprocess.TimeoutExpired):
            return 0.0

    def _extract_enhanced_audio(self, source: Path, output_wav: Path) -> None:
        command = [
            "ffmpeg",
            "-y",
            "-i",
            str(source),
            "-vn",
            "-ac",
            "1",
            "-ar",
            "24000",
            "-af",
            "highpass=f=80,lowpass=f=9000,afftdn=nf=-25,loudnorm=I=-16:TP=-1.5:LRA=11",
            str(output_wav),
        ]
        subprocess.run(command, check=True, capture_output=True, text=True, timeout=settings.avatar_stage_timeout_seconds)

    def _trim_audio_for_duration(self, source: Path, output_wav: Path, duration_seconds: int) -> None:
        command = [
            "ffmpeg",
            "-y",
            "-i",
            str(source),
            "-t",
            str(max(1, min(60, duration_seconds))),
            "-ac",
            "1",
            "-ar",
            "24000",
            "-c:a",
            "pcm_s16le",
            str(output_wav),
        ]
        try:
            subprocess.run(
                command,
                check=True,
                capture_output=True,
                text=True,
                timeout=settings.avatar_stage_timeout_seconds,
            )
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
            detail = getattr(exc, "stderr", "") or str(exc)
            raise LocalAvatarRuntimeError(
                f"Could not prepare the capped avatar-test audio: {detail[-600:]}",
                stage="avatar:audio",
            ) from exc
        if not output_wav.exists() or output_wav.stat().st_size == 0:
            raise LocalAvatarRuntimeError(
                "Avatar-test audio preparation completed without audio.",
                stage="avatar:audio",
            )

    def _strip_video_audio(self, source_video: Path, output_video: Path) -> None:
        if not source_video.exists() or source_video.stat().st_size == 0:
            raise LocalAvatarRuntimeError(
                "HappyHorse completed without a downloadable video.",
                stage="avatar:happy_horse_silence",
            )
        commands = (
            [
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-i", str(source_video),
                "-map", "0:v:0", "-c:v", "copy", "-an",
                "-movflags", "+faststart", str(output_video),
            ],
            [
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-i", str(source_video),
                "-map", "0:v:0", "-c:v", "libx264", "-preset", "veryfast",
                "-crf", "18", "-pix_fmt", "yuv420p", "-an",
                "-movflags", "+faststart", str(output_video),
            ],
        )
        last_detail = ""
        for command in commands:
            output_video.unlink(missing_ok=True)
            try:
                subprocess.run(
                    command,
                    check=True,
                    capture_output=True,
                    text=True,
                    timeout=settings.avatar_stage_timeout_seconds,
                )
                if output_video.exists() and output_video.stat().st_size > 0:
                    return
                last_detail = "ffmpeg returned no silent video output"
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
                last_detail = getattr(exc, "stderr", "") or str(exc)
        raise LocalAvatarRuntimeError(
            f"Could not remove HappyHorse's generated audio before lip sync: {last_detail[-600:]}",
            stage="avatar:happy_horse_silence",
        )

    def _prepare_fal_video_edit_source(
            self,
            source: Path,
            output_video: Path,
            duration_seconds: int,
            aspect_ratio: str,
    ) -> None:
        normalized_ratio = self._first_text(aspect_ratio, "9:16").replace(" ", "")
        width, height = (1280, 720) if normalized_ratio == "16:9" else (720, 720) if normalized_ratio == "1:1" else (720, 1280)
        command = [
            "ffmpeg",
            "-y",
            "-stream_loop",
            "-1",
            "-i",
            str(source),
            "-t",
            str(max(3, min(10, duration_seconds))),
            "-an",
            "-vf",
            f"scale={width}:{height}:force_original_aspect_ratio=decrease,pad={width}:{height}:(ow-iw)/2:(oh-ih)/2,fps=25",
            "-c:v",
            "libx264",
            "-pix_fmt",
            "yuv420p",
            "-movflags",
            "+faststart",
            str(output_video),
        ]
        try:
            subprocess.run(
                command,
                check=True,
                capture_output=True,
                text=True,
                timeout=settings.avatar_stage_timeout_seconds,
            )
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
            detail = getattr(exc, "stderr", "") or str(exc)
            raise LocalAvatarRuntimeError(
                f"Could not prepare founder clip for production enhancement: {detail[-600:]}",
                stage="production_enhancement:prepare",
            ) from exc

    def _prepare_founder_scene_source(
            self,
            source: Path,
            output_video: Path,
            duration_seconds: int,
            start_seconds: int,
            aspect_ratio: str,
    ) -> None:
        normalized_ratio = self._first_text(aspect_ratio, "9:16").replace(" ", "")
        width, height = (1280, 720) if normalized_ratio == "16:9" else (720, 720) if normalized_ratio == "1:1" else (720, 1280)
        command = [
            "ffmpeg",
            "-y",
            "-stream_loop",
            "-1",
            "-i",
            str(source),
            "-ss",
            str(max(0, start_seconds)),
            "-t",
            str(max(1, min(60, duration_seconds))),
            "-an",
            "-vf",
            f"scale={width}:{height}:force_original_aspect_ratio=decrease,pad={width}:{height}:(ow-iw)/2:(oh-ih)/2,fps=25",
            "-c:v",
            "libx264",
            "-threads",
            "2",
            "-preset",
            "veryfast",
            "-pix_fmt",
            "yuv420p",
            "-movflags",
            "+faststart",
            str(output_video),
        ]
        try:
            subprocess.run(command, check=True, capture_output=True, text=True, timeout=settings.avatar_stage_timeout_seconds)
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
            detail = getattr(exc, "stderr", "") or str(exc)
            raise LocalAvatarRuntimeError(
                f"Could not prepare founder source for this scene: {detail[-600:]}",
                stage="scene:prepare_source",
            ) from exc

    def _extract_avatar_portrait(self, source: Path, output_image: Path) -> None:
        if source.suffix.lower() in {".jpg", ".jpeg", ".png", ".webp"}:
            shutil.copy2(source, output_image)
            return
        command = [
            "ffmpeg",
            "-y",
            "-ss",
            "0.5",
            "-i",
            str(source),
            "-frames:v",
            "1",
            "-q:v",
            "2",
            str(output_image),
        ]
        try:
            subprocess.run(command, check=True, capture_output=True, text=True, timeout=settings.avatar_stage_timeout_seconds)
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
            detail = getattr(exc, "stderr", "") or str(exc)
            raise LocalAvatarRuntimeError(
                f"Could not extract a founder portrait from the uploaded video: {detail[-600:]}",
                stage="avatar:portrait",
            ) from exc
        if not output_image.exists() or output_image.stat().st_size == 0:
            raise LocalAvatarRuntimeError(
                "Founder portrait extraction completed without an image.",
                stage="avatar:portrait",
            )

    def _write_silence_audio(self, output_wav: Path, duration_seconds: int) -> None:
        command = [
            "ffmpeg",
            "-y",
            "-f",
            "lavfi",
            "-i",
            "anullsrc=r=24000:cl=mono",
            "-t",
            str(max(1, min(60, duration_seconds))),
            "-c:a",
            "pcm_s16le",
            str(output_wav),
        ]
        subprocess.run(command, check=True, capture_output=True, text=True, timeout=settings.avatar_stage_timeout_seconds)

    def _production_enhancement_prompt(
            self,
            payload: dict[str, Any],
            founder_profile: dict[str, Any],
    ) -> str:
        direction = self._first_text(
            payload.get("productionEnhancementPrompt"),
            founder_profile.get("productionEnhancementPrompt"),
            founder_profile.get("details"),
            "polished founder wardrobe and a clean, premium studio background appropriate for the brand",
        )
        return (
            "Preserve the exact founder identity, face, skin tone, hairstyle, body proportions, expression, "
            "lip region, camera framing, and original motion. Change only the wardrobe, lighting, and background. "
            f"Production direction: {direction}. Keep the result photorealistic, temporally stable, and suitable for lip sync."
        )[:3000]

    def _convert_audio_to_wav(self, source: Path, output_wav: Path) -> None:
        command = [
            "ffmpeg",
            "-y",
            "-i",
            str(source),
            "-ac",
            "1",
            "-ar",
            "24000",
            str(output_wav),
        ]
        subprocess.run(command, check=True, capture_output=True, text=True, timeout=settings.avatar_stage_timeout_seconds)

    def _convert_audio_segment_to_wav(
            self,
            source: Path,
            output_wav: Path,
            start_seconds: float,
            duration_seconds: float,
    ) -> None:
        command = ["ffmpeg", "-y"]
        if start_seconds > 0:
            command.extend(["-ss", str(start_seconds)])
        command.extend(["-i", str(source)])
        if duration_seconds > 0:
            command.extend(["-t", str(max(1.0, min(60.0, duration_seconds)))])
        command.extend([
            "-vn",
            "-ac",
            "1",
            "-ar",
            "24000",
            "-af",
            "highpass=f=80,lowpass=f=9000,afftdn=nf=-25,loudnorm=I=-16:TP=-1.5:LRA=11",
            str(output_wav),
        ])
        subprocess.run(command, check=True, capture_output=True, text=True, timeout=settings.avatar_stage_timeout_seconds)

    def _avatar_test_fallback(self, source: Path, audio: Path, output: Path, duration_seconds: int) -> None:
        duration = max(1, min(duration_seconds, 20))
        command = [
            "ffmpeg",
            "-y",
            "-stream_loop",
            "-1",
            "-i",
            str(source),
            "-i",
            str(audio),
            "-t",
            str(duration),
            "-map",
            "0:v:0",
            "-map",
            "1:a:0",
            "-vf",
            "scale=720:1280:force_original_aspect_ratio=decrease,pad=720:1280:(ow-iw)/2:(oh-ih)/2",
            "-c:v",
            "libx264",
            "-pix_fmt",
            "yuv420p",
            "-c:a",
            "aac",
            "-shortest",
            str(output),
        ]
        subprocess.run(command, check=True, capture_output=True, text=True, timeout=settings.avatar_stage_timeout_seconds)

    def _voice_test_fallback(self, prompt_wav: Path, output: Path, text: str) -> None:
        words = max(1, len(text.split()))
        duration = max(2.0, min(90.0, words / 2.4))
        command = [
            "ffmpeg",
            "-y",
            "-stream_loop",
            "-1",
            "-i",
            str(prompt_wav),
            "-t",
            f"{duration:.2f}",
            "-af",
            "loudnorm=I=-16:TP=-1.5:LRA=11",
            str(output),
        ]
        subprocess.run(command, check=True, capture_output=True, text=True, timeout=settings.avatar_stage_timeout_seconds)

    def _run_template_command(self, template: str, variables: dict[str, str], *, stage: str) -> None:
        command = template.format_map(_SafeFormatDict(variables))
        logger.info("Running local avatar stage=%s command=%s", stage, self._redact_command(command))
        completed = subprocess.run(
            command,
            shell=True,
            cwd=variables.get("work_dir") or None,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=settings.avatar_stage_timeout_seconds,
        )
        if completed.returncode != 0:
            raise LocalAvatarRuntimeError(
                f"Local model stage {stage} failed: {completed.stderr[-1200:] or completed.stdout[-1200:]}",
                stage=stage,
            )

    def _template_variables(self, payload: dict[str, Any], work_dir: Path) -> dict[str, str]:
        return {
            "work_dir": str(work_dir),
            "model_root": str(self.model_root),
            "gpu_profile": settings.avatar_generation_gpu_profile,
            "scene_id": self._first_text(payload.get("sceneId")),
            "scene_number": str(payload.get("sceneNumber") or 1),
            "duration_seconds": str(payload.get("durationSeconds") or 8),
            "aspect_ratio": self._first_text(payload.get("aspectRatio"), "9:16"),
            "elevenlabs_base_url": settings.avatar_elevenlabs_base_url,
            "elevenlabs_model_id": settings.avatar_elevenlabs_model_id,
            "sync_labs_base_url": settings.avatar_sync_labs_base_url,
            "sync_labs_model": settings.avatar_sync_labs_model,
            "proprietary_api_timeout_seconds": str(settings.avatar_proprietary_api_timeout_seconds),
        }

    def _raise_provider_http_error(
        self,
        response: httpx.Response,
        operation: str,
        stage: str,
    ) -> None:
        if response.is_success:
            return
        detail = ""
        try:
            payload = response.json()
            if isinstance(payload, dict):
                detail = self._first_text(
                    payload.get("detail"),
                    payload.get("message"),
                    payload.get("error"),
                )
            if not detail:
                detail = json.dumps(payload, ensure_ascii=True)
        except Exception:
            detail = self._first_text(response.text)
        safe_detail = re.sub(r"\s+", " ", detail).strip()[:800]
        suffix = f": {safe_detail}" if safe_detail else ""
        raise LocalAvatarRuntimeError(
            f"{operation} failed HTTP {response.status_code}{suffix}",
            stage=stage,
        )

    def _provider_identifier(self, payload: Any) -> str:
        if not isinstance(payload, dict):
            return ""
        for key in (
            "voice_id",
            "voiceId",
            "speaker_id",
            "speakerId",
            "custom_voice_id",
            "customVoiceId",
            "id",
        ):
            value = payload.get(key)
            if value is not None and str(value).strip():
                return str(value).strip()
        for key in ("data", "result", "voice", "speaker"):
            nested = payload.get(key)
            identifier = self._provider_identifier(nested)
            if identifier:
                return identifier
        return ""

    def _provider_url(self, base_url: str, path: str) -> str:
        clean_path = self._first_text(path)
        if clean_path.lower().startswith(("http://", "https://")):
            return clean_path
        return f"{base_url.rstrip('/')}/{clean_path.lstrip('/')}"

    def _elevenlabs_language_code(self, language: Any, language_code: Any) -> str:
        normalized_code = self._normalize(language_code).split("_", 1)[0]
        supported = {
            "ar",
            "bg",
            "bn",
            "cs",
            "da",
            "de",
            "el",
            "en",
            "es",
            "fi",
            "fil",
            "fr",
            "he",
            "hi",
            "hr",
            "hu",
            "id",
            "it",
            "ja",
            "ko",
            "ms",
            "nl",
            "no",
            "pl",
            "pt",
            "ro",
            "ru",
            "sk",
            "sv",
            "sw",
            "ta",
            "te",
            "th",
            "tr",
            "uk",
            "ur",
            "vi",
            "zh",
        }
        if normalized_code in supported:
            return normalized_code
        normalized_language = self._normalize(language)
        if normalized_language in {"english", "english_indian", "indian_english"}:
            return "en"
        if normalized_language in {"bengali", "bangla"}:
            return "bn"
        if normalized_language == "tamil":
            return "ta"
        return "hi"

    def _sarvam_language_code(self, language: Any, language_code: Any) -> str:
        normalized_code = self._first_text(language_code).replace("_", "-")
        supported = {
            "bn-IN",
            "en-IN",
            "gu-IN",
            "hi-IN",
            "kn-IN",
            "ml-IN",
            "mr-IN",
            "od-IN",
            "pa-IN",
            "ta-IN",
            "te-IN",
        }
        code_lookup = {value.lower(): value for value in supported}
        if normalized_code.lower() in code_lookup:
            return code_lookup[normalized_code.lower()]
        normalized_language = self._normalize(language)
        language_map = {
            "bengali": "bn-IN",
            "bangla": "bn-IN",
            "english": "en-IN",
            "english_indian": "en-IN",
            "indian_english": "en-IN",
            "gujarati": "gu-IN",
            "hindi": "hi-IN",
            "hinglish": "hi-IN",
            "kannada": "kn-IN",
            "malayalam": "ml-IN",
            "marathi": "mr-IN",
            "odia": "od-IN",
            "punjabi": "pa-IN",
            "tamil": "ta-IN",
            "telugu": "te-IN",
        }
        return language_map.get(normalized_language, "hi-IN")

    def _new_work_dir(self, prefix: str, payload: dict[str, Any]) -> Path:
        key = self._first_text(payload.get("sceneId"), payload.get("runId"), payload.get("voiceId"), str(uuid.uuid4()))
        digest = hashlib.sha1(key.encode("utf-8")).hexdigest()[:10]
        path = self.work_root / f"{prefix}-{digest}-{uuid.uuid4().hex[:8]}"
        path.mkdir(parents=True, exist_ok=True)
        (path / "request.json").write_text(json.dumps(self._jsonable(payload), indent=2), encoding="utf-8")
        return path

    def _cosyvoice_model_dir(self) -> Path:
        configured = self._first_text(settings.avatar_cosyvoice2_model_dir)
        return self._path(configured) if configured else self.model_root / "CosyVoice2-0.5B"

    def _path(self, value: str | None) -> Path:
        if not value:
            return self.model_root
        path = Path(value).expanduser()
        return path if path.is_absolute() else (self.model_root / path).resolve()

    def _stage_capability(self, name: str, path_or_model: str, command: str, *, python_import: str = "") -> dict[str, Any]:
        import_available = False
        if python_import:
            try:
                import_available = importlib.util.find_spec(python_import) is not None
            except (ImportError, ModuleNotFoundError, ValueError):
                import_available = False
        path_exists = bool(path_or_model and Path(path_or_model).expanduser().exists())
        return {
            "model": name,
            "configured": bool(command or path_exists or import_available),
            "commandConfigured": bool(command),
            "pathOrModel": path_or_model,
            "pathExists": path_exists,
            "pythonImport": python_import,
            "pythonImportAvailable": import_available,
        }

    def _latentsync_capability(self) -> dict[str, Any]:
        capability = self._stage_capability(
            "latentsync",
            settings.avatar_latentsync_repo,
            settings.avatar_latentsync_command,
        )
        repo = self._path(settings.avatar_latentsync_repo)
        checkpoint = repo / "checkpoints" / "latentsync_unet.pt"
        whisper = repo / "checkpoints" / "whisper" / "tiny.pt"
        checkpoint_ready = checkpoint.is_file() and checkpoint.stat().st_size == 5_072_348_184
        whisper_ready = whisper.is_file() and whisper.stat().st_size == 75_572_083
        capability.update(
            {
                "configured": bool(settings.avatar_latentsync_command and checkpoint_ready and whisper_ready),
                "release": "1.5",
                "minimumVramGb": 8,
                "checkpointReady": checkpoint_ready,
                "whisperReady": whisper_ready,
            }
        )
        return capability

    def _fal_minimax_voice_cost(self, text: str, cloned: bool) -> float:
        per_thousand_characters = 0.3 if cloned else 0.1
        clone_cost = 1.5 if cloned else 0.0
        return round(clone_cost + max(0, len(text or "")) * per_thousand_characters / 1000, 6)

    def _fal_lipsync_cost(self, duration_seconds: int | float) -> float:
        duration = max(0.0, float(duration_seconds or 0))
        return round(0.20 if duration <= 40 else duration * 0.005, 6)

    def _fal_echomimic_cost(self, duration_seconds: int | float) -> float:
        duration = max(0.0, float(duration_seconds or 0))
        rate = max(0.0, float(settings.avatar_fal_echomimic_usd_per_second or 0.20))
        return round(duration * rate, 6)

    def _fal_happy_horse_cost(self, duration_seconds: int | float, resolution: str) -> float:
        duration = max(0.0, float(duration_seconds or 0))
        rate = (
            settings.avatar_fal_happy_horse_720p_usd_per_second
            if self._normalize(resolution) == "720p"
            else settings.avatar_fal_happy_horse_1080p_usd_per_second
        )
        return round(duration * max(0.0, float(rate or 0)), 6)

    def _fal_heygen_avatar4_cost(self, duration_seconds: int | float) -> float:
        duration = max(0.0, float(duration_seconds or 0))
        rate = max(0.0, float(settings.avatar_fal_heygen_avatar4_usd_per_second or 0.10))
        return round(duration * rate, 6)

    def _fal_video_edit_cost(self, duration_seconds: int | float) -> float:
        duration = max(0.0, float(duration_seconds or 0))
        return round(duration * max(0.0, float(settings.avatar_fal_video_edit_usd_per_second or 0)), 6)

    def _cost_metadata(
            self,
            provider: str,
            model: str,
            total_cost: float,
            usage: dict[str, Any],
    ) -> dict[str, Any]:
        external_provider = self._normalize(provider) in {
            "fal.ai",
            "fal_ai",
            "fal",
            "elevenlabs",
            "sarvam",
            "sync_labs",
        }
        metadata = {
            "modelApiInteracted": external_provider,
            "provider": provider,
            "model": model,
            "currency": "USD",
            "actualTotalCost": round(float(total_cost or 0), 6),
            "totalCost": round(float(total_cost or 0), 6),
            "usage": usage,
            "pricingSource": (
                "FAL_BILLING_EVENT_PENDING"
                if provider == "fal.ai"
                else usage.get("providerUsageSource", "PROVIDER_REPORTED_USAGE")
                if external_provider
                else "LOCAL_RUNTIME"
            ),
        }
        if provider == "elevenlabs":
            metadata["rateUnit"] = "CHARACTER_CREDIT"
            metadata["ratePerMillionCharacters"] = max(
                0.0,
                float(settings.avatar_elevenlabs_usd_per_million_credits or 0),
            )
        if provider == "fal.ai":
            metadata["falRequestIds"] = list(usage.get("falRequestIds") or [])
        return metadata

    def _base64_file(self, path: Path) -> str:
        return base64.b64encode(path.read_bytes()).decode("ascii")

    def _write_base64_to_file(self, encoded: str | None, output: Path) -> None:
        if not encoded:
            return
        data = encoded.strip()
        if data.startswith("data:") and "," in data:
            data = data.split(",", 1)[1]
        output.write_bytes(base64.b64decode(data))

    def _content_type(self, path: Path, fallback: str) -> str:
        if fallback:
            return fallback.split(";")[0].strip()
        suffix = path.suffix.lower()
        if suffix in {".wav"}:
            return "audio/wav"
        if suffix in {".mp3", ".mpeg"}:
            return "audio/mpeg"
        if suffix in {".png"}:
            return "image/png"
        if suffix in {".jpg", ".jpeg"}:
            return "image/jpeg"
        return "video/mp4"

    def _suffix_for_content_type(self, content_type: str, fallback: str) -> str:
        normalized = (content_type or "").split(";", 1)[0].strip().lower()
        return {
            "audio/wav": ".wav",
            "audio/x-wav": ".wav",
            "audio/mpeg": ".mp3",
            "audio/mp3": ".mp3",
            "audio/mp4": ".m4a",
            "audio/x-m4a": ".m4a",
            "audio/aac": ".aac",
            "video/mp4": ".mp4",
            "video/quicktime": ".mov",
            "video/webm": ".webm",
        }.get(normalized, fallback)

    def _suffix_from_url(self, url: str) -> str:
        clean = url.split("?", 1)[0].split("#", 1)[0]
        suffix = Path(clean).suffix.lower()
        return suffix if suffix else ".mp4"

    def _is_video_file(self, path: Path) -> bool:
        if not path.exists() or path.stat().st_size < 12:
            return False
        header = path.read_bytes()[:64]
        return b"ftyp" in header or header.startswith(b"\x1aE\xdf\xa3")

    def _language_code(self, payload: dict[str, Any]) -> str | None:
        language = self._first_text(payload.get("languageCode"), payload.get("language"))
        if not language:
            return None
        lower = language.lower()
        if lower.startswith("hi") or "hinglish" in lower or "hindi" in lower:
            return "hi"
        if lower.startswith("en"):
            return "en"
        return lower[:2]

    def _srt_time(self, seconds: float) -> str:
        safe = max(0.0, float(seconds or 0))
        millis = int(round(safe * 1000))
        hours = millis // 3_600_000
        minutes = (millis % 3_600_000) // 60_000
        secs = (millis % 60_000) // 1000
        ms = millis % 1000
        return f"{hours:02}:{minutes:02}:{secs:02},{ms:03}"

    def _redact_command(self, command: str) -> str:
        token = os.environ.get("HF_TOKEN", "")
        redacted = command.replace(token, "[HF_TOKEN]") if token else command
        return textwrap.shorten(redacted, width=300)

    def _jsonable(self, value: Any) -> Any:
        try:
            json.dumps(value)
            return value
        except TypeError:
            return str(value)

    def _normalize(self, value: Any) -> str:
        return str(value or "").strip().lower().replace("-", "_").replace(" ", "_")

    def _boolean(self, value: Any, default: bool = False) -> bool:
        if value is None:
            return default
        if isinstance(value, bool):
            return value
        if isinstance(value, (int, float)):
            return value != 0
        normalized = str(value).strip().lower()
        if normalized in {"true", "1", "yes", "on"}:
            return True
        if normalized in {"false", "0", "no", "off", ""}:
            return False
        return default

    def _first_dict(self, *values: Any) -> dict[str, Any]:
        for value in values:
            if isinstance(value, dict):
                return value
        return {}

    def _first_text(self, *values: Any) -> str:
        for value in values:
            if value is not None and str(value).strip():
                return str(value).strip()
        return ""


class _SafeFormatDict(dict):
    def __missing__(self, key: str) -> str:
        return "{" + key + "}"


local_avatar_runtime = LocalAvatarRuntime()
