"""Per-call context — everything about an active call."""
from dataclasses import dataclass, field
from typing import Optional
from datetime import datetime, timezone


@dataclass
class ProviderConfig:
    stt_provider: str = "deepgram"
    stt_model: Optional[str] = None
    stt_api_key: Optional[str] = None
    tts_provider: str = "openai"
    tts_model: Optional[str] = None
    tts_voice: str = "alloy"
    tts_api_key: Optional[str] = None
    llm_provider: str = "openai"
    llm_model: Optional[str] = None
    llm_api_key: Optional[str] = None
    rvc_enabled: bool = False
    rvc_model_id: Optional[str] = None


@dataclass
class BotConfig:
    bot_id: Optional[str] = None
    name: str = "Assistant"
    system_prompt: str = "You are a helpful assistant."
    greeting_message: str = "Hello, how can I help you?"
    goodbye_message: str = "Thank you for calling. Goodbye!"
    guidelines: list[str] = field(default_factory=list)
    allowed_intents: list[str] = field(default_factory=list)
    fallback_message: str = "I'm sorry, could you repeat that?"
    escalation_rules: dict = field(default_factory=dict)
    transfer_target: Optional[str] = None
    language: str = "en"
    max_turns: int = 30
    max_duration_seconds: int = 600
    dtmf_enabled: bool = True
    barge_in_enabled: bool = True
    sentiment_tracking: bool = False
    voice_provider: Optional[str] = None
    voice_id: Optional[str] = None
    voice_speed: float = 1.0


@dataclass
class IntentEntry:
    timestamp: datetime
    utterance: str
    intent: str
    confidence: float
    speaker: str


@dataclass
class CallContext:
    call_id: str
    tenant_id: str
    subscription_id: Optional[str] = None
    product_code: str = "BASIC_PBX"
    ai_enabled: bool = False
    stt_enabled: bool = False
    sentiment_enabled: bool = False
    agent_assist_enabled: bool = False
    noise_cancellation_enabled: bool = False
    recording_enabled: bool = False
    providers: ProviderConfig = field(default_factory=ProviderConfig)
    bot: BotConfig = field(default_factory=BotConfig)
    caller_number: str = ""
    callee_number: str = ""
    direction: str = "INBOUND"

    # Campaign + contact context (outbound dialer, populated from PBX-Core)
    campaign: dict = field(default_factory=dict)
    contact: dict = field(default_factory=dict)

    # Conversation state
    conversation_history: list[dict] = field(default_factory=list)
    turn_count: int = 0
    started_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    intent_log: list[IntentEntry] = field(default_factory=list)
    current_sentiment: float = 0.0
    sentiment_history: list[float] = field(default_factory=list)

    @property
    def is_bot_mode(self) -> bool:
        return self.product_code in ("CONV_IVR", "OUTBOUND_DIALER", "VIRTUAL_RECEPTIONIST")

    @property
    def is_agent_assist_mode(self) -> bool:
        return self.product_code == "AI_CC" and self.agent_assist_enabled

    @property
    def is_transcript_only(self) -> bool:
        return not self.ai_enabled and self.stt_enabled

    def add_intent(self, utterance: str, intent: str, confidence: float, speaker: str = "CALLER"):
        self.intent_log.append(IntentEntry(
            timestamp=datetime.now(timezone.utc), utterance=utterance,
            intent=intent, confidence=confidence, speaker=speaker,
        ))

    def add_conversation_turn(self, role: str, content: str):
        self.conversation_history.append({"role": role, "content": content})
        if role == "assistant":
            self.turn_count += 1
