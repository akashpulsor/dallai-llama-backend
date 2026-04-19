"""
UDP RTP receiver for agent-call passive transcription.

RTPEngine forks (copies) RTP audio to this server on port 5555.
Each packet carries an SSRC that maps to a registered call via session_registry.

Flow:
  1. Parse RTP header (RFC 3550): SSRC, payload type, sequence number
  2. Decode audio payload (PCMU=0, PCMA=8 → PCM16 via audioop)
  3. Buffer ~200-300ms of PCM per SSRC in RtpStreamContext
  4. When buffer threshold reached, feed to streaming STT
  5. On STT result, POST to PBX-Core via pbx_core_client
  6. On 5s silence, close STT stream and send final transcript
  7. Cleanup sessions older than 2 hours

Does NOT send audio back — this is purely passive/listen-only.
"""
import asyncio
import audioop
import logging
import struct
import time
from dataclasses import dataclass, field

from config import settings
from pipeline.callbacks import pbx_core_client
from pipeline.processors.sentiment.analyzer import SentimentAnalyzer
from rtp.session_registry import registry, RtpSession

logger = logging.getLogger(__name__)

# RTP constants
RTP_HEADER_SIZE = 12
PAYLOAD_PCMU = 0
PAYLOAD_PCMA = 8
SUPPORTED_PAYLOADS = {PAYLOAD_PCMU, PAYLOAD_PCMA}

# Timing
BUFFER_MS = 200
SILENCE_TIMEOUT_SECS = 5.0
CLEANUP_INTERVAL_SECS = 30.0
SAMPLE_RATE = 8000  # G.711 is always 8kHz
BYTES_PER_SAMPLE = 2  # PCM16
BUFFER_BYTES = int(SAMPLE_RATE * BYTES_PER_SAMPLE * BUFFER_MS / 1000)  # ~3200 bytes


@dataclass
class RtpStreamContext:
    """Per-SSRC stream state."""
    ssrc: int
    call_id: str
    tenant_id: str
    speaker: str  # "caller" or "agent"
    audio_buffer: bytearray = field(default_factory=bytearray)
    last_packet_time: float = field(default_factory=time.time)
    transcript_buffer: list[dict] = field(default_factory=list)
    sentiment: SentimentAnalyzer = field(default_factory=SentimentAnalyzer)
    sequence_last: int = 0
    closed: bool = False


# Active streams indexed by SSRC
_streams: dict[int, RtpStreamContext] = {}
_streams_lock = asyncio.Lock()


def _parse_rtp_header(data: bytes) -> tuple[int, int, int, int] | None:
    """Parse RTP header, returns (payload_type, sequence, timestamp, ssrc) or None."""
    if len(data) < RTP_HEADER_SIZE:
        return None
    first_byte = data[0]
    version = (first_byte >> 6) & 0x03
    if version != 2:
        return None
    cc = first_byte & 0x0F
    second_byte = data[1]
    payload_type = second_byte & 0x7F
    sequence, timestamp, ssrc = struct.unpack("!HII", data[2:12])
    # Skip CSRC identifiers
    header_len = RTP_HEADER_SIZE + cc * 4
    if len(data) < header_len:
        return None
    return payload_type, sequence, ssrc, header_len


def _decode_payload(payload: bytes, payload_type: int) -> bytes:
    """Decode G.711 payload to PCM16 linear."""
    if payload_type == PAYLOAD_PCMU:
        return audioop.ulaw2lin(payload, 2)
    elif payload_type == PAYLOAD_PCMA:
        return audioop.alaw2lin(payload, 2)
    return b""


async def _get_or_create_stream(ssrc: int) -> RtpStreamContext | None:
    """Look up SSRC in session registry and create stream context if new."""
    async with _streams_lock:
        if ssrc in _streams:
            return _streams[ssrc]

    session, speaker = registry.lookup_ssrc(ssrc)
    if session is None:
        return None

    ctx = RtpStreamContext(
        ssrc=ssrc,
        call_id=session.call_id,
        tenant_id=session.tenant_id,
        speaker=speaker or "caller",
    )
    async with _streams_lock:
        # Double-check after lock
        if ssrc in _streams:
            return _streams[ssrc]
        _streams[ssrc] = ctx
    logger.info("RTP stream started: ssrc=%s call=%s speaker=%s", ssrc, session.call_id, speaker)
    return ctx


async def _flush_buffer(ctx: RtpStreamContext):
    """Send buffered PCM to STT via PBX-Core live transcript callback."""
    if not ctx.audio_buffer or ctx.closed:
        return

    # For now, we don't run a local streaming STT here.
    # Instead we use the Deepgram REST API for short-form recognition.
    # This keeps the implementation simple and avoids managing persistent
    # WebSocket STT connections per SSRC.
    pcm_data = bytes(ctx.audio_buffer)
    ctx.audio_buffer.clear()

    # Fire STT request asynchronously
    asyncio.create_task(_transcribe_chunk(ctx, pcm_data))


