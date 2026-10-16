-- Apply after upgrade_v13_chat_context_summary.sql.
-- Links a chat turn to the exact persisted user message so an owner can safely
-- recover a turn after refresh. Existing rows remain NULL and are not guessed.
SET @amha_v14_user_message_column_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'consultation_chat_turn'
      AND column_name = 'user_message_id'
);
SET @amha_v14_add_user_message_column = IF(
    @amha_v14_user_message_column_exists = 0,
    'ALTER TABLE `consultation_chat_turn` ADD COLUMN `user_message_id` BIGINT NULL AFTER `client_request_id`',
    'SELECT 1'
);
PREPARE amha_v14_statement FROM @amha_v14_add_user_message_column;
EXECUTE amha_v14_statement;
DEALLOCATE PREPARE amha_v14_statement;
