--liquibase formatted sql

--changeset rowert:010-spot-created-by-nullable
--comment: Account-Löschung (DSGVO) - Spots bleiben erhalten, werden aber anonymisiert
ALTER TABLE spot ALTER COLUMN created_by DROP NOT NULL;
ALTER TABLE spot DROP CONSTRAINT fk_spot_created_by;
ALTER TABLE spot ADD CONSTRAINT fk_spot_created_by FOREIGN KEY (created_by) REFERENCES app_user(id) ON DELETE SET NULL;
--rollback ALTER TABLE spot DROP CONSTRAINT fk_spot_created_by;
--rollback ALTER TABLE spot ADD CONSTRAINT fk_spot_created_by FOREIGN KEY (created_by) REFERENCES app_user(id);
--rollback ALTER TABLE spot ALTER COLUMN created_by SET NOT NULL;

--changeset rowert:010-user-content-cascade
--comment: Bewertungen, Kommentare und Fotos werden mit dem Account gelöscht
ALTER TABLE rating DROP CONSTRAINT fk_rating_user;
ALTER TABLE rating ADD CONSTRAINT fk_rating_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE;
ALTER TABLE comment DROP CONSTRAINT fk_comment_user;
ALTER TABLE comment ADD CONSTRAINT fk_comment_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE;
ALTER TABLE spot_photo DROP CONSTRAINT fk_spot_photo_user;
ALTER TABLE spot_photo ADD CONSTRAINT fk_spot_photo_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE;
--rollback ALTER TABLE rating DROP CONSTRAINT fk_rating_user;
--rollback ALTER TABLE rating ADD CONSTRAINT fk_rating_user FOREIGN KEY (user_id) REFERENCES app_user(id);
--rollback ALTER TABLE comment DROP CONSTRAINT fk_comment_user;
--rollback ALTER TABLE comment ADD CONSTRAINT fk_comment_user FOREIGN KEY (user_id) REFERENCES app_user(id);
--rollback ALTER TABLE spot_photo DROP CONSTRAINT fk_spot_photo_user;
--rollback ALTER TABLE spot_photo ADD CONSTRAINT fk_spot_photo_user FOREIGN KEY (user_id) REFERENCES app_user(id);
