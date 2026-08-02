[CmdletBinding()]
param(
    [string]$ModelRoot = "",
    [switch]$All,
    [switch]$Voice,
    [switch]$Avatar,
    [switch]$LipSync,
    [switch]$Images,
    [switch]$Stt,
    [switch]$Post,
    [switch]$IncludeFlux,
    [switch]$InstallPythonDeps,
    [string]$HfToken = ""
)

$ErrorActionPreference = "Stop"
$env:PYTHONIOENCODING = "utf-8"
$env:PYTHONUTF8 = "1"
$env:HF_HUB_DISABLE_PROGRESS_BARS = "1"
$env:HF_XET_HIGH_PERFORMANCE = "1"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
if ([string]::IsNullOrWhiteSpace($ModelRoot)) {
    $ModelRoot = Join-Path $repoRoot "models\avatar"
}
$ModelRoot = [System.IO.Path]::GetFullPath($ModelRoot)
New-Item -ItemType Directory -Force -Path $ModelRoot | Out-Null

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "== $Message =="
}

function Ensure-Command([string]$Name, [string]$InstallHint) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "$Name is required. $InstallHint"
    }
}

function Add-PythonUserScriptsToPath() {
    $scripts = python -c "import sysconfig; print(sysconfig.get_path('scripts', 'nt_user'))"
    if (-not [string]::IsNullOrWhiteSpace($scripts) -and (Test-Path $scripts)) {
        $env:PATH = "$scripts;$env:PATH"
    }
}

function Clone-Repo([string]$Url, [string]$Target, [switch]$Recursive) {
    if (Test-Path $Target) {
        Write-Host "Using existing repo: $Target"
        return
    }
    $args = @("clone")
    if ($Recursive) {
        $args += "--recursive"
    }
    $args += @($Url, $Target)
    git @args
}

function Download-Hf([string]$RepoId, [string]$Target, [string[]]$ExtraArgs = @()) {
    New-Item -ItemType Directory -Force -Path $Target | Out-Null
    $downloadCli = "huggingface-cli"
    if (Get-Command hf -ErrorAction SilentlyContinue) {
        $downloadCli = "hf"
    }
    $args = @("download", $RepoId, "--local-dir", $Target, "--quiet")
    $args += $ExtraArgs
    if (-not [string]::IsNullOrWhiteSpace($HfToken)) {
        $args += @("--token", $HfToken)
    }
    & $downloadCli @args
}

function Download-HfFile([string]$RepoId, [string]$FileName, [string]$Target) {
    New-Item -ItemType Directory -Force -Path $Target | Out-Null
    $downloadCli = "huggingface-cli"
    if (Get-Command hf -ErrorAction SilentlyContinue) {
        $downloadCli = "hf"
    }
    $args = @("download", $RepoId, $FileName, "--local-dir", $Target, "--quiet")
    if (-not [string]::IsNullOrWhiteSpace($HfToken)) {
        $args += @("--token", $HfToken)
    }
    & $downloadCli @args
}

if (-not ($All -or $Voice -or $Avatar -or $LipSync -or $Images -or $Stt -or $Post)) {
    $All = $true
}

Ensure-Command "git" "Install Git for Windows."
Ensure-Command "python" "Install Python 3.10 for best compatibility."
Ensure-Command "ffmpeg" "Install ffmpeg and make sure it is on PATH."

if ($InstallPythonDeps) {
    Write-Step "Installing optional Python runtime dependencies"
    python -m pip install -r (Join-Path $repoRoot "requirements-avatar-local.txt")
}

if ($All -or $Voice -or $LipSync -or $Images -or $Stt -or $Post) {
    if (-not (Get-Command huggingface-cli -ErrorAction SilentlyContinue)) {
        Write-Step "Installing Hugging Face CLI"
        python -m pip install "huggingface_hub[cli]>=0.24.0"
        Add-PythonUserScriptsToPath
    }
}

