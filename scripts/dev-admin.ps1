$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$localNode = Join-Path $repo '.tools/node22/node-v22.22.3-win-x64'
if (Test-Path -LiteralPath (Join-Path $localNode 'node.exe')) { $env:PATH = "$localNode;$env:PATH" }
$env:DEBUG = ''
Set-Location -LiteralPath $repo
npm.cmd run dev
exit $LASTEXITCODE
