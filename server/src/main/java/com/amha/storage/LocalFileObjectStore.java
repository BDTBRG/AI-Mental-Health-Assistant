package com.amha.storage;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class LocalFileObjectStore implements FileObjectStore {

    private final Path root;

    public LocalFileObjectStore(String root) {
        if (root == null || root.isBlank()) {
            throw new IllegalArgumentException("File storage root must not be blank");
        }
        this.root = Paths.get(root).toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, InputStream content, long contentLength, String contentType) {
        String normalizedKey = StorageObjectKey.normalize(key);
        Path destination = resolveForWrite(normalizedKey);
        boolean created = false;
        try {
            Files.createDirectories(destination.getParent());
            Path canonicalRoot = root.toRealPath();
            Path canonicalParent = destination.getParent().toRealPath();
            if (!canonicalParent.startsWith(canonicalRoot)) {
                throw new FileStorageException("File storage path is outside the configured root", null);
            }
            long copied;
            try (var output = Files.newOutputStream(destination,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                created = true;
                copied = content.transferTo(output);
            }
            if (copied != contentLength) {
                deletePartial(destination);
                throw new FileStorageException("Stored file size does not match the input", null);
            }
        } catch (FileAlreadyExistsException e) {
            throw new FileStorageException("File already exists in local storage", e);
        } catch (FileStorageException e) {
            throw e;
        } catch (IOException e) {
            if (created) {
                deletePartial(destination);
            }
            throw new FileStorageException("Unable to write local file storage", e);
        }
    }

    @Override
    public InputStream open(String key) throws IOException {
        Path image = resolveExisting(key);
        return Files.newInputStream(image, StandardOpenOption.READ);
    }

    @Override
    public FileObjectStat stat(String key) {
        try {
            Path file = resolveExisting(key);
            return new FileObjectStat(Files.size(file));
        } catch (FileNotFoundException | java.nio.file.NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new FileStorageException("Unable to inspect local file storage", e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Path file = resolveExisting(key);
            Files.deleteIfExists(file);
        } catch (FileNotFoundException | java.nio.file.NoSuchFileException ignored) {
            // Deletion is intentionally idempotent for upload compensation and migration retries.
        } catch (IOException e) {
            throw new FileStorageException("Unable to delete from local file storage", e);
        }
    }

    private Path resolveForWrite(String normalizedKey) {
        try {
            Files.createDirectories(root);
            Path canonicalRoot = root.toRealPath();
            Path destination = canonicalRoot.resolve(normalizedKey).normalize();
            if (!destination.startsWith(canonicalRoot)) {
                throw new FileStorageException("File storage path is outside the configured root", null);
            }
            return destination;
        } catch (FileStorageException e) {
            throw e;
        } catch (IOException e) {
            throw new FileStorageException("Unable to access local file storage", e);
        }
    }

    private Path resolveExisting(String key) throws IOException {
        String normalizedKey = StorageObjectKey.normalize(key);
        Path canonicalRoot;
        try {
            canonicalRoot = root.toRealPath();
        } catch (java.nio.file.NoSuchFileException e) {
            throw new FileNotFoundException(normalizedKey);
        }
        Path candidate = canonicalRoot.resolve(normalizedKey).normalize();
        if (!candidate.startsWith(canonicalRoot)
                || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileNotFoundException(normalizedKey);
        }
        Path realFile = candidate.toRealPath();
        if (!realFile.startsWith(canonicalRoot)) {
            throw new FileNotFoundException(normalizedKey);
        }
        return realFile;
    }

    private static void deletePartial(Path destination) {
        try {
            Files.deleteIfExists(destination);
        } catch (IOException ignored) {
            // The failed write remains authoritative; orphan cleanup can be retried separately.
        }
    }
}
