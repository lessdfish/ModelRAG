[CmdletBinding()]
param(
    [string]$OutputDirectory = (Join-Path $PSScriptRoot "..\..\backups"),
    [switch]$SkipMinio
)

$ErrorActionPreference = "Stop"
$projectDirectory = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$outputDirectory = [System.IO.Path]::GetFullPath((Join-Path $projectDirectory $OutputDirectory))
$dbName = $env:POSTGRES_DB
$dbUser = $env:POSTGRES_USER
$minioUser = $env:MINIO_ROOT_USER
$minioPassword = $env:MINIO_ROOT_PASSWORD
$bucket = $env:MODELRAG_S3_BUCKET
$endpoint = if ($env:MODELRAG_S3_ENDPOINT) { $env:MODELRAG_S3_ENDPOINT } else { "http://localhost:19000" }

if ([string]::IsNullOrWhiteSpace($dbName) -or [string]::IsNullOrWhiteSpace($dbUser)) {
    throw "POSTGRES_DB and POSTGRES_USER must be supplied before backup."
}
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw "docker is required." }

New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$dumpName = "modelrag-$stamp.dump"
$containerDump = "/tmp/$dumpName"
$dumpPath = Join-Path $outputDirectory $dumpName
$composeArgs = @("compose", "--project-directory", $projectDirectory)

Push-Location $projectDirectory
try {
    & docker @composeArgs exec -T postgres pg_dump -U $dbUser -d $dbName --format=custom --no-owner --file=$containerDump
    if ($LASTEXITCODE -ne 0) { throw "pg_dump failed." }
    & docker @composeArgs cp "postgres:$containerDump" $dumpPath
    if ($LASTEXITCODE -ne 0) { throw "Copying PostgreSQL backup failed." }
    & docker @composeArgs exec -T postgres rm -f $containerDump

    if (-not $SkipMinio) {
        if ([string]::IsNullOrWhiteSpace($minioUser) -or [string]::IsNullOrWhiteSpace($minioPassword) -or [string]::IsNullOrWhiteSpace($bucket)) {
            throw "MINIO_ROOT_USER, MINIO_ROOT_PASSWORD and MODELRAG_S3_BUCKET are required for the MinIO backup."
        }
        if (-not (Get-Command mc -ErrorAction SilentlyContinue)) {
            throw "mc is required for MinIO backup; rerun with -SkipMinio only when the object backup is handled elsewhere."
        }
        $objectPath = Join-Path $outputDirectory "minio-$stamp"
        New-Item -ItemType Directory -Force -Path $objectPath | Out-Null
        $alias = "modelrag-backup-$PID"
        & mc alias set $alias $endpoint $minioUser $minioPassword | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Configuring the MinIO client failed." }
        & mc mirror --overwrite "$alias/$bucket" $objectPath
        if ($LASTEXITCODE -ne 0) { throw "MinIO backup failed." }
        & mc alias remove $alias | Out-Null
    }
} finally {
    Pop-Location
}

Write-Output "PostgreSQL backup: $dumpPath"
if (-not $SkipMinio) { Write-Output "MinIO backup: $(Join-Path $outputDirectory "minio-$stamp")" }
