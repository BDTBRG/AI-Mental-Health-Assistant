package com.amha.config;

import com.amha.storage.FileObjectStore;
import com.amha.storage.LocalFileObjectStore;
import com.amha.storage.MinioConfiguredCondition;
import com.amha.storage.MinioFileObjectStore;
import io.minio.MinioClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FileStorageConfiguration {

    @Bean
    public LocalFileObjectStore localFileObjectStore(
            @Value("${file.upload.path:./uploads}") String uploadPath) {
        return new LocalFileObjectStore(uploadPath);
    }

    @Bean
    @Conditional(MinioConfiguredCondition.class)
    public MinioFileObjectStore minioFileObjectStore(
            @Value("${file.upload.s3.endpoint}") String endpoint,
            @Value("${file.upload.s3.access-key}") String accessKey,
            @Value("${file.upload.s3.secret-key}") String secretKey,
            @Value("${file.upload.s3.bucket}") String bucket) {
        MinioClient client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        return new MinioFileObjectStore(client, bucket);
    }

    @Bean
    @Primary
    public FileObjectStore activeFileObjectStore(
            LocalFileObjectStore localFileObjectStore,
            ObjectProvider<MinioFileObjectStore> minioStoreProvider,
            @Value("${file.upload.backend:local}") String backend) {
        if ("local".equalsIgnoreCase(backend.trim())) {
            return localFileObjectStore;
        }
        if ("minio".equalsIgnoreCase(backend.trim())) {
            MinioFileObjectStore minioFileObjectStore = minioStoreProvider.getIfAvailable();
            if (minioFileObjectStore == null) {
                throw new IllegalStateException("MinIO file storage is selected but its connection is not configured");
            }
            minioFileObjectStore.verifyBucketAvailable();
            return minioFileObjectStore;
        }
        throw new IllegalStateException("Unsupported file.upload.backend: " + backend);
    }
}
