package com.amha.storage;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveBucketArgs;
import io.minio.RemoveObjectArgs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnabledIfEnvironmentVariable(named = "MINIO_IT_ENDPOINT", matches = ".+")
@EnabledIfEnvironmentVariable(named = "MINIO_IT_ACCESS_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "MINIO_IT_SECRET_KEY", matches = ".+")
class MinioFileObjectStoreIntegrationTest {

    @Test
    void storesReadsInspectsAndDeletesObjectsUsingTheS3CompatibleApi() throws Exception {
        String endpoint = System.getenv("MINIO_IT_ENDPOINT");
        String accessKey = System.getenv("MINIO_IT_ACCESS_KEY");
        String secretKey = System.getenv("MINIO_IT_SECRET_KEY");
        String bucket = "amha-it-" + UUID.randomUUID().toString().replace("-", "");
        String key = "integration/" + UUID.randomUUID() + ".txt";
        byte[] expected = "MinIO SDK integration check".getBytes(StandardCharsets.UTF_8);

        MinioClient client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();

        try {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            MinioFileObjectStore store = new MinioFileObjectStore(client, bucket);
            store.verifyBucketAvailable();

            try (InputStream input = new java.io.ByteArrayInputStream(expected)) {
                store.put(key, input, expected.length, "text/plain");
            }

            FileObjectStat stat = store.stat(key);
            assertNotNull(stat);
            assertEquals(expected.length, stat.size());
            try (InputStream input = store.open(key)) {
                assertArrayEquals(expected, input.readAllBytes());
            }

            String missingKey = "integration/missing-" + UUID.randomUUID() + ".txt";
            assertNull(store.stat(missingKey));
            assertThrows(FileNotFoundException.class, () -> store.open(missingKey));

            store.delete(key);
            assertNull(store.stat(key));
        } finally {
            if (client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                try {
                    client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
                } finally {
                    client.removeBucket(RemoveBucketArgs.builder().bucket(bucket).build());
                }
            }
        }
    }
}
