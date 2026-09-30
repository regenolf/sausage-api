--liquibase formatted sql

--changeset rowert:004-create-table-rating
--comment: Bewertungen von Usern zu Spots - ein User kann einen Spot nur einmal bewerten
CREATE TABLE rating (
    id BIGSERIAL PRIMARY KEY,
    spot_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    score SMALLINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_rating_spot FOREIGN KEY (spot_id) REFERENCES spot(id),
    CONSTRAINT fk_rating_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT chk_rating_score CHECK (score BETWEEN 1 AND 5),
    CONSTRAINT uk_rating_spot_user UNIQUE (spot_id, user_id)
);
--rollback DROP TABLE rating;
