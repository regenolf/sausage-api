package de.robertegenolf.sausageapi.user;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Sammelt alle zu einem Account gespeicherten Daten (Auskunft und Datenübertragbarkeit, Art. 15/20 DSGVO). */
@Repository
class UserExportRepository {

	private final JdbcClient jdbc;
	private final String publicUrl;

	UserExportRepository(JdbcClient jdbc, @Value("${app.public-url:}") String publicUrl) {
		this.jdbc = jdbc;
		this.publicUrl = publicUrl.replaceAll("/+$", "");
	}

	Map<String, Object> export(long userId) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("profil", rows("""
				SELECT id, username, email, role, enabled, to_char(created_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS erstellt_am,
				       terms_version AS nutzungsbedingungen_version,
				       to_char(terms_accepted_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS nutzungsbedingungen_akzeptiert_am
				FROM app_user WHERE id = :id""", userId).stream().findFirst().orElse(Map.of()));
		data.put("spots", rows("""
				SELECT s.id, c.code AS kategorie, s.name, s.description AS beschreibung, s.street AS strasse,
				       s.house_number AS hausnummer, s.zip_code AS plz, s.city AS ort, s.latitude, s.longitude,
				       s.opening_hours AS oeffnungszeiten,
				       to_char(s.created_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS erstellt_am,
				       to_char(s.updated_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS geaendert_am
				FROM spot s JOIN category c ON c.id = s.category_id
				WHERE s.created_by = :id ORDER BY s.id""", userId));
		data.put("bewertungen", rows("""
				SELECT r.id, r.spot_id, s.name AS spot, r.score AS sterne, r.comment AS kommentar,
				       r.sausage_score AS note_wurst, r.bun_score AS note_broetchen, r.mustard_score AS note_senf,
				       r.value_score AS note_preis_leistung, r.price_cents AS preis_cent,
				       to_char(r.created_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS erstellt_am
				FROM rating r JOIN spot s ON s.id = r.spot_id
				WHERE r.user_id = :id ORDER BY r.id""", userId));
		data.put("kommentare", rows("""
				SELECT c.id, c.spot_id, s.name AS spot, c.text,
				       to_char(c.created_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS erstellt_am
				FROM comment c JOIN spot s ON s.id = c.spot_id
				WHERE c.user_id = :id ORDER BY c.id""", userId));
		data.put("fotos", rows("""
				SELECT p.id, p.spot_id, s.name AS spot, p.content_type, p.size_bytes AS groesse_bytes,
				       :base || '/api/spots/' || p.spot_id || '/photos/' || p.id AS url,
				       to_char(p.created_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS erstellt_am
				FROM spot_photo p JOIN spot s ON s.id = p.spot_id
				WHERE p.user_id = :id ORDER BY p.id""", userId));
		data.put("meldungen", rows("""
				SELECT id, target_type AS art, target_id AS inhalt_id, reason AS grund, message AS nachricht,
				       status, decision_note AS begruendung,
				       to_char(created_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS erstellt_am
				FROM report WHERE reporter_id = :id ORDER BY id""", userId));
		// Gesicherte Inhalte der Person, die gemeldet wurden (inkl. Entscheidung) – ohne Angaben zu den Meldern
		data.put("meldungen_zu_eigenen_inhalten", rows("""
				SELECT DISTINCT ON (target_type, target_id) target_type AS art, target_id AS inhalt_id,
				       reason AS grund, status, decision_note AS begruendung, content_snapshot AS inhalt,
				       to_char(decided_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS entschieden_am
				FROM report WHERE target_author_id = :id
				ORDER BY target_type, target_id, created_at DESC""", userId));
		data.put("wurst_tagebuch", rows("""
				SELECT ci.id, ci.spot_id, s.name AS spot, to_char(ci.eaten_on, 'YYYY-MM-DD') AS gegessen_am,
				       to_char(ci.created_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS erstellt_am
				FROM check_in ci JOIN spot s ON s.id = ci.spot_id
				WHERE ci.user_id = :id ORDER BY ci.id""", userId));
		data.put("listen", rows("""
				SELECT l.id, l.title AS titel, l.description AS beschreibung, l.is_public AS oeffentlich,
				       (SELECT string_agg(s.name || ' (' || s.id || ')', ', ' ORDER BY i.added_at)
				        FROM spot_list_item i JOIN spot s ON s.id = i.spot_id WHERE i.list_id = l.id) AS buden,
				       to_char(l.created_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS erstellt_am,
				       to_char(l.updated_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS geaendert_am
				FROM spot_list l WHERE l.user_id = :id ORDER BY l.id""", userId));
		data.put("hinweise_zu_buden", rows("""
				SELECT i.id, i.spot_id, s.name AS spot, i.type AS art, i.message AS nachricht, i.status,
				       to_char(i.created_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS erstellt_am
				FROM spot_issue i JOIN spot s ON s.id = i.spot_id
				WHERE i.user_id = :id ORDER BY i.id""", userId));
		// Push-Token nur gekürzt: Sie sind Gerätekennungen und für die Auskunft ohne Nutzen
		data.put("geraete_fuer_benachrichtigungen", rows("""
				SELECT platform AS plattform, '…' || right(token, 8) AS token_ende,
				       to_char(created_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS erstellt_am,
				       to_char(last_seen_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS zuletzt_gesehen
				FROM push_device WHERE user_id = :id ORDER BY id""", userId));
		data.put("blockierte_nutzer", rows("""
				SELECT u.username, to_char(b.created_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS blockiert_am
				FROM user_block b JOIN app_user u ON u.id = b.blocked_id
				WHERE b.blocker_id = :id ORDER BY u.username""", userId));
		return data;
	}

	private List<Map<String, Object>> rows(String sql, long userId) {
		var spec = jdbc.sql(sql).param("id", userId);
		if (sql.contains(":base")) {
			spec = spec.param("base", publicUrl);
		}
		return spec.query().listOfRows();
	}
}