if ($All -or $Voice) {
    Write-Step "Bootstrapping CosyVoice2"
    $cosyRepo = Join-Path $ModelRoot "CosyVoice"
    $cosyModel = Join-Path $ModelRoot "CosyVoice2-0.5B"
    Clone-Repo "https://github.com/FunAudioLLM/CosyVoice.git" $cosyRepo -Recursive
    if (-not (Get-Command modelscope -ErrorAction SilentlyContinue)) {
        python -m pip install "modelscope>=1.17.0"
        Add-PythonUserScriptsToPath
    }
    if (-not (Test-Path $cosyModel)) {
        modelscope download iic/CosyVoice2-0.5B --local-dir $cosyModel
    }
}

if ($All -or $Avatar) {
    Write-Step "Bootstrapping LivePortrait and EchoMimic"
    Clone-Repo "https://github.com/KlingAIResearch/LivePortrait.git" (Join-Path $ModelRoot "LivePortrait")
    Download-Hf "KlingTeam/LivePortrait" (Join-Path $ModelRoot "LivePortrait\pretrained_weights")
    Clone-Repo "https://github.com/antgroup/echomimic_v2.git" (Join-Path $ModelRoot "EchoMimicV2")
}

if ($All -or $LipSync) {
    Write-Step "Bootstrapping MuseTalk"
    $museTalkRepo = Join-Path $ModelRoot "MuseTalk"
    Clone-Repo "https://github.com/TMElyralab/MuseTalk.git" $museTalkRepo
    Download-Hf "TMElyralab/MuseTalk" (Join-Path $museTalkRepo "models") @("--include", "musetalkV15/*")
    Download-Hf "stabilityai/sd-vae-ft-mse" (Join-Path $museTalkRepo "models\sd-vae")
    Download-Hf "openai/whisper-tiny" (Join-Path $museTalkRepo "models\whisper")
    Download-HfFile "yzd-v/DWPose" "dw-ll_ucoco_384.pth" (Join-Path $museTalkRepo "models\dwpose")
    Download-Hf "ManyOtherFunctions/face-parse-bisent" (Join-Path $museTalkRepo "models\face-parse-bisent") @("--include", "79999_iter.pth", "resnet18-5c106cde.pth")
    $s3fdTarget = Join-Path $museTalkRepo "musetalk\utils\face_detection\detection\sfd\s3fd.pth"
    $s3fdExpectedHash = "619A31681264D3F7F7FC7A16A42CBBE8B23F31A256F75A366E5A1BCD59B33543"
    $s3fdReady = (Test-Path -LiteralPath $s3fdTarget) -and ((Get-FileHash -LiteralPath $s3fdTarget -Algorithm SHA256).Hash -eq $s3fdExpectedHash)
    if (-not $s3fdReady) {
        $s3fdDownload = "$s3fdTarget.download"
        Invoke-WebRequest -Uri "https://www.adrianbulat.com/downloads/python-fan/s3fd-619a316812.pth" -OutFile $s3fdDownload
        if ((Get-FileHash -LiteralPath $s3fdDownload -Algorithm SHA256).Hash -ne $s3fdExpectedHash) {
            throw "S3FD checkpoint checksum validation failed."
        }
        Move-Item -LiteralPath $s3fdDownload -Destination $s3fdTarget -Force
    }
    $museTalkPython = Join-Path (Split-Path -Parent $ModelRoot) ".avatar-envs\musetalk\Scripts\python.exe"
    if (Test-Path -LiteralPath $museTalkPython) {
        & $museTalkPython -m pip uninstall -y mmcv-lite
        & $museTalkPython -m pip install --no-deps "mmcv==2.2.0" --find-links "https://download.openmmlab.com/mmcv/dist/cpu/torch2.3.0/index.html"
    } else {
        Write-Warning "MuseTalk environment is not present yet. Install the compiled MMCV wheel after creating .avatar-envs\musetalk."
    }

    Write-Step "Bootstrapping LatentSync 1.5 for 8 GB GPUs"
    $latentSyncRepo = Join-Path $ModelRoot "LatentSync"
    Clone-Repo "https://github.com/bytedance/LatentSync.git" $latentSyncRepo
    $latentSyncCheckpoints = Join-Path $latentSyncRepo "checkpoints"
    Download-HfFile "ByteDance/LatentSync-1.5" "latentsync_unet.pt" $latentSyncCheckpoints
    Download-HfFile "ByteDance/LatentSync-1.5" "whisper/tiny.pt" $latentSyncCheckpoints
    $latentSyncCheckpoint = Join-Path $latentSyncCheckpoints "latentsync_unet.pt"
    $latentSyncCheckpointHash = "6440B49A7CCCEFF56CDC001F5F17605216337F5BBD66FA360139768926E23F51"
    if ((Get-Item -LiteralPath $latentSyncCheckpoint).Length -ne 5072348184) {
        throw "LatentSync 1.5 checkpoint size validation failed."
    }
    if ((Get-FileHash -LiteralPath $latentSyncCheckpoint -Algorithm SHA256).Hash -ne $latentSyncCheckpointHash) {
        throw "LatentSync 1.5 checkpoint checksum validation failed."
    }
    $latentSyncWhisper = Join-Path $latentSyncCheckpoints "whisper\tiny.pt"
    $latentSyncWhisperHash = "65147644A518D12F04E32D6F3B26FACC3F8DD46E5390956A9424A650C0CE22B9"
    if ((Get-Item -LiteralPath $latentSyncWhisper).Length -ne 75572083 -or
        (Get-FileHash -LiteralPath $latentSyncWhisper -Algorithm SHA256).Hash -ne $latentSyncWhisperHash) {
        throw "LatentSync Whisper tiny checkpoint validation failed."
    }
    $latentSyncEnv = Join-Path (Split-Path -Parent $ModelRoot) ".avatar-envs\latentsync"
    $latentSyncPython = Join-Path $latentSyncEnv "Scripts\python.exe"
    if (-not (Test-Path -LiteralPath $latentSyncPython)) {
        if (-not (Get-Command py -ErrorAction SilentlyContinue)) {
            throw "Python 3.11 and the Windows py launcher are required for LatentSync."
        }
        & py -3.11 -m venv $latentSyncEnv
    }
    & $latentSyncPython -m pip install --upgrade pip
    & $latentSyncPython -m pip install --index-url "https://download.pytorch.org/whl/cu121" "torch==2.5.1" "torchvision==0.20.1"
    & $latentSyncPython -m pip install -r (Join-Path $repoRoot "requirements-latentsync-local.txt")
}

