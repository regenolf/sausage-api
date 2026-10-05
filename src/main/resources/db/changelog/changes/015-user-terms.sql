--liquibase formatted sql

--changeset rowert:015-add-app-user-terms
--comment: Zustimmung zu den Nutzungsbedingungen (inkl. Bestätigung des Mindestalters) mit Version und Zeitpunkt
ALTER TABLE app_user ADD COLUMN terms_version VARCHAR(20);
ALTER TABLE app_user ADD COLUMN terms_accepted_at TIMESTAMP;
--rollback ALTER TABLE app_user DROP COLUMN terms_accepted_at;
--rollback ALTER TABLE app_user DROP COLUMN terms_version;
