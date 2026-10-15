-- Apply after upgrade_v12_chat_turn_generation_epoch.sql.
-- Adds rolling chat-context summaries and durable summary jobs only.
-- This migration does not alter messages or Agent checkpoint storage.
CREATE TABLE IF NOT EXISTS `consultation_context_summary` (
    `session_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `summary_json` JSON NOT NULL,
    `covered_through_message_id` BIGINT NOT NULL,
    `summary_version` BIGINT NOT NULL,
    `prompt_version` VARCHAR(64) NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`session_id`),
    KEY `idx_context_summary_user` (`user_id`, `session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `consultation_context_summary_outbox` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `session_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `desired_through_message_id` BIGINT NOT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    `attempt_count` INT NOT NULL DEFAULT 0,
    `next_attempt_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `lease_token` CHAR(36) DEFAULT NULL,
    `lease_until` DATETIME DEFAULT NULL,
    `last_error_code` VARCHAR(64) DEFAULT NULL,
    `manual_retry_count` INT NOT NULL DEFAULT 0,
    `last_manual_retry_by` BIGINT DEFAULT NULL,
    `last_manual_retry_at` DATETIME DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_context_summary_outbox_session` (`session_id`),
    KEY `idx_context_summary_outbox_ready` (`status`, `next_attempt_at`, `lease_until`),
    KEY `idx_context_summary_outbox_user` (`user_id`, `session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
