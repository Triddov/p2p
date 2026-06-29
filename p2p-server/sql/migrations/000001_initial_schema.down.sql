-- Rollback of the initial schema. Drops everything 000001 created.

DROP TRIGGER IF EXISTS trigger_signaling_disconnected ON signaling_sessions;
DROP TRIGGER IF EXISTS trigger_signaling_connected ON signaling_sessions;

DROP FUNCTION IF EXISTS archive_old_audit_logs();
DROP FUNCTION IF EXISTS cleanup_old_delivered_messages();
DROP FUNCTION IF EXISTS mark_user_offline();
DROP FUNCTION IF EXISTS update_user_last_seen();

DROP TABLE IF EXISTS audit_log;
DROP TABLE IF EXISTS signaling_sessions;
DROP TABLE IF EXISTS delivery_receipts;
DROP TABLE IF EXISTS pending_messages;
DROP TABLE IF EXISTS device_tokens;
DROP TABLE IF EXISTS one_time_prekeys;
DROP TABLE IF EXISTS signed_prekeys;
DROP TABLE IF EXISTS users;

DROP EXTENSION IF EXISTS "uuid-ossp";
