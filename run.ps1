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

Write-Host "Running..." -ForegroundColor Green

.\mvnw.cmd spring-boot:run