"""voice-brain configuration."""
from typing import Optional
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_prefix="VB_",
        extra="ignore",
    )

    service_name: str = "voice-brain"
    host: str = "0.0.0.0"
    port: int = 8601
    debug: bool = False
    log_level: str = "INFO"

    pbx_core_url: str = "http://pbx-core.internal:8080"
    service_public_url: str = "http://voice-brain:8601"
    database_url: Optional[str] = None
    db_echo: bool = False
    db_pool_size: int = 10
    db_max_overflow: int = 20

    # Default providers
    default_stt_provider: str = "deepgram"
    default_tts_provider: str = "deepgram"
    default_llm_provider: str = "openai"

    # OpenAI
    openai_api_key: Optional[str] = None
    openai_stt_model: str = "whisper-1"
    openai_tts_model: str = "tts-1"
    openai_tts_voice: str = "alloy"
    openai_llm_model: str = "gpt-4o-mini"

    # Ollama
    ollama_base_url: str = "http://localhost:11434/v1"
    ollama_llm_model: str = "gemma2:2b"

    # Local Indic TTS test server
    indic_tts_base_url: str = "http://127.0.0.1:5005/synthesize"
    indic_tts_sample_rate: int = 16000
    indic_tts_voice: str = "female"
    indic_tts_emotion: str = "neutral"

    # Deepgram
    deepgram_api_key: Optional[str] = None
    deepgram_stt_model: str = "nova-2"
    deepgram_tts_model: str = "aura-asteria-en"

    # RVC voice cloning server
    rvc_enabled: bool = False
    rvc_server_url: str = "http://localhost:8700"
    rvc_timeout_ms: int = 100  # max latency per chunk, skip if exceeded

    # Audio post-processing
    audio_normalize: bool = True
    audio_target_lufs: float = -16.0
    audio_highpass_hz: int = 80
    audio_limiter_threshold: float = 0.95

    sample_rate: int = 16000


settings = Settings()
