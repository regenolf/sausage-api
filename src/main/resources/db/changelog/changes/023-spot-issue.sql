--liquibase formatted sql

--changeset rowert:023-create-table-spot-issue
--comment: „Stimmt was nicht?“ – Hinweise auf veraltete Daten (geschlossen, falscher Ort, Öffnungszeiten). Kein Regelverstoß, daher getrennt von report (DSA). Hinweise bleiben nach der Kontolöschung anonym erhalten
CREATE TABLE spot_issue (
    id BIGSERIAL PRIMARY KEY,
    spot_id BIGINT NOT NULL,
    user_id BIGINT,
    type VARCHAR(30) NOT NULL,
    message VARCHAR(1000),
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    decided_by BIGINT,
    decided_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_spot_issue_spot FOREIGN KEY (spot_id) REFERENCES spot(id) ON DELETE CASCADE,
    CONSTRAINT fk_spot_issue_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_spot_issue_decided_by FOREIGN KEY (decided_by) REFERENCES app_user(id) ON DELETE SET NULL,
    CONSTRAINT chk_spot_issue_type CHECK (type IN ('CLOSED', 'WRONG_LOCATION', 'WRONG_HOURS', 'WRONG_DETAILS', 'OTHER')),
    CONSTRAINT chk_spot_issue_status CHECK (status IN ('OPEN', 'RESOLVED', 'REJECTED'))
);
CREATE INDEX idx_spot_issue_status ON spot_issue(status, created_at);
CREATE INDEX idx_spot_issue_spot ON spot_issue(spot_id);
CREATE INDEX idx_spot_issue_user ON spot_issue(user_id);
--rollback DROP TABLE spot_issue;
