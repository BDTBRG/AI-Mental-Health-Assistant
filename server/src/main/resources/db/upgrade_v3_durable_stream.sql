-- Apply after upgrade_v2_review_consent.sql. Run once per database.
CREATE TABLE IF NOT EXISTS `consultation_chat_turn` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `session_id` BIGINT NOT NULL,
    `client_request_id` VARCHAR(64) NOT NULL,
    `request_hash` CHAR(64) NOT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_chat_turn_request` (`session_id`, `client_request_id`),
    KEY `idx_chat_turn_status_updated` (`status`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `consultation_stream_event` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `session_id` BIGINT NOT NULL,
    `client_request_id` VARCHAR(64) NOT NULL,
    `sequence` INT NOT NULL,
    `event_type` VARCHAR(20) NOT NULL,
    `payload` TEXT NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_stream_event_sequence` (`session_id`, `client_request_id`, `sequence`),
    KEY `idx_stream_event_replay` (`session_id`, `client_request_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
