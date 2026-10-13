-- Apply after upgrade_v7_turn_generation_fencing.sql. Keeps published knowledge
-- article changes durable until the Agent index catches up.
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
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_knowledge_index_article` (`article_id`),
    KEY `idx_knowledge_index_ready` (`status`, `next_attempt_at`, `lease_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO `knowledge_index_outbox` (`article_id`, `desired_version`, `status`, `attempts`, `next_attempt_at`)
SELECT `id`, 1, 'PENDING', 0, CURRENT_TIMESTAMP
FROM `knowledge_article`
WHERE `status` = 1;
