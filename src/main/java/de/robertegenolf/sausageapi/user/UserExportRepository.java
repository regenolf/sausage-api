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
