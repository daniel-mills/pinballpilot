param([string]$Container = 'pinballpilot-db-check', [string]$Database = 'postgres')
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
foreach ($file in @('tests/database-bootstrap.sql','supabase/migrations/202610090001_initial.sql','supabase/migrations/202610090002_publication.sql','supabase/migrations/202610090003_private_sync.sql','supabase/migrations/202610090004_evidence_changes.sql','supabase/migrations/202610090005_research_connector.sql','tests/database-security.sql','tests/database-connector.sql')) {
    Get-Content -Raw -LiteralPath (Join-Path $repo $file) | docker exec -i $Container psql -U postgres -d $Database -v ON_ERROR_STOP=1
    if ($LASTEXITCODE -ne 0) { throw "Database verification failed: $file" }
}
