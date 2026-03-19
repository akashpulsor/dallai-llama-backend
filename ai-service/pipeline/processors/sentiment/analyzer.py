"""Lightweight sentiment scoring — keyword + EMA."""
import re

POSITIVE = {"thank", "thanks", "great", "good", "excellent", "perfect", "wonderful",
            "happy", "pleased", "appreciate", "helpful", "awesome", "love", "nice", "amazing"}
NEGATIVE = {"bad", "terrible", "awful", "horrible", "angry", "frustrated", "annoyed",
            "disappointed", "unhappy", "worst", "useless", "hate", "stupid", "ridiculous"}
INTENSIFIERS = {"very", "extremely", "really", "absolutely", "totally"}


class SentimentAnalyzer:
    def __init__(self):
        self._history: list[float] = []

    def analyze(self, text: str) -> tuple[float, str]:
        if not text:
            return 0.0, "NEUTRAL"
        words = set(re.findall(r'\w+', text.lower()))
        has_int = bool(words & INTENSIFIERS)
        pos = len(words & POSITIVE)
        neg = len(words & NEGATIVE)
        if pos == 0 and neg == 0:
            score = 0.0
        else:
            score = (pos - neg) / (pos + neg)
            if has_int:
                score *= 1.5
            score = max(-1.0, min(1.0, score))
        self._history.append(score)
        if len(self._history) > 10:
            self._history = self._history[-10:]
        weights = [0.5 ** i for i in range(len(self._history) - 1, -1, -1)]
        avg = sum(s * w for s, w in zip(self._history, weights)) / sum(weights)
        return round(avg, 3), self._label(avg)

    @staticmethod
    def _label(s: float) -> str:
        if s <= -0.6: return "ANGRY"
        if s <= -0.2: return "FRUSTRATED"
        if s <= 0.2: return "NEUTRAL"
        if s <= 0.6: return "HAPPY"
        return "DELIGHTED"

    @property
    def history(self) -> list[float]:
        return list(self._history)
