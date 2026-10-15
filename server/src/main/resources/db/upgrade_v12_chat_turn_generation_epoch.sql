-- Apply after upgrade_v11_idempotent_session_start.sql. Run once per database.
-- Existing turns start at epoch 0; a reclaimed execution increments to epoch 1.
ALTER TABLE `consultation_chat_turn`
    ADD COLUMN `generation_epoch` BIGINT NOT NULL DEFAULT 0 AFTER `generation_token`;
