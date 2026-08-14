[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$BackupDirectory,
    [switch]$ConfirmRestore,
    [switch]$RestoreMinio
)

$ErrorActionPreference = "Stop"
if (-not $ConfirmRestore) {
    throw "Restore is destructive. Re-run with -ConfirmRestore after stopping application writers and verifying the backup."
}

$projectDirectory = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$backupDirectory = (Resolve-Path $BackupDirectory).Path
$dbName = $env:POSTGRES_DB
$dbUser = $env:POSTGRES_USER
if ([string]::IsNullOrWhiteSpace($dbName) -or [string]::IsNullOrWhiteSpace($dbUser)) {
    throw "POSTGRES_DB and POSTGRES_USER must be supplied before restore."
}
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw "docker is required." }

$dumpFiles = @(Get-ChildItem -LiteralPath $backupDirectory -Filter "*.dump" -File)
if ($dumpFiles.Count -ne 1) {
    throw "BackupDirectory must contain exactly one PostgreSQL .dump file."
}

$containerDump = "/tmp/$($dumpFiles[0].Name)"
$composeArgs = @("compose", "--project-directory", $projectDirectory)
Push-Location $projectDirectory
try {
    & docker @composeArgs cp $dumpFiles[0].FullName "postgres:$containerDump"
    if ($LASTEXITCODE -ne 0) { throw "Copying PostgreSQL backup failed." }
    & docker @composeArgs exec -T postgres pg_restore -U $dbUser -d $dbName --clean --if-exists --no-owner $containerDump
    if ($LASTEXITCODE -ne 0) { throw "pg_restore failed." }
    & docker @composeArgs exec -T postgres rm -f $containerDump

    if ($RestoreMinio) {
        $minioDirectory = @(Get-ChildItem -LiteralPath $backupDirectory -Directory -Filter "minio-*")
        if ($minioDirectory.Count -ne 1) { throw "RestoreMinio requires exactly one minio-* directory." }
        if (-not (Get-Command mc -ErrorAction SilentlyContinue)) { throw "mc is required for MinIO restore." }
        $minioUser = $env:MINIO_ROOT_USER
        $minioPassword = $env:MINIO_ROOT_PASSWORD
        $bucket = $env:MODELRAG_S3_BUCKET
        $endpoint = if ($env:MODELRAG_S3_ENDPOINT) { $env:MODELRAG_S3_ENDPOINT } else { "http://localhost:19000" }
        if ([string]::IsNullOrWhiteSpace($minioUser) -or [string]::IsNullOrWhiteSpace($minioPassword) -or [string]::IsNullOrWhiteSpace($bucket)) {
            throw "MINIO_ROOT_USER, MINIO_ROOT_PASSWORD and MODELRAG_S3_BUCKET are required for MinIO restore."
        }
        $alias = "modelrag-restore-$PID"
        & mc alias set $alias $endpoint $minioUser $minioPassword | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Configuring the MinIO client failed." }
        & mc mirror --overwrite $minioDirectory[0].FullName "$alias/$bucket"
        if ($LASTEXITCODE -ne 0) { throw "MinIO restore failed." }
        & mc alias remove $alias | Out-Null
    }
} finally {
    Pop-Location
}

Write-Output "Restore completed from $backupDirectory"
