if (-not (Test-Path .env)) {
    Write-Error "Файл .env не найден в корне проекта!"
    exit 1
}

Get-Content .env | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith('#') -and $line.Contains('=')) {
        $index = $line.IndexOf('=')
        $key = $line.Substring(0, $index).Trim()
        $value = $line.Substring($index + 1).Trim()

        $value = $value -replace '^["'']|["'']$'

        [System.Environment]::SetEnvironmentVariable($key, $value, "Process")
    }
}

if (-not $env:FLYWAY_ENABLED) {
    [System.Environment]::SetEnvironmentVariable("FLYWAY_ENABLED", "false", "Process")
}

if (-not $env:JPA_DDL_AUTO) {
    [System.Environment]::SetEnvironmentVariable("JPA_DDL_AUTO", "update", "Process")
}

$port = if ($env:SERVER_PORT) { [int]$env:SERVER_PORT } else { 8080 }
$listeners = netstat -ano | Select-String ":$port\s+.*LISTENING"
if ($listeners) {
    Write-Host "Port $port is already in use:" -ForegroundColor Yellow
    $pids = $listeners | ForEach-Object { ($_ -split '\s+')[-1] } | Sort-Object -Unique
    foreach ($processId in $pids) {
        $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
        $name = if ($process) { $process.ProcessName } else { "unknown" }
        Write-Host "  PID $processId ($name)" -ForegroundColor Yellow
    }
    Write-Host "Stop that process or set SERVER_PORT in .env, e.g. SERVER_PORT=8081." -ForegroundColor Yellow
    exit 1
}

Write-Host "Running..." -ForegroundColor Green

.\mvnw.cmd spring-boot:run
