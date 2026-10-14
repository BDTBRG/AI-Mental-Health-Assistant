package com.amha.controller;

import com.amha.common.GlobalExceptionHandler;
import com.amha.entity.FileUpload;
import com.amha.mapper.FileUploadMapper;
import com.amha.storage.FileObjectStat;
import com.amha.storage.FileObjectStore;
import com.amha.storage.FileStorageException;
import com.amha.storage.LocalFileObjectStore;
import com.amha.service.impl.FileServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FileControllerTest {

    @TempDir
    Path uploadDirectory;

    private FileUploadMapper fileUploadMapper;
    private MockMvc mockMvc;
    private String storedImagePath;

    @BeforeEach
    void setUp() throws Exception {
        fileUploadMapper = mock(FileUploadMapper.class);
        FileServiceImpl fileService = new FileServiceImpl(
                fileUploadMapper, new LocalFileObjectStore(uploadDirectory.toString()));
        ReflectionTestUtils.setField(fileService, "allowedExtensions", "png,jpg,jpeg,gif,webp");

        storedImagePath = "2026/10/13/cover.png";
        Path image = uploadDirectory.resolve(storedImagePath);
        Files.createDirectories(image.getParent());
        Files.write(image, new byte[]{
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
        });

        FileUpload metadata = new FileUpload();
        metadata.setFilePath(storedImagePath);
        metadata.setBusinessType("ARTICLE");
        metadata.setBusinessField("cover");
        when(fileUploadMapper.selectOne(any())).thenReturn(metadata);

        mockMvc = MockMvcBuilders.standaloneSetup(new FileController(fileService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void preservesPublicArticleImageUrlAndAddsSafeContentHeaders() throws Exception {
        mockMvc.perform(get("/file/uploads/" + storedImagePath))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void doesNotServeLegacyDocumentThroughPublicUploadUrl() throws Exception {
        mockMvc.perform(get("/file/uploads/2026/10/13/private.pdf"))
                .andExpect(status().isNotFound());
    }

    @Test
    void returnsHttpNotFoundWhenImageMetadataDoesNotExist() throws Exception {
        when(fileUploadMapper.selectOne(any())).thenReturn(null);

        mockMvc.perform(get("/file/uploads/2026/10/13/missing.png"))
                .andExpect(status().isNotFound());
    }

    @Test
    void returnsServiceUnavailableWhenSharedStorageCannotBeReached() throws Exception {
        FileObjectStore unavailableStore = new FileObjectStore() {
            @Override
            public void put(String key, InputStream content, long contentLength, String contentType) {
                throw new UnsupportedOperationException();
            }

            @Override
            public InputStream open(String key) throws IOException {
                throw new FileStorageException("storage unavailable", new IOException("connection refused"));
            }

            @Override
            public FileObjectStat stat(String key) {
                throw new FileStorageException("storage unavailable", new IOException("connection refused"));
            }

            @Override
            public void delete(String key) {
                throw new UnsupportedOperationException();
            }
        };
        FileServiceImpl failingService = new FileServiceImpl(fileUploadMapper, unavailableStore);
        ReflectionTestUtils.setField(failingService, "allowedExtensions", "png,jpg,jpeg,gif,webp");
        mockMvc = MockMvcBuilders.standaloneSetup(new FileController(failingService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/file/uploads/" + storedImagePath))
                .andExpect(status().isServiceUnavailable());
    }
}
