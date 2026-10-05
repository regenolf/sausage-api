--liquibase formatted sql

--changeset rowert:008-add-app-user-role
--comment: Rolle für Berechtigungen (USER oder ADMIN); Admins moderieren Spots/Kommentare und verwalten Kategorien
ALTER TABLE app_user ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER';
ALTER TABLE app_user ADD CONSTRAINT chk_app_user_role CHECK (role IN ('USER', 'ADMIN'));
--rollback ALTER TABLE app_user DROP COLUMN role;
