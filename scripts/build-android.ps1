$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$jdkRoot = Join-Path $repo '.tools/microsoft-jdk17'
if (Test-Path -LiteralPath $jdkRoot) { $env:JAVA_HOME = (Get-ChildItem -LiteralPath $jdkRoot -Directory | Select-Object -First 1).FullName }
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 17 before building.' }
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
$env:DEBUG = ''
Set-Location -LiteralPath (Join-Path $repo 'android')
./gradlew.bat :app:assembleDebug :domain:test :app:lintDebug --no-daemon --console=plain
exit $LASTEXITCODE
