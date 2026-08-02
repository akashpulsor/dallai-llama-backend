"""Founder avatar generation contracts for creator-service."""
from __future__ import annotations

import base64
import json
import logging
import shutil
import tempfile
import uuid
from pathlib import Path
from typing import Any

import httpx
from fastapi import APIRouter, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field
from starlette.background import BackgroundTask

from config import settings
from services.avatar_local_runtime import LocalAvatarRuntimeError, local_avatar_runtime

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/creator/avatar", tags=["creator-avatar"])


class AvatarSceneRequest(BaseModel):
    runId: str = Field(default="", max_length=128)
    scriptId: str = Field(default="", max_length=128)
    sceneId: str = Field(default="", max_length=128)
    sceneNumber: int = Field(default=1, ge=1)
    provider: str = "dalai_llama"
    model: str = "source_video"
    durationSeconds: int = Field(default=8, ge=1, le=60)
    startSeconds: int = Field(default=0, ge=0, le=3600)
    endSeconds: int = Field(default=0, ge=0, le=3660)
    aspectRatio: str = "9:16"
    prompt: str = Field(default="", max_length=12000)
    dialogueScript: str = Field(default="", max_length=3000)
    exactDialogue: str = Field(default="", max_length=3000)
    language: str = "Hinglish"
    languageCode: str = "hi-IN"
    languageBoost: str = "auto"
    seed: int | None = None
    generationMode: str = "talking_head"
    founderAvatarProfile: dict[str, Any] = Field(default_factory=dict)
    avatarId: str = ""
    voiceId: str = ""
    minimaxVoiceId: str = ""
    providerVoiceId: str = ""
    elevenLabsVoiceId: str = ""
    sarvamVoiceId: str = ""
    portraitEmbeddingId: str = ""
    facialFeatureEmbeddingId: str = ""
    voiceEmbeddingId: str = ""
    sourceUrl: str = ""
    sourceContent: str = ""
    sourceContentType: str = ""
    audioContent: str = ""
    audioBase64: str = ""
    audioContentType: str = ""
    audioUrl: str = ""
    finalFounderAudioUrl: str = ""
    spokenText: str = Field(default="", max_length=12000)
    captionText: str = Field(default="", max_length=12000)
    pronunciationGuide: str = Field(default="", max_length=4000)
    referenceTranscript: str = Field(default="", max_length=4000)
    sourceAsset: dict[str, Any] = Field(default_factory=dict)
    localModels: dict[str, Any] = Field(default_factory=dict)
    voiceModel: str = "fal_minimax_voice_clone"
    talkingAvatarModel: str = "source_video"
    imageModel: str = "gemini_storyboard"
    lightingModel: str = "ic_lightning"
    lipSyncModel: str = "fal_latentsync"
    videoModel: str = "ltx_video"
    gpuProfile: str = "rtx_4060_8gb"
    consentConfirmed: bool = False
    manualApprovalRequiredForFallback: bool = True
    fallbackProvider: str = "synthesia"
    productionEnhancementEnabled: bool = False
    productionEnhancementPrompt: str = Field(default="", max_length=3000)
    talkingStyle: str = "stable"
    expression: str = ""
    avatarResolution: str = "720p"
    avatarCaption: bool = False
    avatarBackground: dict[str, Any] = Field(default_factory=dict)


class AvatarVoiceRequest(BaseModel):
    requestId: str = Field(default="", max_length=128)
    text: str = Field(default="", max_length=12000)
    spokenText: str = Field(default="", max_length=12000)
    captionText: str = Field(default="", max_length=12000)
    pronunciationGuide: str = Field(default="", max_length=4000)
    provider: str = "dalai_llama"
    model: str = "fal_minimax_voice_clone"
    voiceModel: str = "fal_minimax_voice_clone"
    voiceId: str = ""
    minimaxVoiceId: str = ""
    providerVoiceId: str = ""
    elevenLabsVoiceId: str = ""
    sarvamVoiceId: str = ""
    voiceEmbeddingId: str = ""
    voiceProfileId: str = ""
    avatarId: str = ""
    language: str = "Hinglish"
    languageCode: str = "hi-IN"
    referenceLanguage: str = "English"
    languageBoost: str = "auto"
    stylePrompt: str = Field(default="", max_length=2000)
    promptText: str = Field(default="", max_length=4000)
    referenceTranscript: str = Field(default="", max_length=4000)
    gpuProfile: str = "rtx_4060_8gb"
    founderAvatarProfile: dict[str, Any] = Field(default_factory=dict)
    sourceUrl: str = ""
    sourceAsset: dict[str, Any] = Field(default_factory=dict)
    sourceContent: str = ""
    sourceContentType: str = ""
    sourcePurpose: str = "founder_reference"
    localModels: dict[str, Any] = Field(default_factory=dict)
    consentConfirmed: bool = False
    manualApprovalRequiredForFallback: bool = True
    fallbackProvider: str = "synthesia"
    preview: bool = False


