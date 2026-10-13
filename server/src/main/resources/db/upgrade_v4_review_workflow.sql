-- Apply after upgrade_v2_review_consent.sql and upgrade_v3_durable_stream.sql.
ALTER TABLE `consultation_session`
    ADD COLUMN `review_status` VARCHAR(20) NOT NULL DEFAULT 'NONE' AFTER `review_consent_updated_at`,
    ADD COLUMN `review_assignee_id` BIGINT DEFAULT NULL AFTER `review_status`,
    ADD COLUMN `review_version` INT NOT NULL DEFAULT 0 AFTER `review_assignee_id`,
    ADD COLUMN `review_requested_at` DATETIME DEFAULT NULL AFTER `review_version`,
    ADD COLUMN `review_updated_at` DATETIME DEFAULT NULL AFTER `review_requested_at`;

ALTER TABLE `consultation_review_audit`
    ADD COLUMN `from_status` VARCHAR(20) DEFAULT NULL AFTER `action`,
    ADD COLUMN `to_status` VARCHAR(20) DEFAULT NULL AFTER `from_status`;
