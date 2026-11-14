--liquibase formatted sql

--changeset rowert:025-create-table-check-in
--comment: Wurst-Tagebuch – „Hier gegessen“, höchstens einmal pro Bude und Tag; wird mit dem Konto gelöscht
CREATE TABLE check_in (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    spot_id BIGINT NOT NULL,
    eaten_on DATE NOT NULL DEFAULT CURRENT_DATE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_check_in_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_check_in_spot FOREIGN KEY (spot_id) REFERENCES spot(id) ON DELETE CASCADE,
    CONSTRAINT uk_check_in_per_day UNIQUE (user_id, spot_id, eaten_on)
);
CREATE INDEX idx_check_in_user ON check_in(user_id, created_at DESC);
CREATE INDEX idx_check_in_spot ON check_in(spot_id);
--rollback DROP TABLE check_in;