class AvatarTranscriptionRequest(BaseModel):
    provider: str = "dalai_llama"
    sourceUrl: str = ""
    sourceContent: str = ""
    sourceContentType: str = ""
    audioContent: str = ""
    audioBase64: str = ""
    language: str = "Hinglish"
    languageCode: str = "hi-IN"
    model: str = "faster_whisper"
    device: str = ""
    computeType: str = ""
    founderAvatarProfile: dict[str, Any] = Field(default_factory=dict)


class AvatarImageRequest(BaseModel):
    provider: str = "dalai_llama"
    prompt: str = Field(default="", max_length=12000)
    negativePrompt: str = Field(default="", max_length=4000)
    width: int = Field(default=432, ge=128, le=2048)
    height: int = Field(default=768, ge=128, le=2048)
    steps: int = Field(default=28, ge=1, le=80)
    guidanceScale: float = Field(default=3.5, ge=0, le=20)
    seed: int | None = None
    aspectRatio: str = "9:16"
    imageModel: str = "gemini_storyboard"
    founderAvatarProfile: dict[str, Any] = Field(default_factory=dict)


class AvatarPostprocessImageRequest(BaseModel):
    provider: str = "dalai_llama"
    sourceUrl: str = ""
    sourceContent: str = ""
    sourceContentType: str = "image/png"
    backgroundRemoval: bool = True
    upscale: bool = True
    faceRestoration: bool = True
    founderAvatarProfile: dict[str, Any] = Field(default_factory=dict)


@router.get("/capabilities", summary="Get local founder-avatar model capabilities")
async def avatar_capabilities():
    runtime_capabilities = local_avatar_runtime.capabilities()
    runner_ready = False
    runner_status = "not_configured"
    if settings.avatar_generation_runner_url:
        runner_status = "unavailable"
        try:
            async with httpx.AsyncClient(timeout=min(settings.avatar_generation_timeout_seconds, 10)) as client:
                response = await client.get(settings.avatar_generation_runner_url.rstrip("/") + "/ready")
                response.raise_for_status()
                readiness = response.json()
            external_runtime = readiness.get("runtime")
            if isinstance(external_runtime, dict):
                embedded_voice = runtime_capabilities.get("stages", {}).get("voice", {})
                runtime_capabilities = external_runtime
                external_stages = runtime_capabilities.setdefault("stages", {})
                external_voice = external_stages.setdefault("voice", {})
                external_stages["voice"] = {**embedded_voice, **external_voice}
            runner_status = str(readiness.get("status") or "ready")
            runner_ready = runner_status.lower() == "ready"
        except Exception as exc:  # noqa: BLE001 - capabilities must remain available when the worker is offline.
            logger.warning("avatar worker readiness unavailable error=%s", exc)

    return {
        "provider": "dalai_llama",
        "enabled": settings.avatar_generation_enabled,
        "runnerConfigured": bool(settings.avatar_generation_runner_url),
        "runnerReady": runner_ready,
        "runnerStatus": runner_status,
        "executionMode": "external_worker" if settings.avatar_generation_runner_url else "embedded_runtime",
        "gpuProfile": settings.avatar_generation_gpu_profile,
        "language": "Hinglish",
        "languageCode": "hi-IN",
        "recommendedRtx4060Models": {
            "voiceModel": settings.avatar_voice_model,
            "talkingAvatarModel": settings.avatar_talking_model,
            "imageModel": settings.avatar_image_model,
            "lightingModel": settings.avatar_lighting_model,
            "lipSyncModel": settings.avatar_lipsync_model,
            "videoModel": settings.avatar_video_model,
        },
        "localRuntime": runtime_capabilities,
        "fallbackProvider": "synthesia",
        "manualApprovalRequiredForFallback": True,
    }


