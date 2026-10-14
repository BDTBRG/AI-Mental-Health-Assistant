package com.amha.storage;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class StorageObjectKey {

    private StorageObjectKey() {
    }

    public static String normalize(String key) {
        if (key == null || key.isBlank() || key.contains("\\") || key.contains(":")
                || key.startsWith("/") || key.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid object key");
        }
        try {
            Path path = Paths.get(key);
            if (path.isAbsolute()) {
                throw new IllegalArgumentException("Invalid object key");
            }
            for (Path segment : path) {
                if (".".equals(segment.toString()) || "..".equals(segment.toString())) {
                    throw new IllegalArgumentException("Invalid object key");
                }
            }
            String normalized = path.normalize().toString().replace('\\', '/');
            if (!key.equals(normalized)) {
                throw new IllegalArgumentException("Invalid object key");
            }
            return normalized;
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Invalid object key", e);
        }
    }
}
