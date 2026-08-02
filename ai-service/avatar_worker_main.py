"""Single-GPU founder-avatar worker.

Run this process beside the model cache, not in the public ai-service pod. Each
worker can enable a subset of stages and serializes GPU work by default so an
8 GB card never loads competing pipelines at the same time.
"""
from __future__ import annotations

import asyncio
import os
from collections.abc import Awaitable, Callable
from typing import Any, TypeVar

from dotenv import load_dotenv

_env_file = os.getenv("AVATAR_WORKER_ENV_FILE", "").strip()
if _env_file:
    load_dotenv(_env_file, override=True)
os.environ["DEBUG"] = "false"
os.environ["AUTH_ENABLED"] = "false"
os.environ["AVATAR_GENERATION_ENABLED"] = "true"
os.environ["AVATAR_LOCAL_RUNTIME_ENABLED"] = "true"

from fastapi import FastAPI, HTTPException  # noqa: E402

from api.avatar_generation import (  # noqa: E402
    AvatarImageRequest,
    AvatarPostprocessImageRequest,
    AvatarSceneRequest,
    AvatarTranscriptionRequest,
    AvatarVoiceRequest,
)
from services.avatar_local_runtime import (  # noqa: E402
    LocalAvatarRuntimeError,
    local_avatar_runtime,
)

T = TypeVar("T")


class GpuStageQueue:
    def __init__(self) -> None:
        concurrency = max(1, int(os.getenv("AVATAR_GPU_MAX_CONCURRENCY", "1")))
        self._semaphore = asyncio.Semaphore(concurrency)
        self.max_concurrency = concurrency
        self.waiting = 0
        self.active = 0

    async def run(self, operation: Callable[[], Awaitable[T]]) -> T:
        self.waiting += 1
        await self._semaphore.acquire()
        self.waiting -= 1
        self.active += 1
        try:
            return await operation()
        finally:
            self.active -= 1
            self._semaphore.release()

    def status(self) -> dict[str, int]:
        return {
            "active": self.active,
            "waiting": self.waiting,
            "maxConcurrency": self.max_concurrency,
        }


app = FastAPI(title="Dalai Llama Founder Avatar Worker", version="1.0.0")
gpu_queue = GpuStageQueue()
enabled_stages = {
    value.strip().lower().replace("-", "_")
    for value in os.getenv(
        "AVATAR_WORKER_STAGES",
        "voice,scene,lip_sync,stt,image,postprocess",
    ).split(",")
    if value.strip()
}


@app.get("/health")
async def health() -> dict[str, Any]:
    return {"status": "ok", "queue": gpu_queue.status()}


@app.get("/ready")
async def ready() -> dict[str, Any]:
    return {
        "status": "ready",
        "enabledStages": sorted(enabled_stages),
        "queue": gpu_queue.status(),
        "runtime": local_avatar_runtime.capabilities(),
    }


@app.post("/voice")
async def generate_voice(payload: AvatarVoiceRequest) -> dict[str, Any]:
    _require_stage("voice")
    _require_consent(payload.model_dump())
    return await _run_stage("voice", lambda: local_avatar_runtime.generate_voice(payload.model_dump()))


@app.post("/generate")
async def generate_scene(payload: AvatarSceneRequest) -> dict[str, Any]:
    _require_stage("scene")
    _require_consent(payload.model_dump())
    return await _run_stage("scene", lambda: local_avatar_runtime.generate_scene(payload.model_dump()))


@app.post("/lip-sync")
async def lip_sync(payload: dict[str, Any]) -> dict[str, Any]:
    _require_stage("lip_sync")
    _require_consent(payload)
    return await _run_stage("lip_sync", lambda: local_avatar_runtime.lip_sync(payload))


@app.post("/stt")
async def transcribe(payload: AvatarTranscriptionRequest) -> dict[str, Any]:
    _require_stage("stt")
    return await _run_stage("stt", lambda: local_avatar_runtime.transcribe(payload.model_dump()))


@app.post("/image")
async def generate_image(payload: AvatarImageRequest) -> dict[str, Any]:
    _require_stage("image")
    return await _run_stage("image", lambda: local_avatar_runtime.generate_image(payload.model_dump()))


@app.post("/postprocess-image")
async def postprocess_image(payload: AvatarPostprocessImageRequest) -> dict[str, Any]:
    _require_stage("postprocess")
    return await _run_stage(
        "postprocess",
        lambda: local_avatar_runtime.postprocess_image(payload.model_dump()),
    )


async def _run_stage(stage: str, operation: Callable[[], Awaitable[T]]) -> T:
    try:
        return await gpu_queue.run(operation)
    except LocalAvatarRuntimeError as exc:
        raise HTTPException(
            status_code=503,
            detail={
                "stage": exc.stage or stage,
                "message": str(exc),
                "setupHint": exc.setup_hint,
            },
        ) from exc


def _require_stage(stage: str) -> None:
    if stage not in enabled_stages:
        raise HTTPException(status_code=404, detail=f"Worker stage is disabled: {stage}")


def _require_consent(payload: dict[str, Any]) -> None:
    profile = payload.get("founderAvatarProfile") or {}
    if not isinstance(profile, dict):
        profile = {}
    confirmed = bool(
        payload.get("consentConfirmed")
        or profile.get("consentConfirmed")
        or profile.get("consent_confirmed")
    )
    if not confirmed:
        raise HTTPException(
            status_code=403,
            detail="Founder consent is required before avatar, voice, or lip-sync processing.",
        )