@router.post("/voice", summary="Generate founder voiceover audio")
async def generate_avatar_voice(payload: AvatarVoiceRequest):
    request_id = payload.requestId.strip() or f"voice-{uuid.uuid4()}"
    payload.requestId = request_id
    if not (payload.spokenText or payload.text).strip():
        raise HTTPException(status_code=400, detail="Voice text is required")
    _require_founder_consent(payload)
    logger.info(
        "Founder voice request accepted request_id=%s model=%s preview=%s text_chars=%s has_inline_source=%s has_source_url=%s has_existing_voice=%s",
        request_id,
        payload.voiceModel or payload.model,
        payload.preview,
        len((payload.spokenText or payload.text).strip()),
        bool(payload.sourceContent),
        bool(payload.sourceUrl),
        bool(
            payload.minimaxVoiceId
            or payload.providerVoiceId
            or payload.elevenLabsVoiceId
            or payload.sarvamVoiceId
        ),
    )
    selected_voice_model = (payload.voiceModel or payload.model).strip().lower().replace("-", "_")
    use_external_client_voice_worker = (
        selected_voice_model == "client_rvc_english"
        and bool(settings.avatar_generation_runner_url)
    )
    if (
        settings.avatar_generation_enabled
        and settings.avatar_local_runtime_enabled
        and not use_external_client_voice_worker
    ):
        try:
            result = await local_avatar_runtime.generate_voice(payload.model_dump())
            if isinstance(result, dict):
                result.setdefault("requestId", request_id)
            logger.info(
                "Founder voice request completed request_id=%s status=%s model=%s has_audio=%s has_provider_voice=%s",
                request_id,
                result.get("status", "unknown") if isinstance(result, dict) else "unknown",
                result.get("voiceModel", payload.voiceModel or payload.model) if isinstance(result, dict) else payload.voiceModel or payload.model,
                bool(_audio_base64(result)) if isinstance(result, dict) else False,
                bool(result.get("providerVoiceId") or result.get("customVoiceId")) if isinstance(result, dict) else False,
            )
            return result
        except LocalAvatarRuntimeError as exc:
            logger.warning(
                "Founder voice request needs setup request_id=%s stage=%s error_type=%s reason=%s",
                request_id,
                exc.stage,
                type(exc).__name__,
                exc,
            )
            return _manual_voice_response(payload, str(exc), stage=exc.stage, setup_hint=exc.setup_hint)
        except Exception as exc:
            logger.exception(
                "Founder voice request failed request_id=%s model=%s error_type=%s",
                request_id,
                payload.voiceModel or payload.model,
                type(exc).__name__,
            )
            raise HTTPException(
                status_code=500,
                detail=f"Founder voice generation failed. Reference: {request_id}",
            ) from exc
    if not settings.avatar_generation_enabled or not settings.avatar_generation_runner_url:
        return _manual_voice_response(payload, "Local founder voice runner is not enabled or configured.")

    try:
        async with httpx.AsyncClient(timeout=settings.avatar_generation_timeout_seconds) as client:
            response = await client.post(
                settings.avatar_generation_runner_url.rstrip("/") + settings.avatar_voice_runner_path,
                json=payload.model_dump(),
            )
            response.raise_for_status()
            data = response.json()
    except httpx.HTTPStatusError as exc:
        logger.warning("local avatar voice runner rejected status=%s", exc.response.status_code)
        return _manual_voice_response(payload, f"Local avatar voice runner failed HTTP {exc.response.status_code}.")
    except Exception as exc:  # noqa: BLE001 - return manual fallback context to creator-service.
        logger.warning("local avatar voice runner unavailable error=%s", exc)
        return _manual_voice_response(payload, "Local avatar voice runner is unavailable.")

    if not _audio_base64(data):
        data.setdefault("status", "NEEDS_MANUAL_APPROVAL")
        data.setdefault("manualApprovalRequired", True)
        data.setdefault("fallbackProvider", payload.fallbackProvider or "synthesia")
        data.setdefault("manualApprovalReason", "Local voice runner completed without inline audio.")
    data.setdefault("provider", "dalai_llama")
    data.setdefault("model", payload.voiceModel or payload.model)
    data.setdefault("voiceModel", payload.voiceModel or payload.model)
    data.setdefault("language", payload.language or "Hinglish")
    data.setdefault("languageCode", payload.languageCode or "hi-IN")
    return data


