"""voice-brain configuration."""
from typing import Optional

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_prefix="",
        extra="ignore",
        case_sensitive=False,
    )

    service_name: str = Field("voice-brain", alias="SERVICE_NAME")
    host: str = Field("0.0.0.0", alias="HOST")
    port: int = Field(8601, alias="PORT")
    debug: bool = Field(False, alias="DEBUG")
    log_level: str = Field("INFO", alias="LOG_LEVEL")

    ai_service_url: str = Field(
        "http://ai-service.apps.svc.cluster.local:8601",
        alias="AI_SERVICE_URL",
    )
    pbx_core_url: str = Field(
        "http://pbx-core.apps.svc.cluster.local:8080",
        alias="PBX_CORE_URL",
    )

    db_host: Optional[str] = Field(None, alias="DB_HOST")
    db_port: int = Field(5432, alias="DB_PORT")
    db_name: Optional[str] = Field(None, alias="DB_NAME")
    db_username: Optional[str] = Field(None, alias="DB_USERNAME")
    db_password: Optional[str] = Field(None, alias="DB_PASSWORD")
    database_url: Optional[str] = Field(None, alias="DATABASE_URL")
    db_echo: bool = Field(False, alias="DB_ECHO")
    db_pool_size: int = Field(10, alias="DB_POOL_SIZE")
    db_max_overflow: int = Field(20, alias="DB_MAX_OVERFLOW")

    auth_enabled: bool = Field(False, alias="AUTH_ENABLED")
    auth_allow_unsafe_dev_tokens: bool = Field(
        False,
        alias="AUTH_ALLOW_UNSAFE_DEV_TOKENS",
    )
    keycloak_server_url: Optional[str] = Field(None, alias="KEYCLOAK_SERVER_URL")
    keycloak_realm: Optional[str] = Field(None, alias="KEYCLOAK_REALM")
    keycloak_client_id: Optional[str] = Field(None, alias="KEYCLOAK_CLIENT_ID")
    keycloak_jwks_url: Optional[str] = Field(None, alias="KEYCLOAK_JWKS_URL")
    keycloak_issuer: Optional[str] = Field(None, alias="KEYCLOAK_ISSUER")
    keycloak_audience: Optional[str] = Field(None, alias="KEYCLOAK_AUDIENCE")
    keycloak_algorithms: str = Field("RS256", alias="KEYCLOAK_ALGORITHMS")
    keycloak_required_scopes: str = Field("", alias="KEYCLOAK_REQUIRED_SCOPES")

    default_stt_provider: str = Field("deepgram", alias="DEFAULT_STT_PROVIDER")
    default_tts_provider: str = Field("deepgram", alias="DEFAULT_TTS_PROVIDER")
    default_llm_provider: str = Field("openai", alias="DEFAULT_LLM_PROVIDER")

    openai_api_key: Optional[str] = Field(None, alias="OPENAI_API_KEY")
    openai_stt_model: str = Field("whisper-1", alias="OPENAI_STT_MODEL")
    openai_tts_model: str = Field("tts-1", alias="OPENAI_TTS_MODEL")
    openai_tts_voice: str = Field("alloy", alias="OPENAI_TTS_VOICE")
    openai_llm_model: str = Field("gpt-4o-mini", alias="OPENAI_LLM_MODEL")

    ollama_base_url: str = Field(
        "http://ollama.apps.svc.cluster.local:11434/v1",
        alias="OLLAMA_BASE_URL",
    )
    ollama_llm_model: str = Field("gemma2:2b", alias="OLLAMA_LLM_MODEL")

    google_api_key: Optional[str] = Field(None, alias="GOOGLE_API_KEY")
    google_project_id: Optional[str] = Field(None, alias="GOOGLE_PROJECT_ID")
    google_location: str = Field("global", alias="GOOGLE_LOCATION")
    google_credentials_path: Optional[str] = Field(None, alias="GOOGLE_CREDENTIALS_PATH")
    google_credentials_json: Optional[str] = Field(None, alias="GOOGLE_CREDENTIALS_JSON")
    google_llm_model: str = Field("gemini-flash-lite-latest", alias="GOOGLE_LLM_MODEL")
    google_gemini_live_llm_model: str = Field(
        "models/gemini-2.5-flash-native-audio-latest",
        alias="GOOGLE_GEMINI_LIVE_LLM_MODEL",
    )
    google_gemini_live_voice: str = Field("Charon", alias="GOOGLE_GEMINI_LIVE_VOICE")
    vertex_llm_model: str = Field("gemma3-4b-it", alias="VERTEX_LLM_MODEL")
    google_tts_voice: str = Field(
        "hi-IN-Chirp3-HD-Pulcherrima",
        alias="GOOGLE_TTS_VOICE",
    )
    google_tts_voice_male: str = Field(
        "hi-IN-Chirp3-HD-Puck",
        alias="GOOGLE_TTS_VOICE_MALE",
    )
    google_tts_voice_female: str = Field(
        "hi-IN-Chirp3-HD-Pulcherrima",
        alias="GOOGLE_TTS_VOICE_FEMALE",
    )
    google_tts_mode: str = Field("chirp", alias="GOOGLE_TTS_MODE")
    google_gemini_tts_model: str = Field(
        "gemini-3.1-flash-tts-preview",
        alias="GOOGLE_GEMINI_TTS_MODEL",
    )

    deepgram_api_key: Optional[str] = Field(None, alias="DEEPGRAM_API_KEY")
    deepgram_stt_model: str = Field("nova-2", alias="DEEPGRAM_STT_MODEL")
    deepgram_tts_model: str = Field("aura-asteria-en", alias="DEEPGRAM_TTS_MODEL")

    voicebrain_rtp_port: int = Field(5555, alias="VOICEBRAIN_RTP_PORT")

    rvc_enabled: bool = Field(False, alias="RVC_ENABLED")
    rvc_server_url: str = Field(
        "http://rvc.apps.svc.cluster.local:8700",
        alias="RVC_SERVER_URL",
    )
    rvc_timeout_ms: int = Field(100, alias="RVC_TIMEOUT_MS")

    avatar_generation_enabled: bool = Field(False, alias="AVATAR_GENERATION_ENABLED")
    avatar_generation_runner_url: Optional[str] = Field(None, alias="AVATAR_GENERATION_RUNNER_URL")
    avatar_generation_runner_path: str = Field("/generate", alias="AVATAR_GENERATION_RUNNER_PATH")
    avatar_voice_runner_path: str = Field("/voice", alias="AVATAR_VOICE_RUNNER_PATH")
    avatar_lipsync_runner_path: str = Field("/lip-sync", alias="AVATAR_LIPSYNC_RUNNER_PATH")
    avatar_stt_runner_path: str = Field("/stt", alias="AVATAR_STT_RUNNER_PATH")
    avatar_image_runner_path: str = Field("/image", alias="AVATAR_IMAGE_RUNNER_PATH")
    avatar_postprocess_runner_path: str = Field("/postprocess-image", alias="AVATAR_POSTPROCESS_RUNNER_PATH")
    avatar_generation_timeout_seconds: float = Field(600.0, alias="AVATAR_GENERATION_TIMEOUT_SECONDS")
    avatar_generation_gpu_profile: str = Field("rtx_4060_8gb", alias="AVATAR_GENERATION_GPU_PROFILE")
    avatar_voice_model: str = Field("fal_minimax_voice_clone", alias="AVATAR_VOICE_MODEL")
    avatar_rvc_profile_root: str = Field("./models/avatar/rvc", alias="AVATAR_RVC_PROFILE_ROOT")
    avatar_rvc_default_profile_id: str = Field(
        "founder_female_v1",
        alias="AVATAR_RVC_DEFAULT_PROFILE_ID",
    )
    avatar_rvc_applio_root: str = Field(
        "/home/kash/female-voice-clone-runtime/Applio",
        alias="AVATAR_RVC_APPLIO_ROOT",
    )
    avatar_rvc_wsl_venv: str = Field(
        "~/.venvs/indic-voice-benchmark",
        alias="AVATAR_RVC_WSL_VENV",
    )
    avatar_rvc_command: str = Field("", alias="AVATAR_RVC_COMMAND")
    avatar_talking_model: str = Field("source_video", alias="AVATAR_TALKING_MODEL")
    avatar_image_model: str = Field("gemini_storyboard", alias="AVATAR_IMAGE_MODEL")
    avatar_lighting_model: str = Field("ic_lightning", alias="AVATAR_LIGHTING_MODEL")
    avatar_video_model: str = Field("ltx_video", alias="AVATAR_VIDEO_MODEL")
    avatar_lipsync_model: str = Field("fal_latentsync", alias="AVATAR_LIPSYNC_MODEL")
    fal_api_key: Optional[str] = Field(None, alias="FAL_KEY")
    avatar_fal_voice_endpoint: str = Field("fal-ai/minimax/voice-clone", alias="AVATAR_FAL_VOICE_ENDPOINT")
    avatar_fal_tts_endpoint: str = Field("fal-ai/minimax/speech-02-hd", alias="AVATAR_FAL_TTS_ENDPOINT")
    avatar_fal_elevenlabs_tts_endpoint: str = Field(
        "fal-ai/elevenlabs/tts/eleven-v3",
        alias="AVATAR_FAL_ELEVENLABS_TTS_ENDPOINT",
    )
    avatar_fal_chatterbox_endpoint: str = Field(
        "fal-ai/chatterbox/text-to-speech/multilingual",
        alias="AVATAR_FAL_CHATTERBOX_ENDPOINT",
    )
    avatar_fal_echomimic_endpoint: str = Field("fal-ai/echomimic-v3", alias="AVATAR_FAL_ECHOMIMIC_ENDPOINT")
    avatar_fal_echomimic_usd_per_second: float = Field(
        0.20,
        alias="AVATAR_FAL_ECHOMIMIC_USD_PER_SECOND",
    )
    avatar_fal_happy_horse_endpoint: str = Field(
        "alibaba/happy-horse/v1.1/image-to-video",
        alias="AVATAR_FAL_HAPPY_HORSE_ENDPOINT",
    )
    avatar_fal_happy_horse_720p_usd_per_second: float = Field(
        0.14,
        alias="AVATAR_FAL_HAPPY_HORSE_720P_USD_PER_SECOND",
    )
    avatar_fal_happy_horse_1080p_usd_per_second: float = Field(
        0.18,
        alias="AVATAR_FAL_HAPPY_HORSE_1080P_USD_PER_SECOND",
    )
    avatar_fal_heygen_avatar4_endpoint: str = Field(
        "fal-ai/heygen/avatar4/image-to-video",
        alias="AVATAR_FAL_HEYGEN_AVATAR4_ENDPOINT",
    )
    avatar_fal_heygen_avatar4_usd_per_second: float = Field(
        0.10,
        alias="AVATAR_FAL_HEYGEN_AVATAR4_USD_PER_SECOND",
    )
    avatar_fal_liveportrait_endpoint: str = Field("fal-ai/live-portrait", alias="AVATAR_FAL_LIVEPORTRAIT_ENDPOINT")
    avatar_fal_lipsync_endpoint: str = Field("fal-ai/latentsync", alias="AVATAR_FAL_LIPSYNC_ENDPOINT")
    avatar_fal_musetalk_endpoint: str = Field("fal-ai/musetalk", alias="AVATAR_FAL_MUSETALK_ENDPOINT")
    avatar_fal_video_edit_endpoint: str = Field(
        "fal-ai/kling-video/o1/standard/video-to-video/edit",
        alias="AVATAR_FAL_VIDEO_EDIT_ENDPOINT",
    )
    avatar_fal_video_edit_usd_per_second: float = Field(0.126, alias="AVATAR_FAL_VIDEO_EDIT_USD_PER_SECOND")
    avatar_fal_media_expiration_seconds: int = Field(3600, alias="AVATAR_FAL_MEDIA_EXPIRATION_SECONDS")
    avatar_fal_store_io: bool = Field(False, alias="AVATAR_FAL_STORE_IO")
    avatar_voice_fallback_command: str = Field("", alias="AVATAR_VOICE_FALLBACK_COMMAND")
    avatar_proprietary_api_timeout_seconds: float = Field(900.0, alias="AVATAR_PROPRIETARY_API_TIMEOUT_SECONDS")
    avatar_local_runtime_enabled: bool = Field(False, alias="AVATAR_LOCAL_RUNTIME_ENABLED")
    avatar_model_root: str = Field("./models/avatar", alias="AVATAR_MODEL_ROOT")
    avatar_work_root: str = Field("./work/avatar", alias="AVATAR_WORK_ROOT")
    avatar_download_timeout_seconds: float = Field(300.0, alias="AVATAR_DOWNLOAD_TIMEOUT_SECONDS")
    avatar_stage_timeout_seconds: float = Field(1200.0, alias="AVATAR_STAGE_TIMEOUT_SECONDS")
    avatar_allow_test_fallback: bool = Field(False, alias="AVATAR_ALLOW_TEST_FALLBACK")
    avatar_cosyvoice_repo: str = Field("", alias="AVATAR_COSYVOICE_REPO")
    avatar_cosyvoice2_model_dir: str = Field("", alias="AVATAR_COSYVOICE2_MODEL_DIR")
    avatar_cosyvoice2_command: str = Field("", alias="AVATAR_COSYVOICE2_COMMAND")
    avatar_elevenlabs_api_key: Optional[str] = Field(None, alias="AVATAR_ELEVENLABS_API_KEY")
    avatar_elevenlabs_base_url: str = Field("https://api.elevenlabs.io", alias="AVATAR_ELEVENLABS_BASE_URL")
    avatar_elevenlabs_voice_id: str = Field("", alias="AVATAR_ELEVENLABS_VOICE_ID")
    avatar_elevenlabs_model_id: str = Field("eleven_v3", alias="AVATAR_ELEVENLABS_MODEL_ID")
    avatar_elevenlabs_output_format: str = Field("mp3_44100_128", alias="AVATAR_ELEVENLABS_OUTPUT_FORMAT")
    avatar_elevenlabs_usd_per_million_credits: float = Field(
        30.0,
        alias="AVATAR_ELEVENLABS_USD_PER_MILLION_CREDITS",
    )
    avatar_elevenlabs_remove_background_noise: bool = Field(
        True,
        alias="AVATAR_ELEVENLABS_REMOVE_BACKGROUND_NOISE",
    )
    avatar_elevenlabs_voice_command: str = Field("", alias="AVATAR_ELEVENLABS_VOICE_COMMAND")
    avatar_sarvam_api_key: Optional[str] = Field(None, alias="AVATAR_SARVAM_API_KEY")
    avatar_sarvam_base_url: str = Field("https://api.sarvam.ai", alias="AVATAR_SARVAM_BASE_URL")
    avatar_sarvam_voice_clone_path: str = Field("", alias="AVATAR_SARVAM_VOICE_CLONE_PATH")
    avatar_sarvam_tts_path: str = Field("/text-to-speech", alias="AVATAR_SARVAM_TTS_PATH")
    avatar_sarvam_voice_id: str = Field("", alias="AVATAR_SARVAM_VOICE_ID")
    avatar_sarvam_model_id: str = Field("bulbul:v3", alias="AVATAR_SARVAM_MODEL_ID")
    avatar_sarvam_output_codec: str = Field("wav", alias="AVATAR_SARVAM_OUTPUT_CODEC")
    avatar_sarvam_sample_rate: int = Field(48000, alias="AVATAR_SARVAM_SAMPLE_RATE")
    avatar_liveportrait_repo: str = Field("", alias="AVATAR_LIVEPORTRAIT_REPO")
    avatar_liveportrait_command: str = Field("", alias="AVATAR_LIVEPORTRAIT_COMMAND")
    avatar_echomimic_repo: str = Field("", alias="AVATAR_ECHOMIMIC_REPO")
    avatar_echomimic_command: str = Field("", alias="AVATAR_ECHOMIMIC_COMMAND")
    avatar_musetalk_repo: str = Field("", alias="AVATAR_MUSETALK_REPO")
    avatar_musetalk_command: str = Field("", alias="AVATAR_MUSETALK_COMMAND")
    avatar_latentsync_repo: str = Field("", alias="AVATAR_LATENTSYNC_REPO")
    avatar_latentsync_command: str = Field("", alias="AVATAR_LATENTSYNC_COMMAND")
    avatar_lipsync_fallback_command: str = Field("", alias="AVATAR_LIPSYNC_FALLBACK_COMMAND")
    avatar_sync_labs_api_key: Optional[str] = Field(None, alias="AVATAR_SYNC_LABS_API_KEY")
    avatar_sync_labs_base_url: str = Field("https://api.sync.so", alias="AVATAR_SYNC_LABS_BASE_URL")
    avatar_sync_labs_model: str = Field("lipsync-2-pro", alias="AVATAR_SYNC_LABS_MODEL")
    avatar_sync_labs_command: str = Field("", alias="AVATAR_SYNC_LABS_COMMAND")
    avatar_flux_model_id: str = Field("black-forest-labs/FLUX.1-dev", alias="AVATAR_FLUX_MODEL_ID")
    avatar_flux_command: str = Field("", alias="AVATAR_FLUX_COMMAND")
    avatar_stt_model: str = Field("small", alias="AVATAR_STT_MODEL")
    avatar_stt_device: str = Field("cuda", alias="AVATAR_STT_DEVICE")
    avatar_stt_compute_type: str = Field("float16", alias="AVATAR_STT_COMPUTE_TYPE")
    avatar_caption_engine: str = Field("faster_whisper", alias="AVATAR_CAPTION_ENGINE")
    avatar_whisperx_command: str = Field("", alias="AVATAR_WHISPERX_COMMAND")
    avatar_birefnet_model_id: str = Field("ZhengPeng7/BiRefNet", alias="AVATAR_BIREFNET_MODEL_ID")
    avatar_birefnet_command: str = Field("", alias="AVATAR_BIREFNET_COMMAND")
    avatar_realesrgan_executable: str = Field("", alias="AVATAR_REALESRGAN_EXECUTABLE")
    avatar_realesrgan_command: str = Field("", alias="AVATAR_REALESRGAN_COMMAND")
    avatar_realesrgan_model: str = Field("realesrgan-x4plus", alias="AVATAR_REALESRGAN_MODEL")
    avatar_codeformer_repo: str = Field("", alias="AVATAR_CODEFORMER_REPO")
    avatar_codeformer_command: str = Field("", alias="AVATAR_CODEFORMER_COMMAND")
    avatar_video_background_removal_command: str = Field("", alias="AVATAR_VIDEO_BACKGROUND_REMOVAL_COMMAND")
    avatar_video_upscale_command: str = Field("", alias="AVATAR_VIDEO_UPSCALE_COMMAND")
    avatar_video_face_restore_command: str = Field("", alias="AVATAR_VIDEO_FACE_RESTORE_COMMAND")

    sample_rate: int = Field(16000, alias="SAMPLE_RATE")
    audio_normalize: bool = Field(True, alias="AUDIO_NORMALIZE")
    audio_target_lufs: float = Field(-16.0, alias="AUDIO_TARGET_LUFS")
    audio_highpass_hz: int = Field(80, alias="AUDIO_HIGHPASS_HZ")
    audio_limiter_threshold: float = Field(0.95, alias="AUDIO_LIMITER_THRESHOLD")

    @model_validator(mode="after")
    def build_database_url(self) -> "Settings":
        if not self.database_url and all(
            [self.db_host, self.db_name, self.db_username, self.db_password]
        ):
            self.database_url = (
                f"postgresql+asyncpg://{self.db_username}:{self.db_password}"
                f"@{self.db_host}:{self.db_port}/{self.db_name}"
            )
        return self

    @model_validator(mode="after")
    def validate_auth_config(self) -> "Settings":
        if self.auth_enabled:
            if not self.keycloak_client_id:
                raise ValueError(
                    "AUTH_ENABLED=true but missing required setting: KEYCLOAK_CLIENT_ID"
                )
            has_explicit_urls = bool(self.keycloak_jwks_url and self.keycloak_issuer)
            has_derivable_urls = bool(self.keycloak_server_url and self.keycloak_realm)
            if not (has_explicit_urls or has_derivable_urls):
                raise ValueError(
                    "AUTH_ENABLED=true but missing Keycloak endpoint settings. "
                    "Provide either KEYCLOAK_JWKS_URL and KEYCLOAK_ISSUER, "
                    "or KEYCLOAK_SERVER_URL and KEYCLOAK_REALM."
                )
        return self


settings = Settings()
