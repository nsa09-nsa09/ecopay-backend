param(
  [string]$MigrationDir = "src/main/resources/db/migration"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $MigrationDir)) {
  Write-Error "Flyway migration directory not found: $MigrationDir"
}

$duplicates =
  Get-ChildItem -LiteralPath $MigrationDir -Filter "V*__*.sql" |
  ForEach-Object {
    if ($_.Name -match '^V(?<version>[0-9]+)__.+\.sql$') {
      [pscustomobject]@{
        Version = [int]$Matches.version
        Name = $_.Name
      }
    }
  } |
  Group-Object Version |
  Where-Object { $_.Count -gt 1 }

if ($duplicates) {
  Write-Error (
    "Duplicate Flyway migration versions found:`n" +
    (($duplicates | ForEach-Object {
      "V$($_.Name): " + (($_.Group | Sort-Object Name | Select-Object -ExpandProperty Name) -join ", ")
    }) -join "`n")
  )
}

Write-Host "Flyway duplicate-version preflight passed."
