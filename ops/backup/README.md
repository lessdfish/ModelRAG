# Backup and restore

`backup.ps1` creates a PostgreSQL custom-format dump and mirrors the versioned MinIO bucket into the same timestamped directory. Set the Compose `.env` values first. `mc` is required unless `-SkipMinio` is explicitly used.

Restore is intentionally destructive and requires `-ConfirmRestore`:

```powershell
.\ops\backup\restore.ps1 -BackupDirectory .\backups\20260812-210000 -ConfirmRestore -RestoreMinio
```

Elasticsearch is not treated as a source of truth. After a restore, re-run the index rebuild/outbox process so its versioned search index is reconstructed from PostgreSQL and MinIO.
