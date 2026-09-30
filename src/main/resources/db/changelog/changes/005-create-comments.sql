--liquibase formatted sql

--changeset rowert:005-create-table-comment
--comment: Freitext-Kommentare von Usern zu Spots
CREATE TABLE comment (
    id BIGSERIAL PRIMARY KEY,
    spot_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    text VARCHAR(2000) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_comment_spot FOREIGN KEY (spot_id) REFERENCES spot(id),
    CONSTRAINT fk_comment_user FOREIGN KEY (user_id) REFERENCES app_user(id)
);
--rollback DROP TABLE comment;

--changeset rowert:005-create-index-comment-spot
CREATE INDEX idx_comment_spot ON comment(spot_id);
--rollback DROP INDEX idx_comment_spot;
