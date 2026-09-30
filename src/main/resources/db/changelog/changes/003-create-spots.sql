--liquibase formatted sql

--changeset rowert:003-create-table-spot
--comment: Orte/Buden - Kern-Entität der App, kategorie-agnostisch für spätere Erweiterung
CREATE TABLE spot (
    id BIGSERIAL PRIMARY KEY,
    category_id BIGINT NOT NULL,
    name VARCHAR(150) NOT NULL,
    description VARCHAR(2000),
    street VARCHAR(150),
    house_number VARCHAR(20),
    zip_code VARCHAR(10),
    city VARCHAR(100),
    latitude DECIMAL(9,6) NOT NULL,
    longitude DECIMAL(9,6) NOT NULL,
    opening_hours VARCHAR(500),
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_spot_category FOREIGN KEY (category_id) REFERENCES category(id),
    CONSTRAINT fk_spot_created_by FOREIGN KEY (created_by) REFERENCES app_user(id)
);
--rollback DROP TABLE spot;

--changeset rowert:003-create-index-spot-category
CREATE INDEX idx_spot_category ON spot(category_id);
--rollback DROP INDEX idx_spot_category;

--changeset rowert:003-create-index-spot-coordinates
--comment: Index für performante Umkreissuche über lat/lon
CREATE INDEX idx_spot_coordinates ON spot(latitude, longitude);
--rollback DROP INDEX idx_spot_coordinates;
