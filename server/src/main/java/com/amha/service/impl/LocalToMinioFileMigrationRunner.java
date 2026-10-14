package com.amha.service.impl;

import com.amha.mapper.FileUploadMapper;
import com.amha.storage.LocalFileObjectStore;
import com.amha.storage.MinioFileObjectStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "file.upload.migration", name = "enabled", havingValue = "true")
public class LocalToMinioFileMigrationRunner implements ApplicationRunner {

    private final FileUploadMapper fileUploadMapper;
    private final LocalFileObjectStore localFileObjectStore;
    private final MinioFileObjectStore minioFileObjectStore;

    @Value("${file.upload.migration.dry-run:true}")
    private boolean dryRun;

    @Value("${file.upload.migration.batch-size:100}")
    private int batchSize;

    @Override
    public void run(ApplicationArguments args) {
        minioFileObjectStore.verifyBucketAvailable();
        var report = new LocalToMinioFileMigrationService(
                fileUploadMapper, localFileObjectStore, minioFileObjectStore, batchSize
        ).migrate(dryRun);

        log.info("Local-to-MinIO file migration finished: dryRun={}, scanned={}, copied={}, wouldCopy={}, "
                        + "alreadyVerified={}, failures={}, failureDetails={}",
                dryRun, report.scanned(), report.copied(), report.wouldCopy(),
                report.alreadyVerified(), report.failureCount(), report.failures());
        if (report.failureCount() > 0) {
            throw new IllegalStateException("File migration has failures; keep local storage active and review the report");
        }
    }
}
