"""
Browser test console with live transcript, intent badges, sentiment meter.

Architecture:
  Browser <-> WebSocket /test/audio/{id}  (binary PCM audio, bidirectional)
  Browser -> GET /test/events/{id}        (poll every 300ms for transcript/intent/sentiment)

Events are stored in-memory per call. The pipeline's TranscriptRelayProcessor
normally sends events to PBX-Core via HTTP. For test mode, we also store them locally.
"""
import time
import logging
from collections import defaultdict

from fastapi import APIRouter, WebSocket
from fastapi.responses import HTMLResponse, JSONResponse

logger = logging.getLogger(__name__)
router = APIRouter()

# In-memory event store per call (for test polling)
call_events: dict[str, list[dict]] = defaultdict(list)
event_seq: dict[str, int] = defaultdict(int)


def add_event(call_id: str, event: dict):
    """Called by pipeline processors to store events for test UI polling."""
    event_seq[call_id] += 1
    event["id"] = event_seq[call_id]
    event["ts"] = time.time()
    last = call_events[call_id][-1] if call_events[call_id] else None
    if last:
        same_type = last.get("type") == event.get("type")
        same_text = last.get("text") == event.get("text")
        same_intent = last.get("intent") == event.get("intent")
        close_in_time = (event["ts"] - last.get("ts", 0)) < 2.0
        if same_type and same_text and same_intent and close_in_time:
            return
    call_events[call_id].append(event)
    if len(call_events[call_id]) > 200:
        call_events[call_id] = call_events[call_id][-200:]


@router.get("/test/events/{call_id}")
async def get_events(call_id: str, since: float = 0):
    """Poll endpoint - returns events since timestamp."""
    events = [e for e in call_events.get(call_id, []) if e.get("ts", 0) > since]
    return JSONResponse({"events": events})


