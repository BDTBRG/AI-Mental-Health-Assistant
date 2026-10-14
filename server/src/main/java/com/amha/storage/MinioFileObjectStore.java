package com.amha.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

public class MinioFileObjectStore implements FileObjectStore {

    private final MinioClient client;
    private final String bucket;

    public MinioFileObjectStore(MinioClient client, String bucket) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("MinIO bucket must not be blank");
        }
        this.client = client;
        this.bucket = bucket.trim();
    }

    public void verifyBucketAvailable() {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                throw new IllegalStateException("Configured MinIO bucket does not exist");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new FileStorageException("Unable to access configured MinIO bucket", e);
        }
    }

    @Override
    public void put(String key, InputStream content, long contentLength, String contentType) {
        String normalizedKey = StorageObjectKey.normalize(key);
        if (contentLength < 0) {
            throw new IllegalArgumentException("Object content length must not be negative");
        }
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(normalizedKey)
                    .stream(content, contentLength, null)
                    .contentType(contentType == null || contentType.isBlank()
                            ? "application/octet-stream" : contentType)
                    .build());
        } catch (Exception e) {
            throw new FileStorageException("Unable to write to MinIO object storage", e);
        }
    }

    @Override
    public InputStream open(String key) throws IOException {
        String normalizedKey = StorageObjectKey.normalize(key);
        try {
            return client.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(normalizedKey)
                    .build());
        } catch (ErrorResponseException e) {
            if (isMissingObject(e)) {
                throw new FileNotFoundException(normalizedKey);
            }
            throw new FileStorageException("Unable to read from MinIO object storage", e);
        } catch (Exception e) {
            throw new FileStorageException("Unable to read from MinIO object storage", e);
        }
    }

    @Override
    public FileObjectStat stat(String key) {
        String normalizedKey = StorageObjectKey.normalize(key);
        try {
            var response = client.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(normalizedKey)
                    .build());
            return new FileObjectStat(response.size());
        } catch (ErrorResponseException e) {
            if (isMissingObject(e)) {
                return null;
            }
            throw new FileStorageException("Unable to inspect MinIO object storage", e);
        } catch (Exception e) {
            throw new FileStorageException("Unable to inspect MinIO object storage", e);
        }
    }

    @Override
    public void delete(String key) {
        String normalizedKey = StorageObjectKey.normalize(key);
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(normalizedKey)
                    .build());
        } catch (Exception e) {
            throw new FileStorageException("Unable to delete from MinIO object storage", e);
        }
    }

    private static boolean isMissingObject(ErrorResponseException exception) {
        return exception.errorResponse() != null
                && ("NoSuchKey".equals(exception.errorResponse().code())
                || "NoSuchObject".equals(exception.errorResponse().code()));
    }
}
