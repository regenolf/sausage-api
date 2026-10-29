--liquibase formatted sql

--changeset rowert:020-spot-source
--comment: Herkunft der Spots für Datenimporte: USER (in der App angelegt) oder importiert (OSM, später FSQ). external_id ist die ID in der Quelle (z. B. node/123) und macht Importe wiederholbar; verified = von der Moderation geprüft (App-Spots gelten als geprüft); imported_at = zuletzt im Import gesehen
ALTER TABLE spot ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'USER';
ALTER TABLE spot ADD COLUMN external_id VARCHAR(100);
ALTER TABLE spot ADD COLUMN verified BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE spot ADD COLUMN imported_at TIMESTAMP;
ALTER TABLE spot ADD CONSTRAINT chk_spot_source CHECK (source IN ('USER', 'OSM', 'FSQ'));
ALTER TABLE spot ADD CONSTRAINT chk_spot_external_id CHECK ((source = 'USER') = (external_id IS NULL));
ALTER TABLE spot ADD CONSTRAINT uk_spot_source_external_id UNIQUE (source, external_id);
--rollback ALTER TABLE spot DROP CONSTRAINT uk_spot_source_external_id;
--rollback ALTER TABLE spot DROP CONSTRAINT chk_spot_external_id;
--rollback ALTER TABLE spot DROP CONSTRAINT chk_spot_source;
--rollback ALTER TABLE spot DROP COLUMN imported_at;
--rollback ALTER TABLE spot DROP COLUMN verified;
--rollback ALTER TABLE spot DROP COLUMN external_id;
--rollback ALTER TABLE spot DROP COLUMN source;

--changeset rowert:020-create-index-spot-unverified
--comment: Prüfliste der Moderation (nur ungeprüfte Spots)
CREATE INDEX idx_spot_unverified ON spot(created_at) WHERE verified = FALSE;
--rollback DROP INDEX idx_spot_unverified;

--changeset rowert:020-create-table-spot-import-rejection
--comment: Gelöschte importierte Spots merken, damit sie beim nächsten Import nicht wiederkommen
CREATE TABLE spot_import_rejection (
    source VARCHAR(20) NOT NULL,
    external_id VARCHAR(100) NOT NULL,
    rejected_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_spot_import_rejection PRIMARY KEY (source, external_id)
);
--rollback DROP TABLE spot_import_rejection;

--changeset rowert:020-create-function-remember-rejected-import splitStatements:false
--comment: Trigger-Funktion: beim Löschen eines importierten Spots (egal über welchen Weg) die externe ID sperren
CREATE FUNCTION remember_rejected_import() RETURNS trigger AS $$
BEGIN
    IF OLD.external_id IS NOT NULL THEN
        INSERT INTO spot_import_rejection (source, external_id)
        VALUES (OLD.source, OLD.external_id)
        ON CONFLICT DO NOTHING;
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;
--rollback DROP FUNCTION remember_rejected_import();

--changeset rowert:020-create-trigger-spot-import-rejection
CREATE TRIGGER trg_spot_import_rejection
    AFTER DELETE ON spot
    FOR EACH ROW EXECUTE FUNCTION remember_rejected_import();
--rollback DROP TRIGGER trg_spot_import_rejection ON spot;
