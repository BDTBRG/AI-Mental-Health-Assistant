-- Apply after upgrade_v6_user_memory.sql. Prevent stale generation workers from
-- appending events after a recovered attempt has taken ownership of the turn.
ALTER TABLE `consultation_chat_turn`
    ADD COLUMN `generation_token` CHAR(36) NULL AFTER `status`;

UPDATE `consultation_chat_turn`
SET `generation_token` = UUID()
WHERE `generation_token` IS NULL;

ALTER TABLE `consultation_chat_turn`
    MODIFY COLUMN `generation_token` CHAR(36) NOT NULL;
