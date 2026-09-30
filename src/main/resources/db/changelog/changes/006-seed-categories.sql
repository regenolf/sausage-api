--liquibase formatted sql

--changeset rowert:006-seed-category-bratwurst
INSERT INTO category (code, name, description)
VALUES ('BRATWURST', 'Bratwurstbude', 'Stände und Buden, die Bratwurst verkaufen');
--rollback DELETE FROM category WHERE code = 'BRATWURST';