TEST_HTML = """<!DOCTYPE html>
<html><head><title>voice-brain Test Console</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:system-ui;background:#0f0f23;color:#e0e0e0;padding:20px}
.c{max-width:900px;margin:0 auto}
h1{color:#7c3aed;margin-bottom:4px}
.sub{color:#888;margin-bottom:20px;font-size:13px}
.cfg{background:#1a1a2e;padding:14px;border-radius:8px;margin-bottom:14px}
.cfg label{display:block;color:#aaa;font-size:11px;margin-bottom:3px}
.cfg input,.cfg select{width:100%;padding:7px;background:#0f0f23;border:1px solid #333;color:#e0e0e0;border-radius:4px;margin-bottom:10px;font-size:13px}
.row{display:flex;gap:10px}.row>div{flex:1}
.ctrls{display:flex;gap:10px;margin-bottom:14px}
button{padding:10px 20px;border:none;border-radius:6px;cursor:pointer;font-size:13px;font-weight:600}
#startBtn{background:#7c3aed;color:#fff}#startBtn:disabled{background:#333}
#stopBtn{background:#dc2626;color:#fff}#stopBtn:disabled{background:#333}
.panels{display:flex;gap:14px}
.panel{flex:1;background:#1a1a2e;border-radius:8px;padding:14px}
.panel h3{font-size:13px;color:#7c3aed;margin-bottom:8px}
.transcript{min-height:350px;max-height:500px;overflow-y:auto;font-size:13px}
.t{margin-bottom:10px;padding:6px 8px;border-radius:4px}
.t.caller{background:#1e293b;border-left:3px solid #3b82f6}
.t.bot{background:#1c1917;border-left:3px solid #7c3aed}
.t.sys{background:transparent;border-left:3px solid #f59e0b;color:#f59e0b;font-size:11px}
.t.escalate{background:#3a1212;border-left:3px solid #ef4444;color:#fecaca}
.t.partial{background:transparent;border-left:3px solid #374151;color:#6b7280;font-style:italic}
.spk{font-weight:600;font-size:11px;color:#888;margin-bottom:2px}
.t.caller .spk{color:#60a5fa}.t.bot .spk{color:#a78bfa}.t.escalate .spk{color:#fca5a5}
.itag{font-size:10px;padding:1px 6px;border-radius:8px;background:#2d2d4e;color:#a5b4fc;margin-left:6px}
.itag.hi{background:#4c1d95;color:#c4b5fd}
.sidebar{min-width:240px;max-width:240px}
.intent-list{font-size:12px}
.intent-item{display:flex;justify-content:space-between;padding:4px 0;border-bottom:1px solid #1e1e3a}
.intent-item .name{color:#a5b4fc}.intent-item .pct{color:#666}
.bar{height:4px;background:#1e1e3a;border-radius:2px;margin-top:2px}
.bar .fill{height:100%;background:#7c3aed;border-radius:2px;transition:width 0.3s}
.sentiment-meter{text-align:center;margin:10px 0}
.sentiment-val{font-size:28px;font-weight:700}
.sentiment-label{font-size:12px;margin-top:2px}
.sentiment-val.pos{color:#34d399}.sentiment-val.neg{color:#f87171}.sentiment-val.neu{color:#9ca3af}
.metrics{display:flex;gap:10px;margin-bottom:14px;font-size:11px;color:#666}
.m{background:#1a1a2e;padding:6px 10px;border-radius:4px}.m span{color:#a78bfa;font-weight:600}
.st{padding:6px 12px;border-radius:4px;font-size:12px;margin-bottom:10px}
.st.on{background:#064e3b;color:#6ee7b7}.st.off{background:#450a0a;color:#fca5a5}.st.wait{background:#1e1b4b;color:#a5b4fc}
</style></head><body><div class="c">
<h1>voice-brain Test Console</h1>
<p class="sub">Real-time: live transcript • intent detection • sentiment • barge-in</p>
<div class="cfg"><div class="row"><div>
<label>Tenant ID</label><input id="tid" value="test-tenant-001"/></div><div>
<label>Product</label><select id="pc">
<option value="CONV_IVR">Conversational IVR (Hinglish bot)</option>
<option value="AI_CC">AI Contact Center (transcript only)</option>
<option value="BASIC_PBX">Basic PBX</option>
<option value="OUTBOUND_DIALER">Outbound Dialer</option>
</select></div></div></div>
<div class="ctrls"><button id="startBtn" onclick="start()">Start Conversation</button>
<button id="stopBtn" onclick="stop()" disabled>Stop</button></div>
<div id="st" class="st off">Disconnected</div>
<div class="metrics">
<div class="m">Turns: <span id="mT">0</span></div>
<div class="m">Latency: <span id="mL">-</span></div>
<div class="m">Duration: <span id="mD">0s</span></div></div>
<div class="panels">
<div class="panel" style="flex:2">
<h3>Live Transcript</h3>
<div id="tr" class="transcript"></div>
</div>
<div class="panel sidebar">
<h3>Sentiment</h3>
<div class="sentiment-meter">
<div id="sentVal" class="sentiment-val neu">0.0</div>
<div id="sentLabel" class="sentiment-label">NEUTRAL</div>
</div>
<h3 style="margin-top:16px">Intent Distribution</h3>
<div id="intents" class="intent-list"></div>
<h3 style="margin-top:16px">Escalation Intents</h3>
<div id="escIntents" class="intent-list" style="color:#f59e0b;font-size:11px">
interested -> sales (85%)<br>not_interested -> hangup (95%)
</div>
</div>
</div></div>
<script>
let ws,ac,ms,wn,callId,pollTimer,startTime,turns=0,lastSpoke=0,intentDist={},lastPollTs=0,seenEventIds=new Set();
function ss(t,c){const e=document.getElementById('st');e.textContent=t;e.className='st '+c}
function at(spk,txt,cls,intent,conf){
  const p=document.getElementById('partial');if(p)p.remove();
  const d=document.createElement('div');d.className='t '+cls;
  let h='<div class="spk">'+spk+'</div>'+txt;
  if(intent&&intent!=='unknown'){
    const hi=conf>0.8?' hi':'';
    h+='<span class="itag'+hi+'">'+intent+' '+(conf*100).toFixed(0)+'%</span>';
  }
  d.innerHTML=h;
  document.getElementById('tr').appendChild(d);d.scrollIntoView({behavior:'smooth'});
}
function showPartial(txt){
  let p=document.getElementById('partial');
  if(!p){p=document.createElement('div');p.id='partial';p.className='t partial';
    p.innerHTML='<div class="spk">listening...</div><span id="ptxt"></span>';
    document.getElementById('tr').appendChild(p);}
  document.getElementById('ptxt').textContent=txt;
  p.scrollIntoView({behavior:'smooth'});
}
function updateSentiment(score,label){
  const el=document.getElementById('sentVal');
  el.textContent=score.toFixed(2);
  el.className='sentiment-val '+(score>0.2?'pos':score<-0.2?'neg':'neu');
  document.getElementById('sentLabel').textContent=label;
}
function updateIntents(dist){
  const el=document.getElementById('intents');
  const sorted=Object.entries(dist).sort((a,b)=>b[1]-a[1]);
  const total=sorted.reduce((s,e)=>s+e[1],0)||1;
  el.innerHTML=sorted.map(([k,v])=>{
    const pct=((v/total)*100).toFixed(0);
    return '<div class="intent-item"><span class="name">'+k+'</span><span class="pct">'+v+'x ('+pct+'%)</span></div>'
      +'<div class="bar"><div class="fill" style="width:'+pct+'%"></div></div>';
  }).join('');
}
async function pollEvents(){
  if(!callId)return;
  try{
    const r=await fetch('/test/events/'+callId+'?since='+lastPollTs);
    const d=await r.json();
    for(const e of d.events){
      if(e.id && seenEventIds.has(e.id)) continue;
      if(e.id) seenEventIds.add(e.id);
      lastPollTs=e.ts||lastPollTs;
      if(e.type==='transcript_partial')showPartial(e.text);
      else if(e.type==='transcript_final'){at('YOU',e.text,'caller');lastSpoke=Date.now();}
      else if(e.type==='bot_response'){
        turns++;document.getElementById('mT').textContent=turns;
        const lat=lastSpoke>0?(Date.now()-lastSpoke):0;
        document.getElementById('mL').textContent=lat+'ms';
        at('BOT',e.text,'bot',e.intent,e.intent_confidence);
        if(e.intent&&e.intent!=='unknown'){intentDist[e.intent]=(intentDist[e.intent]||0)+1;updateIntents(intentDist);}
      }
      else if(e.type==='sentiment'){updateSentiment(e.score,e.label);}
      else if(e.type==='escalation'){
        at('BOT',e.message||'I am escalating you to an agent now. Please stay on the line.','escalate');
        at('SYSTEM','Escalated: '+e.action+' -> '+e.target+' ('+e.reason+')','sys');
      }
    }
  }catch(x){}
  if(startTime){const s=Math.floor((Date.now()-startTime)/1000);document.getElementById('mD').textContent=s+'s';}
}
function playAudio(buf){
  if(!ac)return;
  const s=new Int16Array(buf);const f=new Float32Array(s.length);
  for(let i=0;i<s.length;i++)f[i]=s[i]/32768;
  const b=ac.createBuffer(1,f.length,16000);b.getChannelData(0).set(f);
  const src=ac.createBufferSource();src.buffer=b;src.connect(ac.destination);src.start();
}
async function start(){
  callId='test-'+Date.now();intentDist={};turns=0;lastPollTs=0;startTime=Date.now();seenEventIds=new Set();
  const tid=document.getElementById('tid').value,pc=document.getElementById('pc').value;
  const proto=location.protocol==='https:'?'wss:':'ws:';
  const url=proto+'//'+location.host+'/test/audio/'+callId+'?tenant_id='+tid+'&product_code='+pc;
  document.getElementById('startBtn').disabled=true;document.getElementById('stopBtn').disabled=false;
  document.getElementById('tr').innerHTML='';
  ss('Connecting...','wait');
  ac=new AudioContext({sampleRate:16000});
  try{ms=await navigator.mediaDevices.getUserMedia({audio:{sampleRate:16000,channelCount:1,echoCancellation:true,noiseSuppression:true}})}
  catch(e){ss('Mic denied','off');document.getElementById('startBtn').disabled=false;document.getElementById('stopBtn').disabled=true;return}
  await ac.audioWorklet.addModule(URL.createObjectURL(new Blob([`
class P extends AudioWorkletProcessor{process(i){const d=i[0][0];if(d){const p=new Int16Array(d.length);
for(let j=0;j<d.length;j++)p[j]=Math.max(-32768,Math.min(32767,Math.floor(d[j]*32768)));
this.port.postMessage(p.buffer,[p.buffer])}return true}}registerProcessor('p',P);
`],{type:'application/javascript'})));
  const src=ac.createMediaStreamSource(ms);wn=new AudioWorkletNode(ac,'p');
  src.connect(wn);wn.connect(ac.destination);
  ws=new WebSocket(url);ws.binaryType='arraybuffer';
  ws.onopen=()=>{ss('Connected - speak now','on');at('SYSTEM','Connected. Bot will greet you...','sys');};
  ws.onmessage=(e)=>{if(e.data instanceof ArrayBuffer)playAudio(e.data);};
  ws.onclose=()=>{ss('Disconnected','off');cleanup()};
  ws.onerror=()=>{ss('Error','off');cleanup()};
  wn.port.onmessage=(e)=>{if(ws&&ws.readyState===1)ws.send(e.data)};
  pollTimer=setInterval(pollEvents,300);
}
function stop(){if(ws)ws.close();cleanup()}
function cleanup(){
  if(pollTimer){clearInterval(pollTimer);pollTimer=null}
  if(ms){ms.getTracks().forEach(t=>t.stop());ms=null}
  if(ac){ac.close();ac=null}wn=null;
  document.getElementById('startBtn').disabled=false;document.getElementById('stopBtn').disabled=true;
}
</script></body></html>"""


@router.get("/test", response_class=HTMLResponse)
async def test_page():
    return TEST_HTML


@router.websocket("/test/audio/{call_id}")
async def test_ws(
    websocket: WebSocket,
    call_id: str,
    tenant_id: str = "test-tenant",
    product_code: str = "CONV_IVR",
    bot_id: str = None,
    direction: str = "INBOUND",
    campaign_id: str = None,
    contact_id: str = None,
):
    from api.websocket_handler import handle_audio_websocket

    print("TEST WS ROUTE HIT", call_id, flush=True)
    await handle_audio_websocket(
        websocket,
        call_id,
        tenant_id,
        product_code,
        bot_id,
        direction,
        campaign_id,
        contact_id,
    )
