package com.amha.service.impl;

import com.amha.entity.FileUpload;
import com.amha.mapper.FileUploadMapper;
import com.amha.storage.FileObjectStat;
import com.amha.storage.FileObjectStore;
import com.amha.storage.FileStorageException;
import com.amha.storage.LocalFileObjectStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LocalToMinioFileMigrationServiceTest {

    @TempDir
    Path uploadDirectory;

    private FileUploadMapper fileUploadMapper;
    private LocalFileObjectStore localStore;
    private InMemoryFileObjectStore targetStore;
    private LocalToMinioFileMigrationService migrationService;

    @BeforeEach
    void setUp() {
        fileUploadMapper = mock(FileUploadMapper.class);
        localStore = new LocalFileObjectStore(uploadDirectory.toString());
        targetStore = new InMemoryFileObjectStore();
        migrationService = new LocalToMinioFileMigrationService(fileUploadMapper, localStore, targetStore);
    }

    @Test
    void dryRunChecksDatabaseFilesWithoutWritingToTarget() throws Exception {
        FileUpload record = fileRecord(1L, "2026/10/13/cover.png", "image-content".getBytes());
        writeLocal(record.getFilePath(), "image-content".getBytes());
        returnPages(record);

        var report = migrationService.migrate(true);

        assertEquals(1, report.scanned());
        assertEquals(1, report.wouldCopy());
        assertEquals(0, report.copied());
        assertNull(targetStore.stat(record.getFilePath()));
        assertTrue(Files.exists(uploadDirectory.resolve(record.getFilePath())));
    }

    @Test
    void copiesAndVerifiesObjectsAndRerunIsIdempotent() throws Exception {
        FileUpload record = fileRecord(7L, "2026/10/13/cover.png", "image-content".getBytes());
        writeLocal(record.getFilePath(), "image-content".getBytes());
        returnPages(record);

        var firstRun = migrationService.migrate(false);

        assertEquals(1, firstRun.copied());
        assertEquals("image-content", new String(targetStore.read(record.getFilePath())));
        assertTrue(Files.exists(uploadDirectory.resolve(record.getFilePath())));

        returnPages(record);
        var secondRun = migrationService.migrate(false);

        assertEquals(0, secondRun.copied());
        assertEquals(1, secondRun.alreadyVerified());
        assertEquals(1, targetStore.putCount());
    }

    @Test
    void doesNotOverwriteAnExistingObjectWithDifferentContent() throws Exception {
        FileUpload record = fileRecord(9L, "2026/10/13/cover.png", "local-content".getBytes());
        writeLocal(record.getFilePath(), "local-content".getBytes());
        targetStore.put(record.getFilePath(), new ByteArrayInputStream("other-content".getBytes()),
                "other-content".length(), "image/png");
        returnPages(record);

        var report = migrationService.migrate(false);

        assertEquals(1, report.failureCount());
        assertEquals("other-content", new String(targetStore.read(record.getFilePath())));
        assertTrue(Files.exists(uploadDirectory.resolve(record.getFilePath())));
    }

    @Test
    void recordsMissingSourceAndMetadataSizeMismatchAsFailures() throws Exception {
        FileUpload missing = fileRecord(11L, "2026/10/13/missing.png", new byte[4]);
        FileUpload wrongSize = fileRecord(12L, "2026/10/13/wrong-size.png", new byte[4]);
        writeLocal(wrongSize.getFilePath(), new byte[]{1, 2, 3});
        returnPages(missing, wrongSize);

        var report = migrationService.migrate(true);

        assertEquals(2, report.failureCount());
        assertEquals(2, report.scanned());
        assertNull(targetStore.stat(missing.getFilePath()));
        assertNull(targetStore.stat(wrongSize.getFilePath()));
    }

    @Test
    void stopsPagingWhenObjectStorageBecomesUnavailable() throws Exception {
        FileUpload first = fileRecord(20L, "2026/10/13/first.png", new byte[]{1});
        FileUpload second = fileRecord(21L, "2026/10/13/second.png", new byte[]{2});
        writeLocal(first.getFilePath(), new byte[]{1});
        writeLocal(second.getFilePath(), new byte[]{2});
        targetStore.statFailure = new FileStorageException("target unavailable", new IOException("offline"));
        when(fileUploadMapper.selectList(any())).thenReturn(List.of(first), List.of(second), List.of());
        migrationService = new LocalToMinioFileMigrationService(fileUploadMapper, localStore, targetStore, 1);

        var report = migrationService.migrate(true);

        assertEquals(1, report.scanned());
        assertEquals(1, report.failureCount());
        verify(fileUploadMapper, times(1)).selectList(any());
    }

    private void returnPages(FileUpload... records) {
        when(fileUploadMapper.selectList(any())).thenReturn(List.of(records), List.of());
    }

    private void writeLocal(String key, byte[] content) throws IOException {
        Path destination = uploadDirectory.resolve(key);
        Files.createDirectories(destination.getParent());
        Files.write(destination, content);
    }

    private static FileUpload fileRecord(long id, String key, byte[] content) {
        FileUpload record = new FileUpload();
        record.setId(id);
        record.setFilePath(key);
        record.setFileSize((long) content.length);
        return record;
    }

    private static final class InMemoryFileObjectStore implements FileObjectStore {
        private final Map<String, byte[]> objects = new HashMap<>();
        private int putCount;
        private RuntimeException statFailure;

        @Override
        public void put(String key, InputStream content, long size, String contentType) {
            try {
                objects.put(key, content.readAllBytes());
                putCount++;
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public InputStream open(String key) throws IOException {
            byte[] content = objects.get(key);
            if (content == null) {
                throw new java.io.FileNotFoundException(key);
            }
            return new ByteArrayInputStream(content);
        }

        @Override
        public FileObjectStat stat(String key) {
            if (statFailure != null) {
                throw statFailure;
            }
            byte[] content = objects.get(key);
            return content == null ? null : new FileObjectStat(content.length);
        }

        @Override
        public void delete(String key) {
            objects.remove(key);
        }

        private byte[] read(String key) {
            return objects.get(key);
        }

        private int putCount() {
            return putCount;
        }
    }
}