@router.post("/voice/files", summary="Generate founder voiceover from an uploaded audio/video reference")
async def generate_avatar_voice_from_files(
    sample: UploadFile = File(...),
    text: str = Form(...),
    requestId: str = Form(""),
    provider: str = Form("dalai_llama"),
    model: str = Form("fal_minimax_voice_clone"),
    voiceModel: str = Form("fal_minimax_voice_clone"),
    voiceId: str = Form(""),
    minimaxVoiceId: str = Form(""),
    providerVoiceId: str = Form(""),
    elevenLabsVoiceId: str = Form(""),
    sarvamVoiceId: str = Form(""),
    voiceEmbeddingId: str = Form(""),
    voiceProfileId: str = Form(""),
    avatarId: str = Form(""),
    language: str = Form("Hinglish"),
    languageCode: str = Form("hi-IN"),
    referenceLanguage: str = Form("English"),
    languageBoost: str = Form("auto"),
    stylePrompt: str = Form(""),
    spokenText: str = Form(""),
    captionText: str = Form(""),
    pronunciationGuide: str = Form(""),
    promptText: str = Form(""),
    referenceTranscript: str = Form(""),
    gpuProfile: str = Form("rtx_4060_8gb"),
    consentConfirmed: bool = Form(False),
    founderAvatarProfileJson: str = Form("{}"),
    localModelsJson: str = Form("{}"),
    manualApprovalRequiredForFallback: bool = Form(True),
    fallbackProvider: str = Form("synthesia"),
    sourcePurpose: str = Form("founder_reference"),
    preview: bool = Form(False),
):
    sample_content = await sample.read()
    logger.info(
        "Founder voice multipart received request_id=%s filename_present=%s content_type=%s bytes=%s model=%s preview=%s",
        requestId or "pending",
        bool(sample.filename),
        _upload_content_type(sample, "video/mp4"),
        len(sample_content),
        voiceModel or model,
        preview,
    )
    payload = AvatarVoiceRequest(
        requestId=requestId,
        text=text,
        provider=provider,
        model=model,
        voiceModel=voiceModel,
        voiceId=voiceId,
        minimaxVoiceId=minimaxVoiceId,
        providerVoiceId=providerVoiceId,
        elevenLabsVoiceId=elevenLabsVoiceId,
        sarvamVoiceId=sarvamVoiceId,
        voiceEmbeddingId=voiceEmbeddingId,
        voiceProfileId=voiceProfileId,
        avatarId=avatarId,
        language=language,
        languageCode=languageCode,
        referenceLanguage=referenceLanguage,
        languageBoost=languageBoost,
        stylePrompt=stylePrompt,
        spokenText=spokenText,
        captionText=captionText,
        pronunciationGuide=pronunciationGuide,
        promptText=promptText,
        referenceTranscript=referenceTranscript,
        gpuProfile=gpuProfile,
        consentConfirmed=consentConfirmed,
        founderAvatarProfile=_json_form(founderAvatarProfileJson),
        sourceContent=base64.b64encode(sample_content).decode("ascii"),
        sourceContentType=_upload_content_type(sample, "video/mp4"),
        sourcePurpose=sourcePurpose,
        localModels={**_json_form(localModelsJson), "voiceModel": voiceModel},
        manualApprovalRequiredForFallback=manualApprovalRequiredForFallback,
        fallbackProvider=fallbackProvider,
        preview=preview,
    )
    return await generate_avatar_voice(payload)


@router.post("/scenes", summary="Generate one founder talking-avatar scene")
async def generate_avatar_scene(payload: AvatarSceneRequest):
    _require_founder_consent(payload)
    if settings.avatar_generation_enabled and settings.avatar_local_runtime_enabled:
        try:
            return await local_avatar_runtime.generate_scene(payload.model_dump())
        except LocalAvatarRuntimeError as exc:
            logger.warning("local avatar scene runtime setup needed stage=%s scene_id=%s reason=%s", exc.stage, payload.sceneId, exc)
            return _manual_approval_response(payload, str(exc), stage=exc.stage, setup_hint=exc.setup_hint)
    if not settings.avatar_generation_enabled or not settings.avatar_generation_runner_url:
        return _manual_approval_response(payload, "Local founder-avatar runner is not enabled or configured.")

    try:
        async with httpx.AsyncClient(timeout=settings.avatar_generation_timeout_seconds) as client:
            response = await client.post(
                settings.avatar_generation_runner_url.rstrip("/") + settings.avatar_generation_runner_path,
                json=payload.model_dump(),
            )
            response.raise_for_status()
            data = response.json()
    except httpx.HTTPStatusError as exc:
        logger.warning("local avatar runner rejected scene scene_id=%s status=%s", payload.sceneId, exc.response.status_code)
        return _manual_approval_response(payload, f"Local avatar runner failed HTTP {exc.response.status_code}.")
    except Exception as exc:  # noqa: BLE001 - return manual fallback context to creator-service.
        logger.warning("local avatar runner unavailable scene_id=%s error=%s", payload.sceneId, exc)
        return _manual_approval_response(payload, "Local avatar runner is unavailable.")

    if not _video_url(data) and not _video_base64(data):
        data.setdefault("status", "NEEDS_MANUAL_APPROVAL")
        data.setdefault("manualApprovalRequired", True)
        data.setdefault("fallbackProvider", "synthesia")
        data.setdefault("manualApprovalReason", "Local runner completed without a downloadable video.")
    data.setdefault("provider", "dalai_llama")
    data.setdefault("model", payload.talkingAvatarModel or payload.model)
    return data


