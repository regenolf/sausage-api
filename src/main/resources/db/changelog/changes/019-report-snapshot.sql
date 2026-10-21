--liquibase formatted sql

--changeset rowert:019-report-content-snapshot
--comment: DSA Art. 17: Verfasser und Inhalt beim Melden sichern, damit Entscheidung und Begründung auch nach dem Entfernen nachvollziehbar sind
ALTER TABLE report ADD COLUMN target_author_id BIGINT;
ALTER TABLE report ADD COLUMN target_spot_id BIGINT;
ALTER TABLE report ADD COLUMN content_snapshot VARCHAR(2000);
ALTER TABLE report ADD CONSTRAINT fk_report_target_author FOREIGN KEY (target_author_id) REFERENCES app_user(id) ON DELETE SET NULL;
CREATE INDEX idx_report_target_author ON report(target_author_id);
--rollback DROP INDEX idx_report_target_author; ALTER TABLE report DROP CONSTRAINT fk_report_target_author; ALTER TABLE report DROP COLUMN content_snapshot; ALTER TABLE report DROP COLUMN target_spot_id; ALTER TABLE report DROP COLUMN target_author_id;
