package de.robertegenolf.sausageapi.moderation;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
class ReportRepository {

	enum TargetType { SPOT, RATING, COMMENT, PHOTO }

	enum Reason { ILLEGAL, INSULT, SPAM, PRIVACY, COPYRIGHT, WRONG_INFO, OTHER }

	enum Status { OPEN, REMOVED, REJECTED }

	/** Gemeldeter Inhalt: zugehöriger Spot, Verfasser und eine kurze Vorschau für die Moderation. */
	record Target(Long spotId, String author, String preview) {
	}

	record StoredReport(long id, TargetType targetType, long targetId, Reason reason, String message,
			String reporter, String reporterEmail, Status status, String decisionNote, String decidedBy,
			Instant decidedAt, Instant createdAt) {
	}

	private final JdbcClient jdbc;

	ReportRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Optional<Target> findTarget(TargetType type, long id) {
		String sql = switch (type) {
			case SPOT -> """
					SELECT s.id AS spot_id, u.username AS author, s.name || COALESCE(': ' || s.description, '') AS preview
					FROM spot s LEFT JOIN app_user u ON u.id = s.created_by WHERE s.id = :id""";
			case RATING -> """
					SELECT r.spot_id, u.username AS author, r.score || ' Sterne' || COALESCE(': ' || r.comment, '') AS preview
					FROM rating r JOIN app_user u ON u.id = r.user_id WHERE r.id = :id""";
			case COMMENT -> """
					SELECT c.spot_id, u.username AS author, c.text AS preview
					FROM comment c JOIN app_user u ON u.id = c.user_id WHERE c.id = :id""";
			case PHOTO -> """
					SELECT p.spot_id, u.username AS author, '/api/spots/' || p.spot_id || '/photos/' || p.id AS preview
					FROM spot_photo p JOIN app_user u ON u.id = p.user_id WHERE p.id = :id""";
		};
		return jdbc.sql(sql)
				.param("id", id)
				.query((rs, n) -> new Target(rs.getLong("spot_id"), rs.getString("author"), rs.getString("preview")))
				.optional();
	}

	/** Entfernt den Inhalt; bei Spots werden Bewertungen, Kommentare und Fotos mitgelöscht. */
	void deleteTarget(TargetType type, long id) {
		String table = switch (type) {
			case SPOT -> "spot";
			case RATING -> "rating";
			case COMMENT -> "comment";
			case PHOTO -> "spot_photo";
		};
		jdbc.sql("DELETE FROM " + table + " WHERE id = :id").param("id", id).update();
	}

	long create(TargetType type, long targetId, Reason reason, String message, Long reporterId, String reporterEmail) {
		return jdbc.sql("""
				INSERT INTO report (target_type, target_id, reason, message, reporter_id, reporter_email)
				VALUES (:type, :targetId, :reason, :message, :reporterId, :reporterEmail)
				RETURNING id
				""")
				.param("type", type.name())
				.param("targetId", targetId)
				.param("reason", reason.name())
				.param("message", message)
				.param("reporterId", reporterId)
				.param("reporterEmail", reporterEmail)
				.query(Long.class)
				.single();
	}

	private static final String SELECT = """
			SELECT r.*, rep.username AS reporter, dec.username AS decided_by_name
			FROM report r
			LEFT JOIN app_user rep ON rep.id = r.reporter_id
			LEFT JOIN app_user dec ON dec.id = r.decided_by
			""";

	List<StoredReport> findByStatus(Status status) {
		return jdbc.sql(SELECT + "WHERE (CAST(:status AS varchar) IS NULL OR r.status = :status)\nORDER BY r.created_at, r.id\n")
				.param("status", status == null ? null : status.name())
				.query((rs, n) -> map(rs))
				.list();
	}

	Optional<StoredReport> find(long id) {
		return jdbc.sql(SELECT + "WHERE r.id = :id\n")
				.param("id", id)
				.query((rs, n) -> map(rs))
				.optional();
	}

	List<StoredReport> findByReporter(long reporterId) {
		return jdbc.sql(SELECT + "WHERE r.reporter_id = :reporterId\nORDER BY r.created_at DESC\n")
				.param("reporterId", reporterId)
				.query((rs, n) -> map(rs))
				.list();
	}

	void decide(long id, Status status, String note, long decidedBy) {
		jdbc.sql("""
				UPDATE report SET status = :status, decision_note = :note, decided_by = :decidedBy,
				                  decided_at = CURRENT_TIMESTAMP
				WHERE id = :id
				""")
				.param("status", status.name())
				.param("note", note)
				.param("decidedBy", decidedBy)
				.param("id", id)
				.update();
	}

	/** Entscheidet alle offenen Meldungen zum selben Inhalt gleich mit. */
	void decideAllOpen(TargetType type, long targetId, Status status, String note, long decidedBy) {
		jdbc.sql("""
				UPDATE report SET status = :status, decision_note = :note, decided_by = :decidedBy,
				                  decided_at = CURRENT_TIMESTAMP
				WHERE target_type = :type AND target_id = :targetId AND status = 'OPEN'
				""")
				.param("status", status.name())
				.param("note", note)
				.param("decidedBy", decidedBy)
				.param("type", type.name())
				.param("targetId", targetId)
				.update();
	}

	private static StoredReport map(java.sql.ResultSet rs) throws java.sql.SQLException {
		java.sql.Timestamp decidedAt = rs.getTimestamp("decided_at");
		return new StoredReport(rs.getLong("id"), TargetType.valueOf(rs.getString("target_type")),
				rs.getLong("target_id"), Reason.valueOf(rs.getString("reason")), rs.getString("message"),
				rs.getString("reporter"), rs.getString("reporter_email"), Status.valueOf(rs.getString("status")),
				rs.getString("decision_note"), rs.getString("decided_by_name"),
				decidedAt == null ? null : decidedAt.toInstant(), rs.getTimestamp("created_at").toInstant());
	}
}
