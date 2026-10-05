--liquibase formatted sql

--changeset rowert:014-create-table-report
--comment: Meldungen rechtswidriger/unangemessener Inhalte (Melde- und Abhilfeverfahren nach Art. 16 DSA)
CREATE TABLE report (
    id BIGSERIAL PRIMARY KEY,
    target_type VARCHAR(20) NOT NULL,
    target_id BIGINT NOT NULL,
    reason VARCHAR(30) NOT NULL,
    message VARCHAR(2000),
    reporter_id BIGINT,
    reporter_email VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    decision_note VARCHAR(2000),
    decided_by BIGINT,
    decided_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_report_target_type CHECK (target_type IN ('SPOT', 'RATING', 'COMMENT', 'PHOTO')),
    CONSTRAINT chk_report_reason CHECK (reason IN ('ILLEGAL', 'INSULT', 'SPAM', 'PRIVACY', 'COPYRIGHT', 'WRONG_INFO', 'OTHER')),
    CONSTRAINT chk_report_status CHECK (status IN ('OPEN', 'REMOVED', 'REJECTED')),
    CONSTRAINT fk_report_reporter FOREIGN KEY (reporter_id) REFERENCES app_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_report_decided_by FOREIGN KEY (decided_by) REFERENCES app_user(id) ON DELETE SET NULL
);
--rollback DROP TABLE report;

--changeset rowert:014-create-index-report-status
CREATE INDEX idx_report_status ON report(status, created_at);
--rollback DROP INDEX idx_report_status;
