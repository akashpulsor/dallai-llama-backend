"""
RTP session registry — maps SSRC to call/tenant metadata.

PBX-Core registers sessions via POST /api/rtp-sessions when an agent call
is bridged. The UDP receiver uses this mapping to determine which call/tenant
an incoming RTP packet belongs to.
"""
import logging
import time
from dataclasses import dataclass, field
from threading import Lock

logger = logging.getLogger(__name__)

MAX_SESSION_AGE_SECONDS = 7200  # 2-hour safety net


@dataclass
class RtpSession:
    call_id: str
    tenant_id: str
    ssrc_caller: str
    ssrc_agent: str
    created_at: float = field(default_factory=time.time)


class RtpSessionRegistry:
    """Thread-safe registry of active RTP sessions, indexed by SSRC."""

    def __init__(self):
        self._lock = Lock()
        # call_id -> RtpSession
        self._sessions: dict[str, RtpSession] = {}
        # ssrc (int) -> (call_id, speaker)
        self._ssrc_map: dict[int, tuple[str, str]] = {}

    def register(self, call_id: str, tenant_id: str, ssrc_caller: str, ssrc_agent: str):
        session = RtpSession(
            call_id=call_id,
            tenant_id=tenant_id,
            ssrc_caller=ssrc_caller,
            ssrc_agent=ssrc_agent,
        )
        with self._lock:
            self._sessions[call_id] = session
            # Map each SSRC int to (call_id, speaker_label)
            try:
                self._ssrc_map[int(ssrc_caller)] = (call_id, "caller")
            except (ValueError, TypeError):
                logger.warning("Invalid ssrc_caller=%s for call=%s", ssrc_caller, call_id)
            try:
                self._ssrc_map[int(ssrc_agent)] = (call_id, "agent")
            except (ValueError, TypeError):
                logger.warning("Invalid ssrc_agent=%s for call=%s", ssrc_agent, call_id)

        logger.info(
            "RTP session registered: call=%s tenant=%s ssrc_caller=%s ssrc_agent=%s",
            call_id, tenant_id, ssrc_caller, ssrc_agent,
        )

    def deregister(self, call_id: str) -> RtpSession | None:
        with self._lock:
            session = self._sessions.pop(call_id, None)
            if session:
                try:
                    self._ssrc_map.pop(int(session.ssrc_caller), None)
                except (ValueError, TypeError):
                    pass
                try:
                    self._ssrc_map.pop(int(session.ssrc_agent), None)
                except (ValueError, TypeError):
                    pass
                logger.info("RTP session deregistered: call=%s", call_id)
            return session

    def lookup_ssrc(self, ssrc: int) -> tuple[RtpSession | None, str | None]:
        """Returns (session, speaker) for a given SSRC, or (None, None)."""
        with self._lock:
            entry = self._ssrc_map.get(ssrc)
            if not entry:
                return None, None
            call_id, speaker = entry
            session = self._sessions.get(call_id)
            return session, speaker

    def get_session(self, call_id: str) -> RtpSession | None:
        with self._lock:
            return self._sessions.get(call_id)

    @property
    def active_count(self) -> int:
        return len(self._sessions)

    def cleanup_stale(self):
        """Remove sessions older than MAX_SESSION_AGE_SECONDS."""
        now = time.time()
        stale = []
        with self._lock:
            for call_id, session in self._sessions.items():
                if now - session.created_at > MAX_SESSION_AGE_SECONDS:
                    stale.append(call_id)
        for call_id in stale:
            self.deregister(call_id)
            logger.warning("Stale RTP session cleaned up: call=%s", call_id)


# Global singleton
registry = RtpSessionRegistry()
