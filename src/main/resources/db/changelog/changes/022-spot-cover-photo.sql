--liquibase formatted sql

--changeset rowert:022-spot-cover-photo
--comment: Selbst gewähltes Titelbild einer Bude (Ersteller oder Admin). NULL = erstes Foto. Wird das Foto gelöscht, gilt wieder das erste Foto
ALTER TABLE spot ADD COLUMN cover_photo_id BIGINT;
ALTER TABLE spot ADD CONSTRAINT fk_spot_cover_photo FOREIGN KEY (cover_photo_id) REFERENCES spot_photo(id) ON DELETE SET NULL;
CREATE INDEX idx_spot_cover_photo ON spot(cover_photo_id);
--rollback DROP INDEX idx_spot_cover_photo;
--rollback ALTER TABLE spot DROP CONSTRAINT fk_spot_cover_photo;
--rollback ALTER TABLE spot DROP COLUMN cover_photo_id;

--changeset rowert:022-create-index-spot-created-at
--comment: Startseite: neueste Buden
CREATE INDEX idx_spot_created_at ON spot(created_at DESC, id DESC);
--rollback DROP INDEX idx_spot_created_at;

--changeset rowert:022-create-index-rating-created-at
--comment: Startseite: zuletzt bewertet
CREATE INDEX idx_rating_created_at ON rating(created_at DESC, id DESC);
--rollback DROP INDEX idx_rating_created_at;
