package com.amha.service.impl;

import com.amha.entity.FileUpload;
import com.amha.mapper.FileUploadMapper;
import com.amha.storage.FileObjectStat;
import com.amha.storage.FileObjectStore;
import com.amha.storage.LocalFileObjectStore;
import com.amha.storage.FileStorageException;
import com.amha.storage.StorageObjectKey;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public class LocalToMinioFileMigrationService {

    private static final int DEFAULT_BATCH_SIZE = 100;
    private static final int MAX_FAILURE_DETAILS = 50;

    private final FileUploadMapper fileUploadMapper;
    private final LocalFileObjectStore localStore;
    private final FileObjectStore targetStore;
    private final int batchSize;

    public LocalToMinioFileMigrationService(FileUploadMapper fileUploadMapper,
                                            LocalFileObjectStore localStore,
                                            FileObjectStore targetStore) {
        this(fileUploadMapper, localStore, targetStore, DEFAULT_BATCH_SIZE);
    }

    public LocalToMinioFileMigrationService(FileUploadMapper fileUploadMapper,
                                            LocalFileObjectStore localStore,
                                            FileObjectStore targetStore,
                                            int batchSize) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("Migration batch size must be between 1 and 1000");
        }
        this.fileUploadMapper = fileUploadMapper;
        this.localStore = localStore;
        this.targetStore = targetStore;
        this.batchSize = batchSize;
    }

    public MigrationReport migrate(boolean dryRun) {
        long scanned = 0;
        long copied = 0;
        long wouldCopy = 0;
        long alreadyVerified = 0;
        long failureCount = 0;
        long lastId = 0;
        List<String> failures = new ArrayList<>();

        while (true) {
            List<FileUpload> page = fileUploadMapper.selectList(new LambdaQueryWrapper<FileUpload>()
                    .gt(FileUpload::getId, lastId)
                    .orderByAsc(FileUpload::getId)
                    .last("LIMIT " + batchSize));
            if (page == null || page.isEmpty()) {
                break;
            }

            boolean infrastructureFailure = false;
            for (FileUpload record : page) {
                scanned++;
                if (record == null || record.getId() == null) {
                    failureCount++;
                    addFailure(failures, "row-without-id: invalid database record");
                    break;
                }
                lastId = record.getId();
                try {
                    Result result = migrateRecord(record, dryRun);
                    switch (result) {
                        case COPIED -> copied++;
                        case WOULD_COPY -> wouldCopy++;
                        case ALREADY_VERIFIED -> alreadyVerified++;
                    }
                } catch (Exception e) {
                    failureCount++;
                    addFailure(failures, record.getId() + ": " + failureReason(e));
                    if (e instanceof FileStorageException) {
                        infrastructureFailure = true;
                        break;
                    }
                }
            }

            if (infrastructureFailure || page.size() < batchSize
                    || page.stream().anyMatch(row -> row == null || row.getId() == null)) {
                break;
            }
        }

        return new MigrationReport(scanned, copied, wouldCopy, alreadyVerified, failureCount, List.copyOf(failures));
    }

    private Result migrateRecord(FileUpload record, boolean dryRun) throws IOException {
        String key = StorageObjectKey.normalize(record.getFilePath());
        FileObjectStat localStat = localStore.stat(key);
        if (localStat == null) {
            throw new IllegalStateException("local_source_missing");
        }
        if (record.getFileSize() != null && record.getFileSize() != localStat.size()) {
            throw new IllegalStateException("database_size_mismatch");
        }

        FileObjectStat targetStat = targetStore.stat(key);
        if (targetStat != null) {
            verifySameContent(key, localStat, targetStat);
            return Result.ALREADY_VERIFIED;
        }
        if (dryRun) {
            return Result.WOULD_COPY;
        }

        try (InputStream source = localStore.open(key)) {
            targetStore.put(key, source, localStat.size(), mediaTypeFor(key));
        }
        FileObjectStat copiedStat = targetStore.stat(key);
        if (copiedStat == null) {
            throw new IllegalStateException("target_missing_after_copy");
        }
        verifySameContent(key, localStat, copiedStat);
        return Result.COPIED;
    }

    private void verifySameContent(String key, FileObjectStat source, FileObjectStat target) throws IOException {
        if (source.size() != target.size()) {
            throw new IllegalStateException("target_size_conflict");
        }
        if (!checksum(localStore, key).equals(checksum(targetStore, key))) {
            throw new IllegalStateException("target_content_conflict");
        }
    }

    private static String checksum(FileObjectStore store, String key) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = store.open(key);
                 DigestInputStream digestInput = new DigestInputStream(input, digest)) {
                digestInput.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String mediaTypeFor(String key) {
        String filename = key.substring(key.lastIndexOf('/') + 1);
        return MediaTypeFactory.getMediaType(filename)
                .orElse(MediaType.APPLICATION_OCTET_STREAM)
                .toString();
    }

    private static String failureReason(Exception exception) {
        if (exception instanceof IllegalArgumentException) {
            return "invalid_object_key";
        }
        if (exception instanceof IOException) {
            return "file_read_failed";
        }
        String message = exception.getMessage();
        if (message != null && message.matches("[a-z_]+")) {
            return message;
        }
        return "migration_failed";
    }

    private static void addFailure(List<String> failures, String detail) {
        if (failures.size() < MAX_FAILURE_DETAILS) {
            failures.add(detail);
        }
    }

    private enum Result {
        COPIED,
        WOULD_COPY,
        ALREADY_VERIFIED
    }

    public record MigrationReport(long scanned,
                                  long copied,
                                  long wouldCopy,
                                  long alreadyVerified,
                                  long failureCount,
                                  List<String> failures) {
    }
}
