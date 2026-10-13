CREATE TABLE IF NOT EXISTS `user` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `username` VARCHAR(50) NOT NULL,
    `password` VARCHAR(255) NOT NULL,
    `email` VARCHAR(100) DEFAULT NULL,
    `nickname` VARCHAR(50) DEFAULT NULL,
    `phone` VARCHAR(20) DEFAULT NULL,
    `gender` TINYINT DEFAULT 0,
    `user_type` TINYINT NOT NULL DEFAULT 1,
    `status` TINYINT NOT NULL DEFAULT 1,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
);

CREATE TABLE IF NOT EXISTS `knowledge_category` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `category_name` VARCHAR(100) NOT NULL,
    `description` VARCHAR(500) DEFAULT NULL,
    `sort_order` INT DEFAULT 0,
    `status` TINYINT NOT NULL DEFAULT 1,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `knowledge_article` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `category_id` BIGINT NOT NULL,
    `title` VARCHAR(200) NOT NULL,
    `content` LONGTEXT,
    `summary` VARCHAR(500) DEFAULT NULL,
    `cover_image` VARCHAR(500) DEFAULT NULL,
    `tags` VARCHAR(500) DEFAULT NULL,
    `author_id` BIGINT DEFAULT NULL,
    `author_name` VARCHAR(50) DEFAULT NULL,
    `read_count` INT DEFAULT 0,
    `status` TINYINT NOT NULL DEFAULT 0,
    `is_favorite` TINYINT DEFAULT 0,
    `favorite_count` INT DEFAULT 0,
    `published_at` DATETIME DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `file_upload` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `file_name` VARCHAR(255) NOT NULL,
    `file_path` VARCHAR(500) NOT NULL,
    `file_size` BIGINT DEFAULT 0,
    `business_type` VARCHAR(50) NOT NULL,
    `business_id` VARCHAR(100) DEFAULT NULL,
    `business_field` VARCHAR(50) DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `consultation_session` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `session_title` VARCHAR(200) DEFAULT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    `started_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `ended_at` DATETIME DEFAULT NULL,
    `duration_minutes` INT DEFAULT 0,
    `message_count` INT DEFAULT 0,
    `last_message_content` VARCHAR(1000) DEFAULT NULL,
    `last_message_time` DATETIME DEFAULT NULL,
    `review_consent` TINYINT NOT NULL DEFAULT 0,
    `review_consent_updated_at` DATETIME DEFAULT NULL,
    `review_status` VARCHAR(20) NOT NULL DEFAULT 'NONE',
    `review_assignee_id` BIGINT DEFAULT NULL,
    `review_version` INT NOT NULL DEFAULT 0,
    `review_requested_at` DATETIME DEFAULT NULL,
    `review_updated_at` DATETIME DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `consultation_message` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `session_id` BIGINT NOT NULL,
    `sender_type` TINYINT NOT NULL,
    `message_type` TINYINT DEFAULT 0,
    `content` TEXT,
    `is_error` TINYINT DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `consultation_chat_turn` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `session_id` BIGINT NOT NULL,
    `client_request_id` VARCHAR(64) NOT NULL,
    `request_hash` CHAR(64) NOT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    `generation_token` CHAR(36) NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_chat_turn_request` UNIQUE (`session_id`, `client_request_id`)
);

CREATE TABLE IF NOT EXISTS `consultation_stream_event` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `session_id` BIGINT NOT NULL,
    `client_request_id` VARCHAR(64) NOT NULL,
    `sequence` INT NOT NULL,
    `event_type` VARCHAR(20) NOT NULL,
    `payload` TEXT NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_stream_event_sequence` UNIQUE (`session_id`, `client_request_id`, `sequence`)
);

CREATE TABLE IF NOT EXISTS `consultation_review_audit` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `session_id` BIGINT NOT NULL,
    `actor_user_id` BIGINT NOT NULL,
    `action` VARCHAR(40) NOT NULL,
    `from_status` VARCHAR(20) DEFAULT NULL,
    `to_status` VARCHAR(20) DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `emotion_diary` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `diary_date` DATE NOT NULL,
    `mood_score` INT NOT NULL,
    `dominant_emotion` VARCHAR(50) NOT NULL,
    `emotion_triggers` VARCHAR(1000) DEFAULT NULL,
    `diary_content` TEXT,
    `sleep_quality` INT DEFAULT NULL,
    `stress_level` INT DEFAULT NULL,
    `ai_emotion_analysis` TEXT,
    `ai_analysis_updated_at` DATETIME DEFAULT NULL,
    `has_ai_emotion_analysis` TINYINT DEFAULT 0,
    `ai_analysis_status` VARCHAR(20) DEFAULT 'PENDING',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

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
    CONSTRAINT `uk_emotion_outbox_diary` UNIQUE (`diary_id`)
);

CREATE TABLE IF NOT EXISTS `agent_memory_settings` (
    `user_id` BIGINT NOT NULL,
    `personalization_enabled` TINYINT NOT NULL DEFAULT 0,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`user_id`)
);

CREATE TABLE IF NOT EXISTS `agent_memory` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `content` VARCHAR(1000) NOT NULL,
    `source_type` VARCHAR(30) NOT NULL DEFAULT 'USER_ENTERED',
    `expires_at` DATE DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `knowledge_index_outbox` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `article_id` BIGINT NOT NULL,
    `desired_version` BIGINT NOT NULL DEFAULT 1,
    `processing_version` BIGINT DEFAULT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    `attempts` INT NOT NULL DEFAULT 0,
    `next_attempt_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `lease_until` DATETIME DEFAULT NULL,
    `lease_token` VARCHAR(36) DEFAULT NULL,
    `last_error` VARCHAR(64) DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_knowledge_index_article` UNIQUE (`article_id`)
);

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
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_agent_purge_session` UNIQUE (`session_id`)
);

CREATE TABLE IF NOT EXISTS `emotion_diary_access_audit` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `diary_id` BIGINT NOT NULL,
    `actor_user_id` BIGINT NOT NULL,
    `action` VARCHAR(30) NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);
