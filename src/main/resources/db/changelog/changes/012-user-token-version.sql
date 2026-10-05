--liquibase formatted sql

--changeset rowert:012-add-app-user-token-version
--comment: Version der ausgestellten Tokens; Erhöhen (Passwortänderung, "überall abmelden") macht alle alten JWTs ungültig
ALTER TABLE app_user ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0;
--rollback ALTER TABLE app_user DROP COLUMN token_version;
