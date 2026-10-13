-- Apply after upgrade_v8_knowledge_index_outbox.sql. Audit administrative reads
-- and deletion of sensitive emotion diary records without storing diary content.
CREATE TABLE IF NOT EXISTS `emotion_diary_access_audit` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `diary_id` BIGINT NOT NULL,
    `actor_user_id` BIGINT NOT NULL,
    `action` VARCHAR(30) NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_diary_audit_subject` (`diary_id`, `created_at`),
    KEY `idx_diary_audit_actor` (`actor_user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
