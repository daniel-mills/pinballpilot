param([string]$Container = 'pinballpilot-db-check', [string]$Database = 'postgres')
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
foreach ($file in @('tests/database-bootstrap.sql') + @(Get-ChildItem -LiteralPath (Join-Path $repo 'supabase/migrations') -Filter '*.sql' | Sort-Object Name | ForEach-Object { 'supabase/migrations/'+$_.Name }) + @('tests/database-security.sql','tests/database-connector.sql','tests/database-versions.sql')) {
    if ($file -eq 'supabase/migrations/202610090006_knowledge_versions.sql') {
        Get-Content -Raw -LiteralPath (Join-Path $repo 'tests/database-upgrade-fixture.sql') | docker exec -i $Container psql -U postgres -d $Database -v ON_ERROR_STOP=1
        if ($LASTEXITCODE -ne 0) { throw 'Legacy upgrade fixture failed' }
    }
    Get-Content -Raw -LiteralPath (Join-Path $repo $file) | docker exec -i $Container psql -U postgres -d $Database -v ON_ERROR_STOP=1
    if ($LASTEXITCODE -ne 0) { throw "Database verification failed: $file" }
}
