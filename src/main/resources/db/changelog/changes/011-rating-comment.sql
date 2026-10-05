--liquibase formatted sql

--changeset rowert:011-add-rating-comment
--comment: Optionaler Text zur Bewertung (Bewertung = Sterne + Kommentar, wie im Frontend)
ALTER TABLE rating ADD COLUMN comment VARCHAR(2000);
--rollback ALTER TABLE rating DROP COLUMN comment;
