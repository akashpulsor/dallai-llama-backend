"""
RTP session registration endpoints — called by PBX-Core.

POST /api/rtp-sessions   → register SSRC mapping before RTP fork starts
DELETE /api/rtp-sessions/{call_id}  → cleanup on call hangup
"""
import logging
from fastapi import APIRouter
from pydantic import BaseModel

from rtp.session_registry import registry
from rtp.udp_server import close_streams_for_call

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/rtp-sessions", tags=["rtp"])


class RtpSessionRequest(BaseModel):
    call_id: str
    tenant_id: str
    ssrc_caller: str
    ssrc_agent: str


@router.post("", status_code=200)
async def register_rtp_session(req: RtpSessionRequest):
    """
    PBX-Core calls this on CHANNEL_BRIDGE when AI fork is active.
    Registers the SSRC→call mapping so the UDP receiver can route packets.
    """
    registry.register(
        call_id=req.call_id,
        tenant_id=req.tenant_id,
        ssrc_caller=req.ssrc_caller,
        ssrc_agent=req.ssrc_agent,
    )
    return {
        "status": "registered",
        "call_id": req.call_id,
        "ssrc_caller": req.ssrc_caller,
        "ssrc_agent": req.ssrc_agent,
    }


@router.delete("/{call_id}", status_code=200)
async def deregister_rtp_session(call_id: str):
    """
    PBX-Core calls this on CHANNEL_HANGUP to clean up.
    Closes any active STT streams and removes the session.
    """
    # Close active audio streams first (sends final transcript)
    await close_streams_for_call(call_id)
    session = registry.deregister(call_id)
    if session:
        return {"status": "deregistered", "call_id": call_id}
    return {"status": "not_found", "call_id": call_id}
