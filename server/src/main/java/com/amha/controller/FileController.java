package com.amha.controller;

import com.amha.common.Result;
import com.amha.common.BusinessException;
import com.amha.service.FileService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/file")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;

    @PostMapping("/upload")
    public Result<Map<String, String>> upload(@RequestParam("file") MultipartFile file,
                                              @RequestParam("businessType") String businessType,
                                              @RequestParam(value = "businessId", required = false) String businessId,
                                              @RequestParam(value = "businessField", required = false) String businessField) {
        return Result.success(fileService.uploadFile(file, businessType, businessId, businessField));
    }

    @GetMapping("/uploads/**")
    public ResponseEntity<Resource> getPublicArticleImage(HttpServletRequest request) {
        String requestPath = request.getRequestURI().substring(request.getContextPath().length());
        String prefix = "/file/uploads/";
        if (!requestPath.startsWith(prefix)) {
            return ResponseEntity.notFound().build();
        }

        Resource image;
        try {
            image = fileService.getPublicImage(requestPath.substring(prefix.length()));
        } catch (BusinessException e) {
            if ("404".equals(e.getCode())) {
                return ResponseEntity.notFound().build();
            }
            throw e;
        }
        MediaType mediaType = MediaTypeFactory.getMediaType(image)
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok()
                .contentType(mediaType)
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
                .header("X-Content-Type-Options", "nosniff")
                .body(image);
    }
}
