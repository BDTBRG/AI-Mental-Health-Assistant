-- Apply once to an existing AMHA database. New databases get the same fields from init.sql.
ALTER TABLE `consultation_session`
    ADD COLUMN `review_consent` TINYINT NOT NULL DEFAULT 0,
    ADD COLUMN `review_consent_updated_at` DATETIME DEFAULT NULL;

CREATE TABLE IF NOT EXISTS `consultation_review_audit` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `session_id` BIGINT NOT NULL,
    `actor_user_id` BIGINT NOT NULL,
    `action` VARCHAR(40) NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_review_audit_session` (`session_id`),
    KEY `idx_review_audit_actor` (`actor_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