@router.post("/scenes/files", summary="Generate one founder talking-avatar scene from uploaded media")
async def generate_avatar_scene_from_files(
    sourceVideo: UploadFile = File(...),
    dialogueAudio: UploadFile | None = File(None),
    runId: str = Form(""),
    scriptId: str = Form(""),
    sceneId: str = Form(""),
    sceneNumber: int = Form(1),
    provider: str = Form("dalai_llama"),
    model: str = Form("source_video"),
    durationSeconds: int = Form(8),
    aspectRatio: str = Form("9:16"),
    prompt: str = Form(""),
    dialogueScript: str = Form(""),
    exactDialogue: str = Form(""),
    language: str = Form("Hinglish"),
    languageCode: str = Form("hi-IN"),
    generationMode: str = Form("talking_head"),
    founderAvatarProfileJson: str = Form("{}"),
    localModelsJson: str = Form("{}"),
    voiceModel: str = Form("fal_minimax_voice_clone"),
    talkingAvatarModel: str = Form("source_video"),
    imageModel: str = Form("gemini_storyboard"),
    lightingModel: str = Form("ic_lightning"),
    lipSyncModel: str = Form("fal_latentsync"),
    videoModel: str = Form("ltx_video"),
    gpuProfile: str = Form("rtx_4060_8gb"),
    consentConfirmed: bool = Form(False),
    manualApprovalRequiredForFallback: bool = Form(True),
    fallbackProvider: str = Form("synthesia"),
    productionEnhancementEnabled: bool = Form(False),
    productionEnhancementPrompt: str = Form(""),
    talkingStyle: str = Form("stable"),
    expression: str = Form(""),
    avatarResolution: str = Form("720p"),
    avatarCaption: bool = Form(False),
    avatarBackgroundJson: str = Form("{}"),
):
    if not consentConfirmed:
        raise HTTPException(status_code=403, detail="Founder consent is required before lip-sync processing.")
    if not settings.avatar_generation_enabled or not settings.avatar_local_runtime_enabled:
        raise HTTPException(status_code=503, detail="Streaming founder-avatar runtime is not enabled.")

    local_models = {
        **_json_form(localModelsJson),
        "voiceModel": voiceModel,
        "talkingAvatarModel": talkingAvatarModel,
        "imageModel": imageModel,
        "lightingModel": lightingModel,
        "lipSyncModel": lipSyncModel,
        "videoModel": videoModel,
        "gpuProfile": gpuProfile,
    }
    payload_kwargs: dict[str, Any] = {
        "runId": runId,
        "scriptId": scriptId,
        "sceneId": sceneId,
        "sceneNumber": sceneNumber,
        "provider": provider,
        "model": model,
        "durationSeconds": durationSeconds,
        "aspectRatio": aspectRatio,
        "prompt": prompt,
        "dialogueScript": dialogueScript,
        "exactDialogue": exactDialogue,
        "language": language,
        "languageCode": languageCode,
        "generationMode": generationMode,
        "founderAvatarProfile": _json_form(founderAvatarProfileJson),
        "sourceContentType": _upload_content_type(sourceVideo, "video/mp4"),
        "localModels": local_models,
        "voiceModel": voiceModel,
        "talkingAvatarModel": talkingAvatarModel,
        "imageModel": imageModel,
        "lightingModel": lightingModel,
        "lipSyncModel": lipSyncModel,
        "videoModel": videoModel,
        "gpuProfile": gpuProfile,
        "consentConfirmed": consentConfirmed,
        "manualApprovalRequiredForFallback": manualApprovalRequiredForFallback,
        "fallbackProvider": fallbackProvider,
        "productionEnhancementEnabled": productionEnhancementEnabled,
        "productionEnhancementPrompt": productionEnhancementPrompt,
        "talkingStyle": talkingStyle,
        "expression": expression,
        "avatarResolution": avatarResolution,
        "avatarCaption": avatarCaption,
        "avatarBackground": _json_form(avatarBackgroundJson),
    }
    payload = AvatarSceneRequest(**payload_kwargs)
    incoming_root = Path(settings.avatar_work_root).expanduser().resolve() / "incoming"
    incoming_root.mkdir(parents=True, exist_ok=True)
    incoming_dir = Path(tempfile.mkdtemp(prefix="avatar-scene-", dir=incoming_root))
    source_path = incoming_dir / f"source-media{_upload_suffix(sourceVideo, '.mp4')}"
    audio_path = incoming_dir / f"dialogue-audio{_upload_suffix(dialogueAudio, '.wav')}"
    work_dir: Path | None = None
    try:
        source_bytes = await _stream_upload_to_path(sourceVideo, source_path)
        audio_bytes = 0
        if dialogueAudio is not None and dialogueAudio.filename:
            audio_bytes = await _stream_upload_to_path(dialogueAudio, audio_path)
        runtime_payload = payload.model_dump()
        runtime_payload["_sourceFilePath"] = str(source_path)
        runtime_payload["_sourceFileContentType"] = _upload_content_type(sourceVideo, "video/mp4")
        runtime_payload["_returnFilePath"] = True
        if audio_bytes > 0 and dialogueAudio is not None:
            runtime_payload["_audioFilePath"] = str(audio_path)
            runtime_payload["_audioFileContentType"] = _upload_content_type(dialogueAudio, "audio/wav")

        logger.info(
            "Founder avatar stream accepted request_id=%s source_bytes=%s audio_bytes=%s lip_sync_model=%s",
            runId,
            source_bytes,
            audio_bytes,
            lipSyncModel,
        )
        result = await local_avatar_runtime.generate_scene(runtime_payload)
        output_path = Path(str(result.get("outputFilePath") or "")).expanduser()
        if not output_path.is_file() or output_path.stat().st_size == 0:
            raise LocalAvatarRuntimeError(
                "Founder avatar runtime completed without a streamable video.",
                stage="scene:stream_output",
            )
        work_dir_text = str(result.get("workDir") or "").strip()
        work_dir = Path(work_dir_text).expanduser() if work_dir_text else output_path.parent
        headers = _avatar_stream_headers(result)
        logger.info(
            "Founder avatar stream ready request_id=%s output_bytes=%s fal_request_id=%s",
            runId,
            output_path.stat().st_size,
            result.get("falRequestId") or "",
        )
        return FileResponse(
            output_path,
            media_type="video/mp4",
            headers=headers,
            background=BackgroundTask(_cleanup_avatar_paths, incoming_dir, work_dir),
        )
    except LocalAvatarRuntimeError as exc:
        _cleanup_avatar_paths(incoming_dir, work_dir)
        logger.warning(
            "Founder avatar stream failed request_id=%s stage=%s reason=%s",
            runId,
            exc.stage,
            exc,
        )
        raise HTTPException(
            status_code=503,
            detail={"stage": exc.stage, "message": str(exc), "setupHint": exc.setup_hint},
        ) from exc
    except Exception:
        _cleanup_avatar_paths(incoming_dir, work_dir)
        logger.exception("Founder avatar stream failed unexpectedly request_id=%s", runId)
        raise


