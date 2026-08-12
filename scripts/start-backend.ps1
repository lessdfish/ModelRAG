param(
  [int]$Port = 8091,
  [switch]$SkipPackage
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
$RuntimeDir = Join-Path $Root "tmp\runtime"
New-Item -ItemType Directory -Force -Path $RuntimeDir | Out-Null

function Test-Healthy {
  param([int]$TargetPort)
  try {
    $health = Invoke-RestMethod -Method Get -Uri "http://localhost:$TargetPort/actuator/health" -TimeoutSec 2
    return $health.status -eq "UP"
  } catch {
    return $false
  }
}

$listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($listener) {
  if (Test-Healthy $Port) {
    Write-Host "Backend is already healthy on port $Port."
    return
  }
  $owner = $listener | Select-Object -First 1
  $ownerProcess = Get-Process -Id $owner.OwningProcess -ErrorAction SilentlyContinue
  throw "Port $Port is already in use and is not a healthy ModelRAG backend. PID=$($owner.OwningProcess) Process=$($ownerProcess.ProcessName)"
}

if (-not $SkipPackage) {
  Push-Location $Root
  try {
    mvn -q -pl modelrag-server -am -DskipTests package
    if ($LASTEXITCODE -ne 0) {
      throw "Maven package failed with exit code $LASTEXITCODE."
    }
  } finally {
    Pop-Location
  }
}

$jar = Get-ChildItem (Join-Path $Root "modelrag-server\target") -Filter "modelrag-server-*.jar" |
  Sort-Object LastWriteTime -Descending |
  Select-Object -First 1
if (-not $jar) {
  throw "Backend jar not found. Run: mvn -q -pl modelrag-server -am -DskipTests package"
}

$shell = Get-Command pwsh.exe -ErrorAction SilentlyContinue
if (-not $shell) {
  $shell = Get-Command powershell.exe -ErrorAction Stop
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$stdout = Join-Path $RuntimeDir "backend-$Port-$stamp.out.log"
$stderr = Join-Path $RuntimeDir "backend-$Port-$stamp.err.log"
$command = "`$env:SERVER_PORT='$Port'; `$env:SPRING_PROFILES_ACTIVE='postgres'; `$env:MODELRAG_JDBC_URL='jdbc:postgresql://localhost:15432/modelrag'; `$env:MODELRAG_REDIS_PORT='16379'; `$env:MODELRAG_ELASTICSEARCH_ENDPOINT='http://localhost:19200'; `$env:MODELRAG_DEFAULT_ADMIN_ENABLED='true'; java -jar '$($jar.FullName)'"
$process = Start-Process -FilePath $shell.Source `
  -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", $command) `
  -WorkingDirectory $Root `
  -RedirectStandardOutput $stdout `
  -RedirectStandardError $stderr `
  -WindowStyle Hidden `
  -PassThru

$healthy = $false
for ($i = 0; $i -lt 30; $i++) {
  if (Test-Healthy $Port) {
    $healthy = $true
    break
  }
  Start-Sleep -Seconds 2
}

if (-not $healthy) {
  Write-Host "Backend stdout: $stdout"
  Write-Host "Backend stderr: $stderr"
  throw "Backend did not become healthy in time. PID=$($process.Id)"
}

[pscustomobject]@{
  backend = "http://localhost:$Port"
  pid = $process.Id
  jar = $jar.FullName
  stdout = $stdout
  stderr = $stderr
} | Format-List
