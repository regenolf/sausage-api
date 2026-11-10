--liquibase formatted sql

--changeset rowert:021-app-user-external-id
--comment: Anmeldung über Keycloak: Accounts sind über die Keycloak-User-ID (sub) verknüpft. Alte Accounts bleiben
--comment: unverknüpft (NULL), bis sich jemand mit derselben, in Keycloak bestätigten E-Mail anmeldet.
ALTER TABLE app_user ADD COLUMN external_id VARCHAR(64);
ALTER TABLE app_user ADD CONSTRAINT uk_app_user_external_id UNIQUE (external_id);
--rollback ALTER TABLE app_user DROP COLUMN external_id;

--changeset rowert:021-create-table-deleted-account
--comment: Gerade gelöschte Keycloak-Konten: ihre noch kurz gültigen Tokens dürfen keinen neuen Account anlegen
CREATE TABLE deleted_account (
    external_id VARCHAR(64) PRIMARY KEY,
    deleted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
--rollback DROP TABLE deleted_account;

--changeset rowert:021-drop-local-passwords
--comment: Passwörter, Token-Versionen und Reset-Links verwaltet jetzt Keycloak
DROP TABLE password_reset_token;
ALTER TABLE app_user DROP COLUMN password_hash;
ALTER TABLE app_user DROP COLUMN token_version;
--rollback ALTER TABLE app_user ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0;
--rollback ALTER TABLE app_user ADD COLUMN password_hash VARCHAR(255);
