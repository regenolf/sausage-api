--liquibase formatted sql

--changeset rowert:013-app-user-email-unique-lower
--comment: E-Mail eindeutig unabhängig von Groß-/Kleinschreibung (Login per E-Mail ist case-insensitive)
CREATE UNIQUE INDEX uk_app_user_email_lower ON app_user (lower(email));
--rollback DROP INDEX uk_app_user_email_lower;

--changeset rowert:013-app-user-username-unique-lower
--comment: Verwechselbare Namen wie "Max" und "max" verhindern
CREATE UNIQUE INDEX uk_app_user_username_lower ON app_user (lower(username));
--rollback DROP INDEX uk_app_user_username_lower;
