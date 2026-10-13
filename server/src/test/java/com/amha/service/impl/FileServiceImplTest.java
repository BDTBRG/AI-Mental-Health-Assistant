package com.amha.service.impl;

import com.amha.common.BusinessException;
import com.amha.entity.FileUpload;
import com.amha.mapper.FileUploadMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FileServiceImplTest {

    @TempDir
    Path uploadDirectory;

    private FileUploadMapper fileUploadMapper;
    private FileServiceImpl fileService;

    @BeforeEach
    void setUp() {
        fileUploadMapper = mock(FileUploadMapper.class);
        fileService = new FileServiceImpl(fileUploadMapper);
        ReflectionTestUtils.setField(fileService, "uploadPath", uploadDirectory.toString());
        ReflectionTestUtils.setField(fileService, "allowedExtensions", "png,jpg,jpeg,gif,webp");
        ReflectionTestUtils.setField(fileService, "maxFileSizeBytes", 5L * 1024 * 1024);
    }

    @Test
    void rejectsPayloadThatOnlyClaimsToBePng() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "cover.png", "image/png", "not an image".getBytes()
        );

        assertThrows(BusinessException.class,
                () -> fileService.uploadFile(file, "ARTICLE", "draft-id", "cover"));
        verify(fileUploadMapper, never()).insert(any(FileUpload.class));
    }

    @Test
    void rejectsTruncatedPngThatHasAValidSignature() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "cover.png", "image/png",
                new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}
        );

        assertThrows(BusinessException.class,
                () -> fileService.uploadFile(file, "ARTICLE", "draft-id", "cover"));
        verify(fileUploadMapper, never()).insert(any(FileUpload.class));
    }

    @Test
    void rejectsWebpHeaderWithoutAnImageChunk() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "cover.webp", "image/webp",
                new byte[]{'R', 'I', 'F', 'F', 4, 0, 0, 0, 'W', 'E', 'B', 'P'}
        );

        assertThrows(BusinessException.class,
                () -> fileService.uploadFile(file, "ARTICLE", "draft-id", "cover"));
        verify(fileUploadMapper, never()).insert(any(FileUpload.class));
    }

    @Test
    void rejectsWebpContainerWithOnlyAnExtendedHeader() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "cover.webp", "image/webp",
                new byte[]{
                        'R', 'I', 'F', 'F', 22, 0, 0, 0, 'W', 'E', 'B', 'P',
                        'V', 'P', '8', 'X', 10, 0, 0, 0,
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0
                }
        );

        assertThrows(BusinessException.class,
                () -> fileService.uploadFile(file, "ARTICLE", "draft-id", "cover"));
        verify(fileUploadMapper, never()).insert(any(FileUpload.class));
    }

    @Test
    void rejectsWebpCanvasAboveConfiguredPixelLimit() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "cover.webp", "image/webp",
                new byte[]{
                        'R', 'I', 'F', 'F', 36, 0, 0, 0, 'W', 'E', 'B', 'P',
                        'V', 'P', '8', 'X', 10, 0, 0, 0,
                        0, 0, 0, 0, (byte) 0x88, 0x13, 0, (byte) 0x87, 0x13, 0,
                        'V', 'P', '8', 'L', 5, 0, 0, 0, 0x2f, 0, 0, 0, 0, 0
                }
        );

        assertThrows(BusinessException.class,
                () -> fileService.uploadFile(file, "ARTICLE", "draft-id", "cover"));
        verify(fileUploadMapper, never()).insert(any(FileUpload.class));
    }

    @Test
    void rejectsImageAboveConfiguredSizeLimit() {
        ReflectionTestUtils.setField(fileService, "maxFileSizeBytes", 32L);
        MockMultipartFile file = new MockMultipartFile(
                "file", "cover.png", "image/png", validPng()
        );

        assertThrows(BusinessException.class,
                () -> fileService.uploadFile(file, "ARTICLE", "draft-id", "cover"));
        verify(fileUploadMapper, never()).insert(any(FileUpload.class));
    }

    @Test
    void rejectsTraversalBeforeLookingUpFileMetadata() {
        assertThrows(BusinessException.class, () -> fileService.getPublicImage("../secret.png"));
        verify(fileUploadMapper, never()).selectOne(any());
    }

    @Test
    void rejectsUploadsOutsideArticleCoverBusinessField() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "cover.png", "image/png", validPng()
        );

        assertThrows(BusinessException.class,
                () -> fileService.uploadFile(file, "USER_DOCUMENT", "7", "attachment"));
        verify(fileUploadMapper, never()).insert(any(FileUpload.class));
    }

    @Test
    void acceptsValidArticleCoverImage() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "cover.png", "application/octet-stream", validPng()
        );

        var result = fileService.uploadFile(file, "ARTICLE", "draft-id", "cover");

        assertTrue(result.get("filePath").matches("/file/uploads/\\d{4}/\\d{2}/\\d{2}/[0-9a-f-]+\\.png"));
        verify(fileUploadMapper).insert(any(FileUpload.class));
    }

    private static byte[] validPng() {
        return new byte[]{
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
                0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
                0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
                0x08, 0x02, 0x00, 0x00, 0x00, (byte) 0x90, 0x77, 0x53,
                (byte) 0xde, 0x00, 0x00, 0x00, 0x0b, 0x49, 0x44, 0x41,
                0x54, 0x78, (byte) 0x9c, 0x63, 0x60, 0x00, 0x02, 0x00,
                0x00, 0x05, 0x00, 0x01, (byte) 0xa5, (byte) 0xf6, 0x45,
                0x40, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4e, 0x44,
                (byte) 0xae, 0x42, 0x60, (byte) 0x82
        };
    }
}
