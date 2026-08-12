param(
  [string]$BackendUrl = "http://localhost:8091",
  [string]$AdminUser = "admin",
  [string]$AdminPassword = "modelrag"
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent (Split-Path -Parent $PSCommandPath)

function Invoke-Json {
  param([string]$Method,[string]$Uri,[object]$Body=$null,[hashtable]$Headers=@{})
  if ($null -eq $Body) {
    return Invoke-RestMethod -Method $Method -Uri $Uri -Headers $Headers -TimeoutSec 10
  }
  $json = $Body | ConvertTo-Json -Depth 20
  $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
  return Invoke-RestMethod -Method $Method -Uri $Uri -Headers $Headers -ContentType "application/json; charset=utf-8" -Body $bytes -TimeoutSec 10
}

function Check {
  param([string]$Name,[scriptblock]$Action)
  try {
    $value = & $Action
    [pscustomobject]@{ name = $Name; status = "PASS"; detail = "$value" }
  } catch {
    [pscustomobject]@{ name = $Name; status = "FAIL"; detail = $_.Exception.Message }
  }
}

$checks = @()

$checks += Check "backend.health" {
  $health = Invoke-Json Get "$BackendUrl/actuator/health"
  if ($health.status -ne "UP") { throw "status=$($health.status)" }
  "UP"
}

$checks += Check "backend.not-8080" {
  $uri = [Uri]$BackendUrl
  if ($uri.Port -eq 8080) { throw "BackendUrl uses occupied port 8080" }
  "port=$($uri.Port)"
}

$token = $null
$checks += Check "auth.admin-login" {
  $login = Invoke-Json Post "$BackendUrl/api/v1/auth/login" @{ username = $AdminUser; password = $AdminPassword }
  if ([string]::IsNullOrWhiteSpace($login.data.token)) { throw "missing token" }
  $script:token = $login.data.token
  "user=$($login.data.user.id)"
}

$adminHeaders = @{}
if (-not [string]::IsNullOrWhiteSpace($token)) {
  $adminHeaders.Authorization = "Bearer $token"
}

$checks += Check "qa.context-policy" {
  $policy = Invoke-Json Get "$BackendUrl/api/v1/qa/context-policy" $null $adminHeaders
  if ($policy.data.maxEvidenceTokens -le 0) { throw "invalid context policy" }
  "maxEvidenceTokens=$($policy.data.maxEvidenceTokens)"
}

$checks += Check "knowledge-bases.list" {
  $items = (Invoke-Json Get "$BackendUrl/api/v1/knowledge-bases" $null $adminHeaders).data
  "count=$(@($items).Count)"
}

$checks += Check "security.users" {
  $users = (Invoke-Json Get "$BackendUrl/api/v1/admin/security/users" $null $adminHeaders).data
  "count=$(@($users).Count)"
}

$checks += Check "frontend.dist" {
  $index = Join-Path $Root "modelrag-client\dist\index.html"
  if (-not (Test-Path -LiteralPath $index)) { throw "frontend dist missing; run npm run build in modelrag-client" }
  "present"
}

$failed = @($checks | Where-Object { $_.status -ne "PASS" })
$checks | Format-Table -AutoSize
if ($failed.Count -gt 0) {
  throw "Product check failed: $($failed.Count) failed check(s)."
}

Write-Host "Product check passed. Run scripts\acceptance-smoke.ps1 for full RAG/Agent workflow validation."
