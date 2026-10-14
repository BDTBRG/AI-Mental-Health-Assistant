package com.amha.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LocalFileObjectStoreTest {

    @TempDir
    Path storageRoot;

    @Test
    void writesAndReadsObjectsUnderTheConfiguredRoot() throws Exception {
        LocalFileObjectStore store = new LocalFileObjectStore(storageRoot.toString());
        byte[] content = "safe image bytes".getBytes();

        store.put("2026/10/14/image.png", new ByteArrayInputStream(content), content.length, "image/png");

        assertEquals(content.length, store.stat("2026/10/14/image.png").size());
        try (var input = store.open("2026/10/14/image.png")) {
            assertArrayEquals(content, input.readAllBytes());
        }
    }

    @Test
    void rejectsTraversalAndDoesNotCreateFilesOutsideTheRoot() {
        LocalFileObjectStore store = new LocalFileObjectStore(storageRoot.toString());

        assertThrows(IllegalArgumentException.class,
                () -> store.put("../outside.txt", new ByteArrayInputStream(new byte[]{1}), 1, "text/plain"));
        assertFalse(Files.exists(storageRoot.getParent().resolve("outside.txt")));
    }

    @Test
    void removesPartialObjectWhenIncomingLengthDoesNotMatch() {
        LocalFileObjectStore store = new LocalFileObjectStore(storageRoot.toString());

        assertThrows(FileStorageException.class,
                () -> store.put("2026/10/14/partial.bin", new ByteArrayInputStream(new byte[]{1, 2, 3}),
                        4, "application/octet-stream"));

        assertNull(store.stat("2026/10/14/partial.bin"));
    }
}