@router.post("/lip-sync/files", summary="Lip-sync an uploaded video with uploaded audio")
async def lip_sync_uploaded_files(
    video: UploadFile = File(...),
    audio: UploadFile = File(...),
    runId: str = Form(""),
    sceneId: str = Form(""),
    sceneNumber: int = Form(1),
    provider: str = Form("dalai_llama"),
    model: str = Form("fal_latentsync"),
    lipSyncModel: str = Form("fal_latentsync"),
    aspectRatio: str = Form("9:16"),
    durationSeconds: int = Form(8),
    consentConfirmed: bool = Form(False),
    localModelsJson: str = Form("{}"),
):
    if not consentConfirmed:
        raise HTTPException(status_code=403, detail="Founder consent is required before lip-sync processing.")
    if not settings.avatar_generation_enabled:
        raise HTTPException(status_code=503, detail="Founder-avatar generation is not enabled.")
    if not settings.avatar_generation_runner_url and not settings.avatar_local_runtime_enabled:
        raise HTTPException(status_code=503, detail="Founder-avatar worker is not configured.")
    local_models = {**_json_form(localModelsJson), "lipSyncModel": lipSyncModel}
    payload = {
        "runId": runId,
        "sceneId": sceneId,
        "sceneNumber": sceneNumber,
        "provider": provider,
        "model": model,
        "lipSyncModel": lipSyncModel,
        "durationSeconds": durationSeconds,
        "aspectRatio": aspectRatio,
        "consentConfirmed": consentConfirmed,
        "sourceContent": await _upload_file_base64(video),
        "sourceContentType": _upload_content_type(video, "video/mp4"),
        "audioContent": await _upload_file_base64(audio),
        "audioContentType": _upload_content_type(audio, "audio/wav"),
        "localModels": local_models,
    }
    if settings.avatar_generation_runner_url:
        return await _run_remote_stage(
            settings.avatar_lipsync_runner_path,
            payload,
            stage="lip_sync",
        )
    try:
        return await local_avatar_runtime.lip_sync(payload)
    except LocalAvatarRuntimeError as exc:
        raise HTTPException(
            status_code=503,
            detail={"stage": exc.stage, "message": str(exc), "setupHint": exc.setup_hint},
        ) from exc


@router.post("/stt", summary="Transcribe founder sample and build captions")
async def transcribe_avatar_media(payload: AvatarTranscriptionRequest):
    if settings.avatar_generation_runner_url:
        return await _run_remote_stage(
            settings.avatar_stt_runner_path,
            payload.model_dump(),
            stage="stt",
        )
    if not settings.avatar_generation_enabled or not settings.avatar_local_runtime_enabled:
        raise HTTPException(status_code=503, detail="Local avatar runtime is not enabled.")
    try:
        return await local_avatar_runtime.transcribe(payload.model_dump())
    except LocalAvatarRuntimeError as exc:
        raise HTTPException(
            status_code=503,
            detail={"stage": exc.stage, "message": str(exc), "setupHint": exc.setup_hint},
        ) from exc


@router.post("/images", summary="Generate founder-video supporting image")
async def generate_avatar_image(payload: AvatarImageRequest):
    if not payload.prompt.strip():
        raise HTTPException(status_code=400, detail="Image prompt is required")
    if settings.avatar_generation_runner_url:
        return await _run_remote_stage(
            settings.avatar_image_runner_path,
            payload.model_dump(),
            stage="image",
        )
    if not settings.avatar_generation_enabled or not settings.avatar_local_runtime_enabled:
        raise HTTPException(status_code=503, detail="Local avatar runtime is not enabled.")
    try:
        return await local_avatar_runtime.generate_image(payload.model_dump())
    except LocalAvatarRuntimeError as exc:
        raise HTTPException(
            status_code=503,
            detail={"stage": exc.stage, "message": str(exc), "setupHint": exc.setup_hint},
        ) from exc


