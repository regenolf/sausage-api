--liquibase formatted sql

--changeset rowert:027-create-table-push-device
--comment: Push-Token (Firebase Cloud Messaging) der Geräte, auf denen Benachrichtigungen erlaubt wurden; ein Token gehört immer zum zuletzt angemeldeten Konto
CREATE TABLE push_device (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token VARCHAR(4096) NOT NULL,
    platform VARCHAR(10) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_push_device_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT uk_push_device_token UNIQUE (token),
    CONSTRAINT chk_push_device_platform CHECK (platform IN ('ANDROID', 'IOS'))
);
CREATE INDEX idx_push_device_user ON push_device(user_id);
--rollback DROP TABLE push_device;
