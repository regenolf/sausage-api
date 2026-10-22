--liquibase formatted sql

--changeset rowert:017-foreign-key-indexes
--comment: Indizes auf Fremdschlüssel: "meine Spots", Export, Konto löschen (CASCADE/SET NULL) und Moderation ohne Seq-Scan
CREATE INDEX idx_spot_created_by ON spot(created_by);
CREATE INDEX idx_rating_user ON rating(user_id);
CREATE INDEX idx_comment_user ON comment(user_id);
CREATE INDEX idx_spot_photo_user ON spot_photo(user_id);
CREATE INDEX idx_report_reporter ON report(reporter_id);
CREATE INDEX idx_report_target ON report(target_type, target_id);
CREATE INDEX idx_user_block_blocked ON user_block(blocked_id);
--rollback DROP INDEX idx_spot_created_by; DROP INDEX idx_rating_user; DROP INDEX idx_comment_user; DROP INDEX idx_spot_photo_user; DROP INDEX idx_report_reporter; DROP INDEX idx_report_target; DROP INDEX idx_user_block_blocked;