@router.post("/postprocess-image", summary="Remove background, upscale, and restore faces")
async def postprocess_avatar_image(payload: AvatarPostprocessImageRequest):
    if settings.avatar_generation_runner_url:
        return await _run_remote_stage(
            settings.avatar_postprocess_runner_path,
            payload.model_dump(),
            stage="postprocess",
        )
    if not settings.avatar_generation_enabled or not settings.avatar_local_runtime_enabled:
        raise HTTPException(status_code=503, detail="Local avatar runtime is not enabled.")
    try:
        return await local_avatar_runtime.postprocess_image(payload.model_dump())
    except LocalAvatarRuntimeError as exc:
        raise HTTPException(
            status_code=503,
            detail={"stage": exc.stage, "message": str(exc), "setupHint": exc.setup_hint},
        ) from exc


@router.get("/jobs/{job_id}", summary="Get a local founder-avatar scene job")
async def avatar_job(job_id: str):
    if not settings.avatar_generation_runner_url:
        raise HTTPException(status_code=404, detail="Local avatar runner is not configured")
    try:
        async with httpx.AsyncClient(timeout=settings.avatar_generation_timeout_seconds) as client:
            response = await client.get(
                settings.avatar_generation_runner_url.rstrip("/") + f"/jobs/{job_id}",
            )
            response.raise_for_status()
            return response.json()
    except httpx.HTTPStatusError as exc:
        raise HTTPException(status_code=exc.response.status_code, detail=exc.response.text) from exc


async def _run_remote_stage(
    path: str,
    payload: dict[str, Any],
    *,
    stage: str,
) -> dict[str, Any]:
    if not settings.avatar_generation_enabled or not settings.avatar_generation_runner_url:
        raise HTTPException(status_code=503, detail="Founder-avatar worker is not configured.")
    try:
        async with httpx.AsyncClient(timeout=settings.avatar_generation_timeout_seconds) as client:
            response = await client.post(
                settings.avatar_generation_runner_url.rstrip("/") + path,
                json=payload,
            )
            response.raise_for_status()
            data = response.json()
    except httpx.HTTPStatusError as exc:
        try:
            detail: Any = exc.response.json().get("detail", exc.response.text)
        except (ValueError, AttributeError):
            detail = exc.response.text
        raise HTTPException(status_code=exc.response.status_code, detail=detail) from exc
    except (httpx.RequestError, ValueError) as exc:
        logger.warning("avatar worker unavailable stage=%s error=%s", stage, exc)
        raise HTTPException(
            status_code=503,
            detail={"stage": stage, "message": "Founder-avatar worker is unavailable."},
        ) from exc
    if not isinstance(data, dict):
        raise HTTPException(
            status_code=502,
            detail={"stage": stage, "message": "Founder-avatar worker returned an invalid response."},
        )
    return data


def _manual_approval_response(
    payload: AvatarSceneRequest,
    reason: str,
    *,
    stage: str = "",
    setup_hint: str = "",
) -> dict[str, Any]:
    return {
        "status": "NEEDS_MANUAL_APPROVAL",
        "manualApprovalRequired": True,
        "manualApprovalReason": reason,
        "stage": stage,
        "setupHint": setup_hint,
        "fallbackProvider": payload.fallbackProvider or "synthesia",
        "provider": "dalai_llama",
        "model": payload.talkingAvatarModel or payload.model,
        "lipSyncModel": payload.lipSyncModel,
        "sceneId": payload.sceneId,
        "sceneNumber": payload.sceneNumber,
        "avatarId": payload.avatarId,
        "voiceId": payload.voiceId,
        "portraitEmbeddingId": payload.portraitEmbeddingId,
        "facialFeatureEmbeddingId": payload.facialFeatureEmbeddingId,
        "voiceEmbeddingId": payload.voiceEmbeddingId,
        "localModels": payload.localModels,
        "manualApprovalAction": setup_hint or "Approve Synthesia fallback or configure local avatar runner.",
    }


def _require_founder_consent(payload: AvatarSceneRequest | AvatarVoiceRequest) -> None:
    profile = payload.founderAvatarProfile or {}
    consent_confirmed = bool(
        payload.consentConfirmed
        or profile.get("consentConfirmed")
        or profile.get("consent_confirmed")
    )
    if not consent_confirmed:
        raise HTTPException(
            status_code=403,
            detail="Founder consent is required before avatar or voice cloning.",
        )


