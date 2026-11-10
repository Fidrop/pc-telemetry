$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$schema = Join-Path $projectRoot 'protocol/telemetry.schema.json'
$files = @(Get-ChildItem (Join-Path $projectRoot 'protocol/examples') -Filter '*.json')
foreach ($file in $files) {
    if (-not (Test-Json -Json (Get-Content $file.FullName -Raw) -SchemaFile $schema)) {
        throw "Schema validation failed $($file.Name)"
    }
}
$live = Join-Path $projectRoot 'docs/stage-1-live-snapshots.jsonl'
$count = 0
if (Test-Path $live) {
    foreach ($line in Get-Content $live) {
        if (-not (Test-Json -Json $line -SchemaFile $schema)) { throw 'Live snapshot schema validation failed' }
        $count++
    }
}
Write-Output "PASS JSON Schema validation: $($files.Count) DEMO fixtures and $count recorded live snapshots."
