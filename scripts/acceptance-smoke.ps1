param(
  [string]$BackendUrl = "http://localhost:8091",
  [string]$SampleDir = "",
  [string]$AdminUser = $env:MODELRAG_BOOTSTRAP_ADMIN_USER,
  [string]$AdminPassword = $env:MODELRAG_BOOTSTRAP_ADMIN_PASSWORD
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
if ([string]::IsNullOrWhiteSpace($AdminUser) -or [string]::IsNullOrWhiteSpace($AdminPassword)) {
  throw "Supply AdminUser/AdminPassword or MODELRAG_BOOTSTRAP_ADMIN_USER/MODELRAG_BOOTSTRAP_ADMIN_PASSWORD."
}

function Resolve-SamplePath {
  param([string]$ConfiguredDir)
  if (-not [string]::IsNullOrWhiteSpace($ConfiguredDir)) {
    $path = Join-Path $Root $ConfiguredDir
    if (Test-Path -LiteralPath $path) { return $path }
    throw "Sample document directory does not exist: $path"
  }
  $rootSamples = Join-Path $Root "sample-documents"
  if (-not (Test-Path -LiteralPath $rootSamples)) {
    throw "sample-documents directory does not exist: $rootSamples"
  }
  $candidate = Get-ChildItem -LiteralPath $rootSamples -Directory |
    Where-Object {
      @($_ | Get-ChildItem -File | Where-Object { $_.Extension -in ".pdf",".docx",".md",".txt" }).Count -ge 4
    } |
    Select-Object -First 1
  if (-not $candidate) {
    throw "No sample document directory with pdf/docx/md/txt files was found under $rootSamples"
  }
  return $candidate.FullName
}

$SamplePath = Resolve-SamplePath $SampleDir

$Headers = @{}
$ApproverHeaders = @{}

function Invoke-Api {
  param([string]$Method,[string]$Path,[object]$Body=$null,[hashtable]$RequestHeaders=$Headers)
  $uri = "$BackendUrl/api/v2$Path"
  if ($null -eq $Body) {
    return Invoke-RestMethod -Method $Method -Uri $uri -Headers $RequestHeaders
  }
  $json = $Body | ConvertTo-Json -Depth 20
  $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
  return Invoke-RestMethod -Method $Method -Uri $uri -Headers $RequestHeaders -ContentType "application/json; charset=utf-8" -Body $bytes
}

function Invoke-Upload {
  param([long]$DatasetId,[System.IO.FileInfo]$File,[hashtable]$RequestHeaders=$Headers)
  $uri = "$BackendUrl/api/v2/datasets/$DatasetId/documents"
  if ($PSVersionTable.PSVersion.Major -ge 7) {
    return Invoke-RestMethod -Method Post -Uri $uri -Headers $RequestHeaders -Form @{ file = $File }
  }
  $args = @("-sS", "-X", "POST", $uri)
  foreach ($key in $RequestHeaders.Keys) {
    $args += @("-H", "$key`: $($RequestHeaders[$key])")
  }
  $args += @("-F", "file=@$($File.FullName)")
  $raw = & curl.exe @args
  if ($LASTEXITCODE -ne 0) { throw "curl upload failed for $($File.Name)" }
  return $raw | ConvertFrom-Json
}

function Assert-True {
  param([bool]$Condition,[string]$Message)
  if (-not $Condition) { throw $Message }
}

function Decode-Utf8Base64 {
  param([string]$Value)
  return [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($Value))
}

function Login-User {
  param([string]$Username,[string]$Password)
  $login = Invoke-Api Post "/auth/login" @{ username = $Username; password = $Password } @{}
  Assert-True (-not [string]::IsNullOrWhiteSpace($login.data.token)) "Login failed for $Username."
  return @{ Authorization = "Bearer $($login.data.token)" }
}

Write-Host "Waiting for backend health: $BackendUrl/actuator/health"
$healthy = $false
for ($i=0; $i -lt 30; $i++) {
  try {
    $health = Invoke-RestMethod -Method Get -Uri "$BackendUrl/actuator/health"
    if ($health.status -eq "UP") { $healthy = $true; break }
  } catch {}
  Start-Sleep -Seconds 2
}
Assert-True $healthy "Backend did not become healthy in time."

$runId = Get-Date -Format "yyyyMMdd-HHmmss"
$userId = "smoke-user-$runId"
$approverId = "smoke-approver-$runId"
$noAccessId = "smoke-noaccess-$runId"
$userPassword = "modelrag-$runId"

Write-Host "Logging in admin and preparing real smoke users"
$Headers = Login-User $AdminUser $AdminPassword

$datasetName = "smoke-kb-$runId"
Write-Host "Creating knowledge base: $datasetName"
$datasetResponse = Invoke-Api Post "/datasets" @{
  name = $datasetName
  description = "smoke: multi-format indexing, auto dataset routing, rag/agent routing, trace replay"
  chunkSize = 512
  chunkOverlap = 64
}
$dataset = $datasetResponse.data
Assert-True ($dataset.id -gt 0) "Knowledge base creation failed."

Invoke-Api Post "/admin/security/users" @{ userId = $userId; displayName = "Smoke User $runId"; password = $userPassword; enabled = $true; roles = @("USER") } $Headers | Out-Null
Invoke-Api Post "/admin/security/users" @{ userId = $approverId; displayName = "Smoke Approver $runId"; password = $userPassword; enabled = $true; roles = @("APPROVER") } $Headers | Out-Null
Invoke-Api Post "/admin/security/users" @{ userId = $noAccessId; displayName = "Smoke No Access $runId"; password = $userPassword; enabled = $true; roles = @("USER") } $Headers | Out-Null
Invoke-Api Post "/admin/security/users/$userId/datasets/$($dataset.id)" @{ permission = "READ" } $Headers | Out-Null
Invoke-Api Post "/admin/security/users/$approverId/datasets/$($dataset.id)" @{ permission = "READ" } $Headers | Out-Null

$UserHeaders = Login-User $userId $userPassword
# BYOK credentials are user-scoped. Run model-backed checks as the configured admin while the
# generated ordinary users continue to exercise dataset ACL and independent approval roles.
$QuestionHeaders = $Headers
$ApproverHeaders = Login-User $approverId $userPassword
$NoAccessHeaders = Login-User $noAccessId $userPassword

$visibleForUser = (Invoke-Api Get "/datasets" $null $UserHeaders).data
Assert-True (@($visibleForUser | Where-Object { [long]$_.id -eq [long]$dataset.id }).Count -eq 1) "Authorized user cannot see the granted knowledge base."
$visibleForNoAccess = (Invoke-Api Get "/datasets" $null $NoAccessHeaders).data
Assert-True (@($visibleForNoAccess | Where-Object { [long]$_.id -eq [long]$dataset.id }).Count -eq 0) "Unauthorized user can see an ungranted knowledge base."

$files = Get-ChildItem -LiteralPath $SamplePath -File | Where-Object { $_.Extension -in ".pdf",".docx",".md",".txt" } | Sort-Object Name
Assert-True ($files.Count -ge 4) "Expected at least four sample documents under $SamplePath."

foreach ($file in $files) {
  Write-Host "Uploading document: $($file.Name)"
  $upload = Invoke-Upload $dataset.id $file $Headers
  Assert-True ($upload.data.id -gt 0) "Document upload failed: $($file.Name)"
}

Write-Host "Waiting for all documents to become READY"
$ready = $false
for ($i=0; $i -lt 60; $i++) {
  $docs = (Invoke-Api Get "/datasets/$($dataset.id)/documents").data
  $readyCount = @($docs | Where-Object { $_.status -eq "READY" }).Count
  if ($docs.Count -ge $files.Count -and $readyCount -eq $docs.Count) { $ready = $true; break }
  Start-Sleep -Seconds 1
}
Assert-True $ready "Documents did not all become READY."

Write-Host "Checking simple question: auto dataset + DIRECT_RAG"
$simpleQuery = Decode-Utf8Base64 "5ZGY5bel5q+P5bm05pyJ5Yeg5aSp5bim6Jaq5bm05YGH77yf"
$simple = (Invoke-Api Post "/assistant/auto" @{ question = $simpleQuery } $QuestionHeaders).data
Assert-True ([long]$simple.datasetId -eq [long]$dataset.id) "Auto dataset selection failed for simple question. expected=$($dataset.id) actual=$($simple.datasetId) status=$($simple.status) answer=$($simple.answer)"
Assert-True ($simple.route -eq "DIRECT_RAG") "Simple question route was $($simple.route), expected DIRECT_RAG."
Assert-True ($simple.answer -match "5") "Simple answer did not include expected leave days. Answer: $($simple.answer)"
Assert-True (-not [string]::IsNullOrWhiteSpace($simple.traceId)) "Simple RAG response did not include traceId."
Assert-True ($simple.citations.Count -gt 0) "Simple RAG response did not include citations."

Write-Host "Checking complex question: auto dataset + AGENT + trace/citations"
$complexQuery = Decode-Utf8Base64 "5a+55q+U5bm05YGH6KeE5YiZ5Lul5Y+K6L+c56iL5Yqe5YWs6KaB5rGC"
$complex = (Invoke-Api Post "/assistant/auto" @{ question = $complexQuery } $QuestionHeaders).data
Assert-True ([long]$complex.datasetId -eq [long]$dataset.id) "Auto dataset selection failed for complex question. expected=$($dataset.id) actual=$($complex.datasetId) status=$($complex.status) answer=$($complex.answer)"
Assert-True ($complex.route -eq "AGENT") "Complex question route was $($complex.route), expected AGENT."
Assert-True ($complex.steps.Count -gt 0) "Agent did not return execution steps."
Assert-True (-not [string]::IsNullOrWhiteSpace($complex.traceId)) "Agent response did not include traceId."
Assert-True ($complex.citations.Count -gt 0) "Agent response did not include citations."

Write-Host "Checking retrieval trace replay"
$replay = (Invoke-Api Get "/traces/$($simple.traceId)/replay").data
Assert-True ($replay.found -eq $true) "Trace replay did not find the simple trace."
Assert-True (-not [string]::IsNullOrWhiteSpace($replay.failureStage)) "Trace replay did not include failureStage."
Assert-True ($replay.actionHints.Count -gt 0) "Trace replay did not include actionHints."
Assert-True ($replay.failureStage -ne "RECALL") "Answered simple question was diagnosed as RECALL."
Assert-True (-not [string]::IsNullOrWhiteSpace($replay.finalPrompt)) "Trace replay did not include finalPrompt."
Assert-True (-not [string]::IsNullOrWhiteSpace($replay.promptContext)) "Trace replay did not include promptContext."
Assert-True ($replay.contextMaxTokens -gt 0) "Trace replay did not include contextMaxTokens."
Assert-True (-not [string]::IsNullOrWhiteSpace($replay.answerSource)) "Trace replay did not include answerSource."

Write-Host "Checking high-risk Agent approval and approver audit"
$riskQuery = Decode-Utf8Base64 "6K+35a6h5om55Yig6Zmk6L+Z5Lu95paH5qGj"
$risk = (Invoke-Api Post "/assistant/auto" @{ question = $riskQuery } $QuestionHeaders).data
Assert-True ([long]$risk.datasetId -eq [long]$dataset.id) "Auto dataset selection failed for high-risk question. expected=$($dataset.id) actual=$($risk.datasetId) status=$($risk.status) answer=$($risk.answer)"
Assert-True ($risk.route -eq "AGENT") "High-risk question route was $($risk.route), expected AGENT."
Assert-True ($risk.status -eq "WAITING_APPROVAL") "High-risk Agent status was $($risk.status), expected WAITING_APPROVAL."
Assert-True (-not [string]::IsNullOrWhiteSpace($risk.approvalId)) "High-risk Agent did not return approvalId."
$approved = (Invoke-Api Post "/approvals/$($risk.approvalId)/approve" @{ datasetId = $risk.datasetId; question = $riskQuery } $ApproverHeaders).data
Assert-True ($approved.status -eq "DONE") "Approved Agent status was $($approved.status), expected DONE."
$approvals = (Invoke-Api Get "/admin/approvals").data
$approvalRecord = @($approvals | Where-Object { $_.id -eq $risk.approvalId })[0]
Assert-True ($null -ne $approvalRecord) "Approval record was not visible in admin list."
Assert-True ($approvalRecord.status -eq "APPROVED") "Approval record status was not APPROVED."
Assert-True ($approvalRecord.approvedBy -eq $approverId) "Approver audit was incorrect: $($approvalRecord.approvedBy)."

Write-Host "Checking unified Q&A audit for RAG and Agent answers"
$qaAudits = (Invoke-Api Get "/admin/qa-audits").data
$simpleAudit = @($qaAudits | Where-Object { [string]$_.traceId -eq [string]$simple.traceId -and [string]$_.mode -eq "rag" })[0]
Assert-True ($null -ne $simpleAudit) "RAG answer was not visible in unified Q&A audit."
$complexAudit = @($qaAudits | Where-Object { [string]$_.traceId -eq [string]$complex.traceId -and [string]$_.mode -eq "agent" })[0]
Assert-True ($null -ne $complexAudit) "Agent answer was not visible in unified Q&A audit."
Assert-True ($simpleAudit.userId -eq $AdminUser) "RAG audit did not include the requester userId."
Assert-True ($complexAudit.userId -eq $AdminUser) "Agent audit did not include the requester userId."
Assert-True (-not [string]::IsNullOrWhiteSpace($simpleAudit.query)) "RAG audit did not include original query."
Assert-True (-not [string]::IsNullOrWhiteSpace($complexAudit.query)) "Agent audit did not include original query."
Assert-True (-not [string]::IsNullOrWhiteSpace($simpleAudit.answer)) "RAG audit did not include final answer."
Assert-True (-not [string]::IsNullOrWhiteSpace($complexAudit.answer)) "Agent audit did not include final answer."

Write-Host "Smoke acceptance passed"
[pscustomobject]@{
  backend = $BackendUrl
  datasetId = $dataset.id
  datasetName = $dataset.name
  documents = $files.Count
  simpleRoute = $simple.route
  complexRoute = $complex.route
  simpleTraceId = $simple.traceId
  complexTraceId = $complex.traceId
  complexCitations = $complex.citations.Count
  failureStage = $replay.failureStage
  answerSource = $replay.answerSource
  approvalId = $risk.approvalId
  approvedBy = $approvalRecord.approvedBy
  requesterUser = $AdminUser
  ragAuditMode = $simpleAudit.mode
  agentAuditMode = $complexAudit.mode
} | Format-List
