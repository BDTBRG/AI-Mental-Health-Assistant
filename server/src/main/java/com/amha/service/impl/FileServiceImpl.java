package com.amha.service.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import com.amha.common.BusinessException;
import com.amha.entity.FileUpload;
import com.amha.mapper.FileUploadMapper;
import com.amha.service.FileService;
import com.amha.storage.FileObjectStore;
import com.amha.storage.FileObjectStat;
import com.amha.storage.FileStorageException;
import com.amha.storage.StorageObjectKey;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileServiceImpl implements FileService {

    private static final Set<String> ARTICLE_IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp");

    @Value("${file.upload.allowed-extensions:jpg,jpeg,png,gif,webp}")
    private String allowedExtensions;

    @Value("${file.upload.max-size-bytes:5242880}")
    private long maxFileSizeBytes = 5_242_880L;

    @Value("${file.upload.max-image-pixels:25000000}")
    private long maxImagePixels = 25_000_000L;

    @Override
    public Map<String, String> uploadFile(MultipartFile file, String businessType,
                                          String businessId, String businessField) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }
        if (!"ARTICLE".equalsIgnoreCase(trimToEmpty(businessType))
                || !"cover".equalsIgnoreCase(trimToEmpty(businessField))
                || businessId == null || businessId.isBlank() || businessId.length() > 100) {
            throw new BusinessException("仅允许为文章封面上传图片");
        }

        String originalFilename = safeOriginalFilename(file.getOriginalFilename());
        String ext = FileUtil.extName(originalFilename).toLowerCase(Locale.ROOT);
        Set<String> configuredExtensions = configuredExtensions();
        if (!ARTICLE_IMAGE_EXTENSIONS.contains(ext) || !configuredExtensions.contains(ext)) {
            throw new BusinessException("仅支持 JPG、PNG、GIF 或 WEBP 图片");
        }
        if (file.getSize() > maxFileSizeBytes) {
            throw new BusinessException("图片大小超出允许限制");
        }

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("读取上传文件失败");
        }
        if (!isValidImageContent(ext, content)) {
            throw new BusinessException("文件内容与图片格式不匹配");
        }

        String newFilename = IdUtil.fastSimpleUUID() + "." + ext;
        String dateDir = cn.hutool.core.date.DateUtil.format(new Date(), "yyyy/MM/dd");
        String relativePath = dateDir + "/" + newFilename;
        try (ByteArrayInputStream input = new ByteArrayInputStream(content)) {
            fileObjectStore.put(relativePath, input, content.length, imageMediaType(ext));
        } catch (IOException e) {
            throw new FileStorageException("Unable to read upload content for storage", e);
        }

        FileUpload record = new FileUpload();
        record.setFileName(originalFilename);
        record.setFilePath(relativePath);
        record.setFileSize((long) content.length);
        record.setBusinessType("ARTICLE");
        record.setBusinessId(businessId.trim());
        record.setBusinessField("cover");
        try {
            if (fileUploadMapper.insert(record) <= 0) {
                throw new IllegalStateException("File metadata was not saved");
            }
        } catch (RuntimeException e) {
            if (metadataMayExist(relativePath)) {
                log.warn("Preserving stored object because file metadata commit status is uncertain: {}", relativePath);
            } else {
                try {
                    fileObjectStore.delete(relativePath);
                } catch (RuntimeException cleanupException) {
                    // If compensation also fails, reconcile the orphan later.
                    log.warn("Unable to compensate a failed file metadata insert for object key {}",
                            relativePath, cleanupException);
                }
            }
            throw e;
        }

        Map<String, String> result = new HashMap<>();
        result.put("filePath", "/file/uploads/" + relativePath);
        return result;
    }

    @Override
    public Resource getPublicImage(String relativePath) {
        String normalizedPath = normalizeRelativePath(relativePath);
        String ext = FileUtil.extName(normalizedPath).toLowerCase(Locale.ROOT);
        if (!ARTICLE_IMAGE_EXTENSIONS.contains(ext) || !configuredExtensions().contains(ext)) {
            throw fileNotFound();
        }

        FileUpload metadata = fileUploadMapper.selectOne(new LambdaQueryWrapper<FileUpload>()
                .eq(FileUpload::getFilePath, normalizedPath));
        if (metadata == null
                || !"ARTICLE".equalsIgnoreCase(metadata.getBusinessType())
                || !"cover".equalsIgnoreCase(metadata.getBusinessField())) {
            throw fileNotFound();
        }

        FileObjectStat stat = fileObjectStore.stat(normalizedPath);
        if (stat == null) {
            throw fileNotFound();
        }
        String filename = normalizedPath.substring(normalizedPath.lastIndexOf('/') + 1);
        return new StoredFileResource(fileObjectStore, normalizedPath, filename, stat.size());
    }

    private Set<String> configuredExtensions() {
        Set<String> configured = new HashSet<>();
        for (String configuredExtension : allowedExtensions.split(",")) {
            configured.add(configuredExtension.trim().toLowerCase(Locale.ROOT));
        }
        return configured;
    }

    private static String normalizeRelativePath(String relativePath) {
        try {
            return StorageObjectKey.normalize(relativePath);
        } catch (IllegalArgumentException e) {
            throw fileNotFound();
        }
    }

    private static String imageMediaType(String extension) {
        return switch (extension) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            default -> "application/octet-stream";
        };
    }

    private boolean metadataMayExist(String relativePath) {
        try {
            return fileUploadMapper.selectOne(new LambdaQueryWrapper<FileUpload>()
                    .eq(FileUpload::getFilePath, relativePath)) != null;
        } catch (RuntimeException lookupException) {
            log.warn("Unable to determine file metadata commit status for object key {}; preserving object",
                    relativePath, lookupException);
            return true;
        }
    }

    private static String safeOriginalFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new BusinessException("文件名无效");
        }
        String normalized = originalFilename.replace('\\', '/');
        String filename = normalized.substring(normalized.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}]", "");
        if (filename.isBlank() || filename.length() > 255) {
            throw new BusinessException("文件名无效");
        }
        return filename;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String detectImageFormat(byte[] content) {
        if (content.length >= 8
                && (content[0] & 0xff) == 0x89 && content[1] == 0x50
                && content[2] == 0x4e && content[3] == 0x47
                && content[4] == 0x0d && content[5] == 0x0a
                && content[6] == 0x1a && content[7] == 0x0a) {
            return "png";
        }
        if (content.length >= 3
                && (content[0] & 0xff) == 0xff
                && (content[1] & 0xff) == 0xd8
                && (content[2] & 0xff) == 0xff) {
            return "jpeg";
        }
        if (content.length >= 6
                && content[0] == 'G' && content[1] == 'I' && content[2] == 'F'
                && content[3] == '8' && (content[4] == '7' || content[4] == '9')
                && content[5] == 'a') {
            return "gif";
        }
        if (content.length >= 12
                && content[0] == 'R' && content[1] == 'I' && content[2] == 'F' && content[3] == 'F'
                && content[8] == 'W' && content[9] == 'E' && content[10] == 'B' && content[11] == 'P') {
            return "webp";
        }
        return null;
    }

    private static boolean extensionMatchesFormat(String ext, String format) {
        return "jpeg".equals(format) ? Set.of("jpg", "jpeg").contains(ext) : ext.equals(format);
    }

    private boolean isValidImageContent(String extension, byte[] content) {
        String detectedFormat = detectImageFormat(content);
        if (detectedFormat == null || !extensionMatchesFormat(extension, detectedFormat)) {
            return false;
        }
        if ("webp".equals(detectedFormat)) {
            return hasValidWebpContainer(content, maxImagePixels);
        }

        try (ImageInputStream imageInput = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (imageInput == null) {
                return false;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                return false;
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > maxImagePixels) {
                    return false;
                }
                return reader.read(0) != null;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean hasValidWebpContainer(byte[] content, long maxImagePixels) {
        if (content.length < 20 || readUInt32LittleEndian(content, 4) + 8L != content.length) {
            return false;
        }
        int offset = 12;
        boolean hasImageFrame = false;
        while (offset + 8 <= content.length) {
            String chunkType = new String(content, offset, 4, java.nio.charset.StandardCharsets.US_ASCII);
            long chunkSize = readUInt32LittleEndian(content, offset + 4);
            long chunkEnd = offset + 8L + chunkSize;
            if (chunkEnd > content.length) {
                return false;
            }

            switch (chunkType) {
                case "VP8 " -> hasImageFrame |= isValidVp8Frame(content, offset + 8, chunkSize, maxImagePixels);
                case "VP8L" -> hasImageFrame |= isValidVp8lFrame(content, offset + 8, chunkSize, maxImagePixels);
                case "VP8X" -> {
                    if (!isValidVp8xHeader(content, offset + 8, chunkSize, maxImagePixels)) {
                        return false;
                    }
                }
                case "ANMF" -> hasImageFrame |= hasValidWebpFrameChunks(
                        content, offset + 24, chunkEnd, maxImagePixels);
                default -> { /* Metadata and animation chunks are valid RIFF members. */ }
            }

            long nextOffset = chunkEnd + (chunkSize & 1L);
            if (nextOffset > content.length) {
                return false;
            }
            offset = (int) nextOffset;
        }
        return offset == content.length && hasImageFrame;
    }

    private static boolean hasValidWebpFrameChunks(byte[] content, int offset, long end, long maxImagePixels) {
        boolean hasImageFrame = false;
        while (offset + 8L <= end) {
            String chunkType = new String(content, offset, 4, java.nio.charset.StandardCharsets.US_ASCII);
            long chunkSize = readUInt32LittleEndian(content, offset + 4);
            long chunkEnd = offset + 8L + chunkSize;
            if (chunkEnd > end) {
                return false;
            }
            if ("VP8 ".equals(chunkType)) {
                hasImageFrame |= isValidVp8Frame(content, offset + 8, chunkSize, maxImagePixels);
            } else if ("VP8L".equals(chunkType)) {
                hasImageFrame |= isValidVp8lFrame(content, offset + 8, chunkSize, maxImagePixels);
            }
            offset = (int) (chunkEnd + (chunkSize & 1L));
        }
        return offset == end && hasImageFrame;
    }

    private static boolean isValidVp8Frame(byte[] content, int payloadOffset, long chunkSize, long maxImagePixels) {
        if (chunkSize < 10 || (content[payloadOffset + 3] & 0xff) != 0x9d
                || (content[payloadOffset + 4] & 0xff) != 0x01
                || (content[payloadOffset + 5] & 0xff) != 0x2a) {
            return false;
        }
        int width = readUInt16LittleEndian(content, payloadOffset + 6) & 0x3fff;
        int height = readUInt16LittleEndian(content, payloadOffset + 8) & 0x3fff;
        return dimensionsWithinLimit(width, height, maxImagePixels);
    }

    private static boolean isValidVp8lFrame(byte[] content, int payloadOffset, long chunkSize, long maxImagePixels) {
        if (chunkSize < 5 || (content[payloadOffset] & 0xff) != 0x2f
                || (content[payloadOffset + 4] & 0xe0) != 0) {
            return false;
        }
        int width = 1 + (content[payloadOffset + 1] & 0xff)
                + ((content[payloadOffset + 2] & 0x3f) << 8);
        int height = 1 + ((content[payloadOffset + 2] & 0xc0) >>> 6)
                + ((content[payloadOffset + 3] & 0xff) << 2)
                + ((content[payloadOffset + 4] & 0x0f) << 10);
        return dimensionsWithinLimit(width, height, maxImagePixels);
    }

    private static boolean isValidVp8xHeader(byte[] content, int payloadOffset, long chunkSize,
                                               long maxImagePixels) {
        if (chunkSize < 10) {
            return false;
        }
        int width = 1 + readUInt24LittleEndian(content, payloadOffset + 4);
        int height = 1 + readUInt24LittleEndian(content, payloadOffset + 7);
        return dimensionsWithinLimit(width, height, maxImagePixels);
    }

    private static boolean dimensionsWithinLimit(int width, int height, long maxImagePixels) {
        return width > 0 && height > 0 && maxImagePixels > 0 && (long) width * height <= maxImagePixels;
    }

    private static long readUInt32LittleEndian(byte[] content, int offset) {
        return (content[offset] & 0xffL)
                | ((content[offset + 1] & 0xffL) << 8)
                | ((content[offset + 2] & 0xffL) << 16)
                | ((content[offset + 3] & 0xffL) << 24);
    }

    private static int readUInt16LittleEndian(byte[] content, int offset) {
        return (content[offset] & 0xff) | ((content[offset + 1] & 0xff) << 8);
    }

    private static int readUInt24LittleEndian(byte[] content, int offset) {
        return (content[offset] & 0xff)
                | ((content[offset + 1] & 0xff) << 8)
                | ((content[offset + 2] & 0xff) << 16);
    }

    private static BusinessException fileNotFound() {
        return new BusinessException("404", "文件不存在");
    }

    private static final class StoredFileResource extends AbstractResource {
        private final FileObjectStore fileObjectStore;
        private final String key;
        private final String filename;
        private final long size;

        private StoredFileResource(FileObjectStore fileObjectStore, String key, String filename, long size) {
            this.fileObjectStore = fileObjectStore;
            this.key = key;
            this.filename = filename;
            this.size = size;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return fileObjectStore.open(key);
        }

        @Override
        public String getFilename() {
            return filename;
        }

        @Override
        public long contentLength() {
            return size;
        }

        @Override
        public String getDescription() {
            return "stored object " + key;
        }
    }

    private final FileUploadMapper fileUploadMapper;
    private final FileObjectStore fileObjectStore;
}
