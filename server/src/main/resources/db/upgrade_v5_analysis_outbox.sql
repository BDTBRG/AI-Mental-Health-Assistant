CREATE TABLE IF NOT EXISTS `emotion_analysis_outbox` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `diary_id` BIGINT NOT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    `attempts` INT NOT NULL DEFAULT 0,
    `next_attempt_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `lease_until` DATETIME DEFAULT NULL,
    `lease_token` VARCHAR(36) DEFAULT NULL,
    `last_error` VARCHAR(64) DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_emotion_outbox_diary` (`diary_id`),
    KEY `idx_emotion_outbox_ready` (`status`, `next_attempt_at`, `lease_until`)
);

INSERT IGNORE INTO `emotion_analysis_outbox` (`diary_id`, `status`, `attempts`, `next_attempt_at`)
SELECT `id`, 'PENDING', 0, CURRENT_TIMESTAMP
FROM `emotion_diary`
WHERE `ai_analysis_status` = 'PENDING';
