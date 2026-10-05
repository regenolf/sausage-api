--liquibase formatted sql

--changeset rowert:016-create-table-user-block
--comment: Nutzer blockieren: Beiträge blockierter Nutzer werden für den Blockierenden ausgeblendet (App Store 1.2)
CREATE TABLE user_block (
    blocker_id BIGINT NOT NULL,
    blocked_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_user_block PRIMARY KEY (blocker_id, blocked_id),
    CONSTRAINT fk_user_block_blocker FOREIGN KEY (blocker_id) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_block_blocked FOREIGN KEY (blocked_id) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT chk_user_block_self CHECK (blocker_id <> blocked_id)
);
--rollback DROP TABLE user_block;