def _manual_voice_response(
    payload: AvatarVoiceRequest,
    reason: str,
    *,
    stage: str = "",
    setup_hint: str = "",
) -> dict[str, Any]:
    return {
        "requestId": payload.requestId,
        "status": "NEEDS_MANUAL_APPROVAL",
        "manualApprovalRequired": True,
        "manualApprovalReason": reason,
        "stage": stage,
        "setupHint": setup_hint,
        "fallbackProvider": payload.fallbackProvider or "synthesia",
        "provider": "dalai_llama",
        "model": payload.voiceModel or payload.model,
        "voiceModel": payload.voiceModel or payload.model,
        "avatarId": payload.avatarId,
        "voiceId": payload.voiceId,
        "voiceEmbeddingId": payload.voiceEmbeddingId,
        "language": payload.language or "Hinglish",
        "languageCode": payload.languageCode or "hi-IN",
        "gpuProfile": payload.gpuProfile or settings.avatar_generation_gpu_profile,
        "manualApprovalAction": setup_hint or "Approve Synthesia fallback or configure local avatar voice runner.",
    }


async def _upload_file_base64(file: UploadFile) -> str:
    content = await file.read()
    if not content:
        raise HTTPException(status_code=400, detail=f"{file.filename or 'upload'} is empty.")
    return base64.b64encode(content).decode("ascii")


async def _stream_upload_to_path(file: UploadFile, destination: Path) -> int:
    destination.parent.mkdir(parents=True, exist_ok=True)
    await file.seek(0)
    total_bytes = 0
    with destination.open("wb") as target:
        while True:
            chunk = await file.read(1024 * 1024)
            if not chunk:
                break
            target.write(chunk)
            total_bytes += len(chunk)
    if total_bytes == 0:
        destination.unlink(missing_ok=True)
        raise HTTPException(status_code=400, detail=f"{file.filename or 'upload'} is empty.")
    return total_bytes


def _avatar_stream_headers(result: dict[str, Any]) -> dict[str, str]:
    metadata = {
        key: value
        for key, value in result.items()
        if key not in {"outputFilePath", "outputVideo", "workDir"}
    }
    encoded = base64.urlsafe_b64encode(
        json.dumps(metadata, separators=(",", ":"), ensure_ascii=True).encode("utf-8")
    ).decode("ascii")
    return {
        "X-Dalai-Avatar-Metadata": encoded,
        "X-Dalai-Avatar-Request-Id": str(result.get("requestId") or ""),
        "X-Dalai-Fal-Request-Id": str(result.get("falRequestId") or ""),
    }


def _cleanup_avatar_paths(*paths: Path | None) -> None:
    work_root = Path(settings.avatar_work_root).expanduser().resolve()
    for path in paths:
        if path is None:
            continue
        try:
            resolved = path.expanduser().resolve()
            if resolved == work_root or not resolved.is_relative_to(work_root):
                continue
            if resolved.is_dir():
                shutil.rmtree(resolved, ignore_errors=True)
            else:
                resolved.unlink(missing_ok=True)
        except OSError:
            logger.warning("Could not clean avatar stream path path=%s", path)


def _upload_content_type(file: UploadFile, fallback: str) -> str:
    return (file.content_type or fallback).split(";", 1)[0].strip() or fallback


def _upload_suffix(file: UploadFile | None, fallback: str) -> str:
    if file is None:
        return fallback
    suffix = Path(file.filename or "").suffix.lower()
    if suffix and len(suffix) <= 8 and suffix[1:].isalnum():
        return suffix
    content_type = _upload_content_type(file, "")
    return {
        "image/jpeg": ".jpg",
        "image/png": ".png",
        "image/webp": ".webp",
        "video/quicktime": ".mov",
        "video/webm": ".webm",
        "audio/mpeg": ".mp3",
        "audio/mp4": ".m4a",
        "audio/x-m4a": ".m4a",
    }.get(content_type, fallback)


def _json_form(value: str) -> dict[str, Any]:
    if not value or not value.strip():
        return {}
    try:
        parsed = json.loads(value)
    except json.JSONDecodeError as exc:
        raise HTTPException(status_code=400, detail="Invalid JSON form field.") from exc
    if not isinstance(parsed, dict):
        raise HTTPException(status_code=400, detail="JSON form field must be an object.")
    return parsed


def _audio_base64(data: dict[str, Any]) -> str:
    for key in ("audioContent", "audioBase64", "audio_base64", "data"):
        value = data.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    for key in ("audio", "data", "result"):
        child = data.get(key)
        if isinstance(child, dict):
            nested = _audio_base64(child)
            if nested:
                return nested
    return ""


def _video_url(data: dict[str, Any]) -> str:
    for key in ("videoUrl", "video_url", "url", "downloadUrl", "download", "uri"):
        value = data.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    for key in ("data", "result", "video"):
        child = data.get(key)
        if isinstance(child, dict):
            nested = _video_url(child)
            if nested:
                return nested
    return ""


def _video_base64(data: dict[str, Any]) -> str:
    for key in ("videoContent", "videoBase64", "data"):
        value = data.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    for key in ("outputVideo", "video", "data", "result"):
        child = data.get(key)
        if isinstance(child, dict):
            nested = _video_base64(child)
            if nested:
                return nested
    return ""
