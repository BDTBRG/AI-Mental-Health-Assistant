# Local uploads to MinIO migration

The application keeps the existing `/file/uploads/<relative-path>` URL and the `file_upload.file_path` value when changing storage. Local storage remains the default. The migration copies records referenced by `file_upload` into an already-created S3-compatible bucket and never removes local files.

## Before migration

1. Back up the MySQL database and the configured local upload directory.
2. Pause uploads while copying and switching the backend. This prevents a new local upload from landing after its migration batch has passed.
3. Create the MinIO bucket and a credential that can list the bucket and read/write/delete objects. The application does not create buckets automatically.
4. Confirm `file.upload.path` points at the directory that contains the existing relative paths.

## Preflight

Set the MinIO connection variables for the process. Do not put credentials in source control:

```powershell
$env:MINIO_ENDPOINT = "http://localhost:9000"
$env:MINIO_BUCKET = "amha-uploads"
$env:MINIO_ACCESS_KEY = "<migration-access-key>"
$env:MINIO_SECRET_KEY = "<migration-secret-key>"
$env:FILE_STORAGE_BACKEND = "local"
$env:FILE_STORAGE_MIGRATION_ENABLED = "true"
$env:FILE_STORAGE_MIGRATION_DRY_RUN = "true"
java -jar .\target\amha-server-1.0.0.jar --spring.main.web-application-type=none
```

The default is a dry run. It checks every database-referenced local file, compares its size with database metadata when present, checks the bucket, and detects conflicting target objects without writing any object. Review the summary and any failed record IDs before applying.

## Copy and verify

Keep `FILE_STORAGE_BACKEND=local`, then explicitly opt in to writes:

```powershell
$env:FILE_STORAGE_MIGRATION_DRY_RUN = "false"
java -jar .\target\amha-server-1.0.0.jar --spring.main.web-application-type=none
```

The migrator processes rows in keyset-paginated batches. Existing target objects are accepted only when their size and SHA-256 digest match the local source. New copies are read back and checked the same way. A failed or interrupted run can be repeated; matching objects are verified and skipped, while mismatches stop the cutover. The process exits unsuccessfully if any record failed. Failure detail is bounded to the first 50 database IDs in logs.

After the copy reports zero failures, run once more with dry-run enabled. Confirm the report shows every row as already verified and no rows needing a copy.

## Cut over

Pause uploads, perform the verified dry run, then set:

```text
FILE_STORAGE_BACKEND=minio
FILE_STORAGE_MIGRATION_ENABLED=false
```

Restart the application and verify representative existing image URLs and a new upload. The browser-visible URL and stored relative path remain unchanged. Keep the local directory and its backup until the new storage has been independently backed up and the chosen retention period has elapsed; this implementation intentionally has no local-file deletion step.

## Rollback

Pause uploads and set `FILE_STORAGE_BACKEND=local`. Local originals are preserved, so the previous local backend remains available. Any uploads created after cutover exist only in MinIO; copy them back with a separately reviewed reverse migration before relying on local-only rollback.
