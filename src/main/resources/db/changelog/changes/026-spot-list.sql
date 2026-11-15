--liquibase formatted sql

--changeset rowert:026-create-table-spot-list
--comment: Eigene Listen („Meine Top 5 in Erfurt“), privat oder per Link teilbar; werden mit dem Konto gelöscht
CREATE TABLE spot_list (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    is_public BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_spot_list_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
);
CREATE INDEX idx_spot_list_user ON spot_list(user_id, updated_at DESC);
--rollback DROP TABLE spot_list;

--changeset rowert:026-create-table-spot-list-item
CREATE TABLE spot_list_item (
    list_id BIGINT NOT NULL,
    spot_id BIGINT NOT NULL,
    added_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_spot_list_item PRIMARY KEY (list_id, spot_id),
    CONSTRAINT fk_spot_list_item_list FOREIGN KEY (list_id) REFERENCES spot_list(id) ON DELETE CASCADE,
    CONSTRAINT fk_spot_list_item_spot FOREIGN KEY (spot_id) REFERENCES spot(id) ON DELETE CASCADE
);
CREATE INDEX idx_spot_list_item_spot ON spot_list_item(spot_id);
--rollback DROP TABLE spot_list_item;

--changeset rowert:026-report-target-list
--comment: Öffentliche Listen enthalten Text von Nutzern und sind daher meldbar
ALTER TABLE report DROP CONSTRAINT chk_report_target_type;
ALTER TABLE report ADD CONSTRAINT chk_report_target_type CHECK (target_type IN ('SPOT', 'RATING', 'COMMENT', 'PHOTO', 'LIST'));
--rollback ALTER TABLE report DROP CONSTRAINT chk_report_target_type;
--rollback ALTER TABLE report ADD CONSTRAINT chk_report_target_type CHECK (target_type IN ('SPOT', 'RATING', 'COMMENT', 'PHOTO'));