if ($All -or $Images) {
    Write-Step "Bootstrapping image model metadata"
    if ($IncludeFlux) {
        if ([string]::IsNullOrWhiteSpace($HfToken) -and [string]::IsNullOrWhiteSpace($env:HF_TOKEN)) {
            Write-Warning "Skipping FLUX.1-dev download because it is gated. Re-run with -IncludeFlux -HfToken <token> after accepting the license."
        } else {
            Download-Hf "black-forest-labs/FLUX.1-dev" (Join-Path $ModelRoot "FLUX.1-dev")
        }
    } else {
        Write-Host "FLUX.1-dev is gated. Use -IncludeFlux -HfToken <token> after accepting the license."
    }
}

if ($All -or $Stt) {
    Write-Step "Bootstrapping STT/caption dependencies"
    python -m pip install "faster-whisper>=1.1.0" "whisperx>=3.3.0"
    Download-Hf "Systran/faster-whisper-tiny" (Join-Path $ModelRoot "faster-whisper-tiny")
}

if ($All -or $Post) {
    Write-Step "Bootstrapping post-processing models"
    Download-Hf "ZhengPeng7/BiRefNet" (Join-Path $ModelRoot "BiRefNet")
    $realZip = Join-Path $ModelRoot "realesrgan-ncnn-vulkan-20220424-windows.zip"
    $realDir = Join-Path $ModelRoot "realesrgan-ncnn-vulkan"
    if (-not (Test-Path $realDir)) {
        Invoke-WebRequest -Uri "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesrgan-ncnn-vulkan-20220424-windows.zip" -OutFile $realZip
        Expand-Archive -Path $realZip -DestinationPath $ModelRoot -Force
    }
    Clone-Repo "https://github.com/sczhou/CodeFormer.git" (Join-Path $ModelRoot "CodeFormer")
}