async def _transcribe_chunk(ctx: RtpStreamContext, pcm_data: bytes):
    """Transcribe a PCM chunk via Deepgram REST API and relay to PBX-Core."""
    try:
        import httpx

        deepgram_key = settings.deepgram_api_key
        if not deepgram_key:
            return

        # Deepgram prerecorded endpoint accepts raw PCM
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post(
                "https://api.deepgram.com/v1/listen",
                params={
                    "model": settings.deepgram_stt_model,
                    "encoding": "linear16",
                    "sample_rate": str(SAMPLE_RATE),
                    "channels": "1",
                    "punctuate": "true",
                    "smart_format": "true",
                },
                headers={
                    "Authorization": f"Token {deepgram_key}",
                    "Content-Type": "application/octet-stream",
                },
                content=pcm_data,
            )
            if resp.status_code != 200:
                logger.warning("Deepgram STT failed: status=%s body=%s", resp.status_code, resp.text[:200])
                return
            result = resp.json()

        # Extract transcript
        alternatives = (
            result.get("results", {})
            .get("channels", [{}])[0]
            .get("alternatives", [])
        )
        if not alternatives:
            return

        text = alternatives[0].get("transcript", "").strip()
        confidence = alternatives[0].get("confidence", 0.0)
        if not text:
            return

        ts_ms = int(time.time() * 1000)

        # Buffer for final transcript assembly
        ctx.transcript_buffer.append({
            "speaker": ctx.speaker.upper(),
            "text": text,
            "timestamp_ms": ts_ms,
        })

        # Relay to PBX-Core (fire-and-forget)
        asyncio.create_task(pbx_core_client.send_live_transcript(
            call_id=ctx.call_id, tenant_id=ctx.tenant_id,
            speaker=ctx.speaker.upper(), text=text, is_final=True,
            confidence=confidence, timestamp_ms=ts_ms,
        ))

        # Sentiment
        score, label = ctx.sentiment.analyze(text)
        asyncio.create_task(pbx_core_client.send_sentiment(
            call_id=ctx.call_id, tenant_id=ctx.tenant_id,
            score=score, label=label, emotion_history=ctx.sentiment.history,
        ))

        logger.debug("RTP STT: ssrc=%s call=%s speaker=%s text=%s", ctx.ssrc, ctx.call_id, ctx.speaker, text[:100])

    except Exception as e:
        logger.warning("RTP transcribe failed: ssrc=%s err=%s", ctx.ssrc, e)


async def _close_stream(ssrc: int, reason: str = "silence"):
    """Finalize a stream — send final transcript and clean up."""
    async with _streams_lock:
        ctx = _streams.pop(ssrc, None)
    if not ctx or ctx.closed:
        return

    ctx.closed = True

    # Flush any remaining buffer
    if ctx.audio_buffer:
        await _flush_buffer(ctx)
        # Give a moment for the last transcription to complete
        await asyncio.sleep(0.5)

    # Send final transcript
    if ctx.transcript_buffer:
        asyncio.create_task(pbx_core_client.send_final_transcript(
            call_id=ctx.call_id,
            transcript_summary=f"Agent call transcript ({len(ctx.transcript_buffer)} utterances)",
            ai_minutes=0,
            total_turns=len(ctx.transcript_buffer),
            tenant_id=ctx.tenant_id,
            full_transcript=ctx.transcript_buffer,
        ))

    logger.info("RTP stream closed: ssrc=%s call=%s reason=%s utterances=%s",
                ssrc, ctx.call_id, reason, len(ctx.transcript_buffer))


async def close_streams_for_call(call_id: str):
    """Close all streams belonging to a call (called on session deregister)."""
    to_close = []
    async with _streams_lock:
        for ssrc, ctx in _streams.items():
            if ctx.call_id == call_id:
                to_close.append(ssrc)
    for ssrc in to_close:
        await _close_stream(ssrc, reason="session_deregistered")


class RtpProtocol(asyncio.DatagramProtocol):
    """asyncio UDP protocol that receives RTP packets."""

    def __init__(self, loop: asyncio.AbstractEventLoop):
        self._loop = loop

    def connection_made(self, transport):
        self._transport = transport

    def datagram_received(self, data: bytes, addr):
        # Schedule async processing
        self._loop.create_task(self._handle_packet(data))

    async def _handle_packet(self, data: bytes):
        parsed = _parse_rtp_header(data)
        if not parsed:
            return

        payload_type, sequence, ssrc, header_len = parsed
        if payload_type not in SUPPORTED_PAYLOADS:
            return

        payload = data[header_len:]
        if not payload:
            return

        ctx = await _get_or_create_stream(ssrc)
        if ctx is None:
            return  # Unknown SSRC — not registered via /api/rtp-sessions

        # Decode G.711 to PCM16
        pcm = _decode_payload(payload, payload_type)
        if not pcm:
            return

        ctx.audio_buffer.extend(pcm)
        ctx.last_packet_time = time.time()
        ctx.sequence_last = sequence

        # Flush when buffer is large enough (~200ms of audio)
        if len(ctx.audio_buffer) >= BUFFER_BYTES:
            await _flush_buffer(ctx)


async def _silence_monitor():
    """Periodically check for streams with no recent packets and close them."""
    while True:
        await asyncio.sleep(CLEANUP_INTERVAL_SECS)
        now = time.time()
        to_close = []
        async with _streams_lock:
            for ssrc, ctx in _streams.items():
                if now - ctx.last_packet_time > SILENCE_TIMEOUT_SECS:
                    to_close.append(ssrc)

        for ssrc in to_close:
            await _close_stream(ssrc, reason="silence_timeout")

        # Also clean stale sessions in the registry
        registry.cleanup_stale()


async def start_rtp_server(port: int):
    """Start the UDP RTP receiver on the given port."""
    loop = asyncio.get_running_loop()
    transport, protocol = await loop.create_datagram_endpoint(
        lambda: RtpProtocol(loop),
        local_addr=("0.0.0.0", port),
    )
    logger.info("RTP UDP server started on port %s", port)

    # Start background silence monitor
    asyncio.create_task(_silence_monitor())

    return transport
