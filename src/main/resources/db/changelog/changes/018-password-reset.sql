--liquibase formatted sql

--changeset rowert:018-create-table-password-reset-token
--comment: „Passwort vergessen“: nur der SHA-256-Hash des Tokens wird gespeichert, einmal verwendbar, kurz gültig
CREATE TABLE password_reset_token (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_password_reset_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT uk_password_reset_token UNIQUE (token_hash)
);
CREATE INDEX idx_password_reset_user ON password_reset_token(user_id);
--rollback DROP TABLE password_reset_token;
