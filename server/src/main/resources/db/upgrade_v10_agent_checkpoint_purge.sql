CREATE TABLE IF NOT EXISTS `agent_checkpoint_purge_outbox` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `session_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `desired_version` BIGINT NOT NULL DEFAULT 1,
    `processing_version` BIGINT DEFAULT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    `attempts` INT NOT NULL DEFAULT 0,
    `next_attempt_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `lease_until` DATETIME DEFAULT NULL,
    `lease_token` VARCHAR(36) DEFAULT NULL,
    `last_error` VARCHAR(64) DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_purge_session` (`session_id`),
    KEY `idx_agent_purge_ready` (`status`, `next_attempt_at`, `lease_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
