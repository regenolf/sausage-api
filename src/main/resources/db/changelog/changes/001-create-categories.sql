--liquibase formatted sql

--changeset rowert:001-create-table-category
--comment: Kategorien für Orte (z.B. Bratwurstbude, Kneipe) - macht die App modular erweiterbar
CREATE TABLE category (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(50) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_category_code UNIQUE (code)
);
--rollback DROP TABLE category;
