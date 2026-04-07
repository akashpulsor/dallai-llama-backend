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
    google_llm_model: str = Field("gemini-2.5-flash-lite", alias="GOOGLE_LLM_MODEL")
    google_gemini_live_llm_model: str = Field(
        "models/gemini-2.5-flash-native-audio-preview-12-2025",
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
        "gemini-2.5-flash-tts",
        alias="GOOGLE_GEMINI_TTS_MODEL",
    )

    deepgram_api_key: Optional[str] = Field(None, alias="DEEPGRAM_API_KEY")
    deepgram_stt_model: str = Field("nova-2", alias="DEEPGRAM_STT_MODEL")
    deepgram_tts_model: str = Field("aura-asteria-en", alias="DEEPGRAM_TTS_MODEL")

    rvc_enabled: bool = Field(False, alias="RVC_ENABLED")
    rvc_server_url: str = Field(
        "http://rvc.apps.svc.cluster.local:8700",
        alias="RVC_SERVER_URL",
    )
    rvc_timeout_ms: int = Field(100, alias="RVC_TIMEOUT_MS")

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
