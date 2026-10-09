$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$cache = Join-Path $repo '.tools'
New-Item -ItemType Directory -Force -Path $cache | Out-Null
$archive = Join-Path $cache 'vosk-model-small-en-us-0.15.zip'
if (-not (Test-Path -LiteralPath $archive)) {
    curl.exe --fail --location --max-time 180 --silent --show-error 'https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip' -o $archive
    if ($LASTEXITCODE -ne 0) { throw 'Voice model download failed' }
}
$expected = '30F26242C4EB449F948E42CB302DD7A686CB29A3423A8367F99FF41780942498'
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $expected) { throw 'Voice model checksum changed. Verify the upstream model before updating the pinned checksum.' }
Expand-Archive -LiteralPath $archive -DestinationPath (Join-Path $cache 'voice-model') -Force
$target = Join-Path $repo 'android/app/src/main/assets/model-en-us'
New-Item -ItemType Directory -Force -Path $target | Out-Null
Copy-Item -Path (Join-Path $cache 'voice-model/vosk-model-small-en-us-0.15/*') -Destination $target -Recurse -Force
Set-Content -LiteralPath (Join-Path $target 'uuid') -Value 'pinballpilot-vosk-en-us-0.15-v1' -NoNewline
Write-Output 'Installed Apache-2.0 Vosk small English model. Hardware validation is still required.'
