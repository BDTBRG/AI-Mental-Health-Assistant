package com.amha.storage;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

public class MinioConfiguredCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var environment = context.getEnvironment();
        return StringUtils.hasText(environment.getProperty("file.upload.s3.endpoint"))
                && StringUtils.hasText(environment.getProperty("file.upload.s3.bucket"))
                && StringUtils.hasText(environment.getProperty("file.upload.s3.access-key"))
                && StringUtils.hasText(environment.getProperty("file.upload.s3.secret-key"));
    }
}
