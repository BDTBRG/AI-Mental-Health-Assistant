package com.amha.config;

import com.amha.storage.FileObjectStore;
import com.amha.storage.LocalFileObjectStore;
import com.amha.storage.MinioFileObjectStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FileStorageConfigurationTest {

    private final FileStorageConfiguration configuration = new FileStorageConfiguration();
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(FileStorageConfiguration.class);

    @Test
    void usesLocalStorageWhenBackendIsUnspecifiedOrLocal() {
        LocalFileObjectStore localStore = new LocalFileObjectStore("./target/test-uploads");
        @SuppressWarnings("unchecked")
        ObjectProvider<MinioFileObjectStore> minioProvider = mock(ObjectProvider.class);

        FileObjectStore defaultStore = configuration.activeFileObjectStore(localStore, minioProvider, "local");
        FileObjectStore explicitLocalStore = configuration.activeFileObjectStore(localStore, minioProvider, "LOCAL");

        assertSame(localStore, defaultStore);
        assertSame(localStore, explicitLocalStore);
        verifyNoInteractions(minioProvider);
    }

    @Test
    void refusesMinioBackendWhenConnectionIsNotConfigured() {
        LocalFileObjectStore localStore = new LocalFileObjectStore("./target/test-uploads");
        @SuppressWarnings("unchecked")
        ObjectProvider<MinioFileObjectStore> minioProvider = mock(ObjectProvider.class);
        when(minioProvider.getIfAvailable()).thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> configuration.activeFileObjectStore(localStore, minioProvider, "minio"));
    }

    @Test
    void verifiesBucketBeforeSelectingMinioForLiveTraffic() {
        LocalFileObjectStore localStore = new LocalFileObjectStore("./target/test-uploads");
        MinioFileObjectStore minioStore = mock(MinioFileObjectStore.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<MinioFileObjectStore> minioProvider = mock(ObjectProvider.class);
        when(minioProvider.getIfAvailable()).thenReturn(minioStore);

        assertSame(minioStore, configuration.activeFileObjectStore(localStore, minioProvider, "minio"));
        verify(minioStore).verifyBucketAvailable();
    }

    @Test
    void rejectsUnknownBackendInsteadOfSilentlyFallingBack() {
        LocalFileObjectStore localStore = new LocalFileObjectStore("./target/test-uploads");
        @SuppressWarnings("unchecked")
        ObjectProvider<MinioFileObjectStore> minioProvider = mock(ObjectProvider.class);

        assertThrows(IllegalStateException.class,
                () -> configuration.activeFileObjectStore(localStore, minioProvider, "unknown"));
    }

    @Test
    void springContextUsesLocalStoreByDefaultAndDoesNotRequireMinio() {
        contextRunner.withPropertyValues("file.upload.path=./target/test-uploads")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertSame(context.getBean(LocalFileObjectStore.class), context.getBean(FileObjectStore.class));
                    assertFalse(context.containsBean("minioFileObjectStore"));
                });
    }

    @Test
    void createsMinioAdapterOnlyWhenAllConnectionSettingsArePresent() {
        contextRunner.withPropertyValues(
                        "file.upload.backend=local",
                        "file.upload.path=./target/test-uploads",
                        "file.upload.s3.endpoint=http://127.0.0.1:9000",
                        "file.upload.s3.bucket=test-bucket",
                        "file.upload.s3.access-key=test-access",
                        "file.upload.s3.secret-key=test-secret")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertSame(context.getBean(LocalFileObjectStore.class), context.getBean(FileObjectStore.class));
                    assertTrue(context.containsBean("minioFileObjectStore"));
                });
    }
}