$envFile = Join-Path $repoRoot ".env.avatar-local.generated"
$realesrganExe = Join-Path $ModelRoot "realesrgan-ncnn-vulkan.exe"
if (-not (Test-Path $realesrganExe)) {
    $realesrganExe = Join-Path $ModelRoot "realesrgan-ncnn-vulkan\realesrgan-ncnn-vulkan.exe"
}
$cosyPython = Join-Path (Split-Path -Parent $ModelRoot) ".avatar-envs\cosyvoice\Scripts\python.exe"
$livePortraitPython = Join-Path (Split-Path -Parent $ModelRoot) ".avatar-envs\liveportrait\Scripts\python.exe"
$museTalkPython = Join-Path (Split-Path -Parent $ModelRoot) ".avatar-envs\musetalk\Scripts\python.exe"
$latentSyncPython = Join-Path (Split-Path -Parent $ModelRoot) ".avatar-envs\latentsync\Scripts\python.exe"
$cosyRunner = Join-Path $repoRoot "scripts\run_cosyvoice2_zero_shot.py"
$livePortraitRunner = Join-Path $repoRoot "scripts\run_liveportrait_scene.py"
$museTalkRunner = Join-Path $repoRoot "scripts\run_musetalk_lipsync.py"
$latentSyncRunner = Join-Path $repoRoot "scripts\run_latentsync_lipsync.py"
$elevenlabsRunner = Join-Path $repoRoot "scripts\run_elevenlabs_voice_clone.py"
$syncLabsRunner = Join-Path $repoRoot "scripts\run_sync_labs_lipsync.py"
$cosyRepo = Join-Path $ModelRoot "CosyVoice"
$birefRunner = Join-Path $repoRoot "scripts\run_birefnet_background_removal.py"
$codeformerRunner = Join-Path $repoRoot "scripts\run_codeformer_face_restore.py"
@"
AVATAR_GENERATION_ENABLED=true
AVATAR_LOCAL_RUNTIME_ENABLED=true
AVATAR_MODEL_ROOT=$ModelRoot
AVATAR_WORK_ROOT=$(Join-Path $repoRoot "work\avatar")
AVATAR_ALLOW_TEST_FALLBACK=false
AVATAR_VOICE_MODEL=cosy_voice2
AVATAR_TALKING_MODEL=liveportrait
AVATAR_LIPSYNC_MODEL=musetalk
AVATAR_IMAGE_MODEL=gemini_storyboard
AVATAR_VIDEO_MODEL=wan_2_2
AVATAR_PROPRIETARY_API_TIMEOUT_SECONDS=900
AVATAR_STT_MODEL=$(Join-Path $ModelRoot "faster-whisper-tiny")
AVATAR_STT_DEVICE=cpu
AVATAR_STT_COMPUTE_TYPE=int8
AVATAR_CAPTION_ENGINE=faster_whisper
AVATAR_COSYVOICE_REPO=$(Join-Path $ModelRoot "CosyVoice")
AVATAR_COSYVOICE2_MODEL_DIR=$(Join-Path $ModelRoot "CosyVoice2-0.5B")
AVATAR_LIVEPORTRAIT_REPO=$(Join-Path $ModelRoot "LivePortrait")
AVATAR_ECHOMIMIC_REPO=$(Join-Path $ModelRoot "EchoMimicV2")
AVATAR_MUSETALK_REPO=$(Join-Path $ModelRoot "MuseTalk")
AVATAR_LATENTSYNC_REPO=$(Join-Path $ModelRoot "LatentSync")
AVATAR_FLUX_MODEL_ID=black-forest-labs/FLUX.1-dev
AVATAR_BIREFNET_MODEL_ID=ZhengPeng7/BiRefNet
AVATAR_REALESRGAN_EXECUTABLE=$realesrganExe
AVATAR_CODEFORMER_REPO=$(Join-Path $ModelRoot "CodeFormer")
AVATAR_ELEVENLABS_API_KEY=
AVATAR_ELEVENLABS_BASE_URL=https://api.elevenlabs.io
AVATAR_ELEVENLABS_VOICE_ID=
AVATAR_ELEVENLABS_MODEL_ID=eleven_multilingual_v2
AVATAR_SYNC_LABS_API_KEY=
AVATAR_SYNC_LABS_BASE_URL=https://api.sync.so
AVATAR_SYNC_LABS_MODEL=lipsync-2-pro

