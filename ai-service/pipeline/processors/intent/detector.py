"""Intent tracker — rolling EMA + campaign escalation thresholds."""
from dataclasses import dataclass
from datetime import datetime
from typing import Optional
import logging

logger = logging.getLogger(__name__)


@dataclass
class IntentEntry:
    timestamp: datetime
    utterance: str
    intent: str
    confidence: float
    speaker: str
    sentiment_hint: str = "neutral"


class IntentTracker:
    def __init__(self, escalation_intents: dict[str, float] = None,
                 intent_actions: dict[str, dict] = None):
        self.log: list[IntentEntry] = []
        self.escalation_intents = escalation_intents or {}
        self.intent_actions = intent_actions or {}
        self._rolling: dict[str, float] = {}
        self._alpha = 0.4

    def record(self, utterance: str, intent: str, confidence: float,
               speaker: str = "CALLER", sentiment_hint: str = "neutral"):
        self.log.append(IntentEntry(
            timestamp=datetime.utcnow(), utterance=utterance, intent=intent,
            confidence=confidence, speaker=speaker, sentiment_hint=sentiment_hint,
        ))
        prev = self._rolling.get(intent, 0.0)
        self._rolling[intent] = self._alpha * confidence + (1 - self._alpha) * prev

    def check_escalation_threshold(self) -> Optional[tuple[str, str, str]]:
        """Returns (action, target, reason) or None."""
        for intent, threshold in self.escalation_intents.items():
            rolling = self._rolling.get(intent, 0.0)
            if rolling >= threshold:
                cfg = self.intent_actions.get(intent, {})
                action = cfg.get("action", "TRANSFER_QUEUE")
                target = cfg.get("target", "default")
                reason = f"intent_{intent}_threshold_{threshold}_reached_{rolling:.2f}"
                return action, target, reason
        return None

    def get_dominant_intent(self) -> tuple[str, float]:
        if not self.log:
            return "unknown", 0.0
        counts: dict[str, list[float]] = {}
        for e in self.log:
            counts.setdefault(e.intent, []).append(e.confidence)
        best = max(counts, key=lambda i: (len(counts[i]), sum(counts[i]) / len(counts[i])))
        return best, sum(counts[best]) / len(counts[best])

    def build_summary(self, max_entries: int = 20) -> str:
        return "\n".join(
            f"[{e.speaker}:{e.intent}({e.confidence:.2f})] {e.utterance}"
            for e in self.log[-max_entries:]
        )

    def get_intent_distribution(self) -> dict[str, int]:
        dist: dict[str, int] = {}
        for e in self.log:
            dist[e.intent] = dist.get(e.intent, 0) + 1
        return dict(sorted(dist.items(), key=lambda x: x[1], reverse=True))
