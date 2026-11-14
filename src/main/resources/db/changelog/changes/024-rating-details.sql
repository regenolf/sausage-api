--liquibase formatted sql

--changeset rowert:024-rating-sub-scores-and-price
--comment: Optionale Teilnoten (Wurst, Brötchen, Senf, Preis-Leistung) und bezahlter Preis einer Bratwurst in Cent zur Bewertung; die Gesamtsterne bleiben Pflicht
ALTER TABLE rating ADD COLUMN sausage_score SMALLINT;
ALTER TABLE rating ADD COLUMN bun_score SMALLINT;
ALTER TABLE rating ADD COLUMN mustard_score SMALLINT;
ALTER TABLE rating ADD COLUMN value_score SMALLINT;
ALTER TABLE rating ADD COLUMN price_cents INTEGER;
ALTER TABLE rating ADD CONSTRAINT chk_rating_sausage_score CHECK (sausage_score BETWEEN 1 AND 5);
ALTER TABLE rating ADD CONSTRAINT chk_rating_bun_score CHECK (bun_score BETWEEN 1 AND 5);
ALTER TABLE rating ADD CONSTRAINT chk_rating_mustard_score CHECK (mustard_score BETWEEN 1 AND 5);
ALTER TABLE rating ADD CONSTRAINT chk_rating_value_score CHECK (value_score BETWEEN 1 AND 5);
ALTER TABLE rating ADD CONSTRAINT chk_rating_price_cents CHECK (price_cents BETWEEN 50 AND 3000);
--rollback ALTER TABLE rating DROP COLUMN price_cents;
--rollback ALTER TABLE rating DROP COLUMN value_score;
--rollback ALTER TABLE rating DROP COLUMN mustard_score;
--rollback ALTER TABLE rating DROP COLUMN bun_score;
--rollback ALTER TABLE rating DROP COLUMN sausage_score;
