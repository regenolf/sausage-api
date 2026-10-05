--liquibase formatted sql

--changeset rowert:009-create-table-spot-photo
--comment: Fotos zu Spots, direkt in der Datenbank gespeichert (max. 5 MB je Bild)
CREATE TABLE spot_photo (
    id BIGSERIAL PRIMARY KEY,
    spot_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    content_type VARCHAR(50) NOT NULL,
    size_bytes INTEGER NOT NULL,
    data BYTEA NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_spot_photo_spot FOREIGN KEY (spot_id) REFERENCES spot(id) ON DELETE CASCADE,
    CONSTRAINT fk_spot_photo_user FOREIGN KEY (user_id) REFERENCES app_user(id)
);
--rollback DROP TABLE spot_photo;

--changeset rowert:009-create-index-spot-photo-spot
CREATE INDEX idx_spot_photo_spot ON spot_photo(spot_id);
--rollback DROP INDEX idx_spot_photo_spot;