# Fill these with the exact commands after each third-party repo environment is installed.
# The service replaces {source_video}, {audio}, {output_video}, {work_dir}, {text_file}, and other placeholders.
AVATAR_COSYVOICE2_COMMAND='"$cosyPython" "$cosyRunner" --repo "$cosyRepo" --model-dir "{cosyvoice_model_dir}" --text-file "{text_file}" --prompt-wav "{prompt_wav}" --prompt-text "{prompt_text}" --output-audio "{output_audio}"'
AVATAR_ELEVENLABS_VOICE_COMMAND='python "$elevenlabsRunner" --text-file "{text_file}" --prompt-wav "{prompt_wav}" --output-audio "{output_audio}" --voice-id "{proprietary_voice_id}" --voice-name "{voice_clone_name}" --base-url "{elevenlabs_base_url}" --model-id "{elevenlabs_model_id}" --timeout "{proprietary_api_timeout_seconds}"'
AVATAR_VOICE_FALLBACK_COMMAND='python "$elevenlabsRunner" --text-file "{text_file}" --prompt-wav "{prompt_wav}" --output-audio "{output_audio}" --voice-id "{proprietary_voice_id}" --voice-name "{voice_clone_name}" --base-url "{elevenlabs_base_url}" --model-id "{elevenlabs_model_id}" --timeout "{proprietary_api_timeout_seconds}"'
AVATAR_LIVEPORTRAIT_COMMAND='"$livePortraitPython" "$livePortraitRunner" --repo "$(Join-Path $ModelRoot "LivePortrait")" --source-video "{source_video}" --audio "{audio}" --output-video "{output_video}" --duration "{duration_seconds}"'
AVATAR_ECHOMIMIC_COMMAND=
AVATAR_MUSETALK_COMMAND='"$museTalkPython" "$museTalkRunner" --repo "$(Join-Path $ModelRoot "MuseTalk")" --input-video "{avatar_video}" --audio "{audio}" --output-video "{lip_synced_video}"'
AVATAR_LATENTSYNC_COMMAND='"$latentSyncPython" "$latentSyncRunner" --repo "$(Join-Path $ModelRoot "LatentSync")" --input-video "{avatar_video}" --audio "{audio}" --output-video "{lip_synced_video}" --config "configs/unet/stage2_efficient.yaml" --checkpoint "checkpoints/latentsync_unet.pt" --steps "20" --guidance-scale "1.2"'
AVATAR_SYNC_LABS_COMMAND='python "$syncLabsRunner" --input-video "{avatar_video}" --audio "{audio}" --output-video "{lip_synced_video}" --model "{sync_labs_model}" --base-url "{sync_labs_base_url}" --timeout "{proprietary_api_timeout_seconds}"'
AVATAR_LIPSYNC_FALLBACK_COMMAND='python "$syncLabsRunner" --input-video "{avatar_video}" --audio "{audio}" --output-video "{lip_synced_video}" --model "{sync_labs_model}" --base-url "{sync_labs_base_url}" --timeout "{proprietary_api_timeout_seconds}"'
AVATAR_FLUX_COMMAND=
AVATAR_BIREFNET_COMMAND='python "$birefRunner" --model-dir "$(Join-Path $ModelRoot "BiRefNet")" --input-image "{input_image}" --output-image "{output_image}"'
AVATAR_CODEFORMER_COMMAND='python "$codeformerRunner" --repo "$(Join-Path $ModelRoot "CodeFormer")" --input-image "{input_image}" --output-image "{output_image}"'
"@ | Set-Content -Path $envFile -Encoding UTF8

Write-Step "Done"
Write-Host "Model root: $ModelRoot"
Write-Host "Generated env file: $envFile"
Write-Host "Next: merge .env.avatar-local.generated into .env and fill command templates for each model repo environment."
