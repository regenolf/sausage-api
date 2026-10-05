--liquibase formatted sql

--changeset rowert:007-rating-spot-fk-cascade
--comment: Bewertungen werden mit dem Spot gelöscht
ALTER TABLE rating DROP CONSTRAINT fk_rating_spot;
ALTER TABLE rating ADD CONSTRAINT fk_rating_spot FOREIGN KEY (spot_id) REFERENCES spot(id) ON DELETE CASCADE;
--rollback ALTER TABLE rating DROP CONSTRAINT fk_rating_spot;
--rollback ALTER TABLE rating ADD CONSTRAINT fk_rating_spot FOREIGN KEY (spot_id) REFERENCES spot(id);

--changeset rowert:007-comment-spot-fk-cascade
--comment: Kommentare werden mit dem Spot gelöscht
ALTER TABLE comment DROP CONSTRAINT fk_comment_spot;
ALTER TABLE comment ADD CONSTRAINT fk_comment_spot FOREIGN KEY (spot_id) REFERENCES spot(id) ON DELETE CASCADE;
--rollback ALTER TABLE comment DROP CONSTRAINT fk_comment_spot;
--rollback ALTER TABLE comment ADD CONSTRAINT fk_comment_spot FOREIGN KEY (spot_id) REFERENCES spot(id);
