-- Apply after upgrade_v10_agent_checkpoint_purge.sql.
-- Before applying to an existing database, make a backup and confirm the selected schema.
-- This migration adds nullable columns and a unique key; legacy sessions keep NULL keys.
-- The conditional DDL is safe to re-run against MySQL 8.x databases.

SET @amha_schema = DATABASE();

SET @amha_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = @amha_schema
       AND table_name = 'consultation_session'
       AND column_name = 'start_request_id') = 0,
    'ALTER TABLE `consultation_session` ADD COLUMN `start_request_id` VARCHAR(64) DEFAULT NULL',
    'SELECT 1'
);
PREPARE amha_stmt FROM @amha_ddl;
EXECUTE amha_stmt;
DEALLOCATE PREPARE amha_stmt;

SET @amha_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = @amha_schema
       AND table_name = 'consultation_session'
       AND column_name = 'start_request_hash') = 0,
    'ALTER TABLE `consultation_session` ADD COLUMN `start_request_hash` CHAR(64) DEFAULT NULL',
    'SELECT 1'
);
PREPARE amha_stmt FROM @amha_ddl;
EXECUTE amha_stmt;
DEALLOCATE PREPARE amha_stmt;

SET @amha_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = @amha_schema
       AND table_name = 'consultation_session'
       AND index_name = 'uk_session_start_request') = 0,
    'ALTER TABLE `consultation_session` ADD UNIQUE KEY `uk_session_start_request` (`user_id`, `start_request_id`)',
    'SELECT 1'
);
PREPARE amha_stmt FROM @amha_ddl;
EXECUTE amha_stmt;
DEALLOCATE PREPARE amha_stmt;
