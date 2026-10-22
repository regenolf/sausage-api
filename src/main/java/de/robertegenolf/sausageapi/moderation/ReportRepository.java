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
	record Target(Long spotId, Long authorId, String author, String preview) {
	}

	/**
	 * {@code reporterAccountEmail}: Adresse des Melder-Kontos (für die Benachrichtigung), {@code snapshot*}: beim Melden
	 * gesicherter Stand des Inhalts – bleibt erhalten, wenn der Inhalt entfernt wird.
	 */
	record StoredReport(long id, TargetType targetType, long targetId, Reason reason, String message,
			String reporter, String reporterEmail, Status status, String decisionNote, String decidedBy,
			Instant decidedAt, Instant createdAt, String reporterAccountEmail, Long targetAuthorId,
			String snapshotAuthor, Long snapshotSpotId, String contentSnapshot) {

		/** Adresse für die Benachrichtigung: Konto oder (ohne Anmeldung) die angegebene. */
		String notifyEmail() {
			return reporterAccountEmail != null ? reporterAccountEmail : reporterEmail;
		}
	}

	/** Entscheidung über Inhalte einer Person (für Betroffene, DSA Art. 17). */
	record Decision(long reportId, TargetType targetType, long targetId, Reason reason, String decisionNote,
			Instant decidedAt, String contentSnapshot) {
	}

	private final JdbcClient jdbc;

	ReportRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Optional<Target> findTarget(TargetType type, long id) {
		String sql = switch (type) {
			case SPOT -> """
					SELECT s.id AS spot_id, s.created_by AS author_id, u.username AS author,
					       s.name || COALESCE(': ' || s.description, '') AS preview
					FROM spot s LEFT JOIN app_user u ON u.id = s.created_by WHERE s.id = :id""";
			case RATING -> """
					SELECT r.spot_id, r.user_id AS author_id, u.username AS author,
					       r.score || ' Sterne' || COALESCE(': ' || r.comment, '') AS preview
					FROM rating r JOIN app_user u ON u.id = r.user_id WHERE r.id = :id""";
			case COMMENT -> """
					SELECT c.spot_id, c.user_id AS author_id, u.username AS author, c.text AS preview
					FROM comment c JOIN app_user u ON u.id = c.user_id WHERE c.id = :id""";
			case PHOTO -> """
					SELECT p.spot_id, p.user_id AS author_id, u.username AS author,
					       '/api/spots/' || p.spot_id || '/photos/' || p.id AS preview
					FROM spot_photo p JOIN app_user u ON u.id = p.user_id WHERE p.id = :id""";
		};
		return jdbc.sql(sql)
				.param("id", id)
				.query((rs, n) -> new Target(rs.getLong("spot_id"), (Long) rs.getObject("author_id", Long.class),
						rs.getString("author"), rs.getString("preview")))
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

	long create(TargetType type, long targetId, Reason reason, String message, Long reporterId, String reporterEmail,
			Target target) {
		String snapshot = target.preview() == null ? null
				: target.preview().length() <= 2000 ? target.preview() : target.preview().substring(0, 2000);
		return jdbc.sql("""
				INSERT INTO report (target_type, target_id, reason, message, reporter_id, reporter_email,
				                    target_author_id, target_spot_id, content_snapshot)
				VALUES (:type, :targetId, :reason, :message, :reporterId, :reporterEmail,
				        :authorId, :spotId, :snapshot)
				RETURNING id
				""")
				.param("authorId", target.authorId())
				.param("spotId", target.spotId())
				.param("snapshot", snapshot)
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
			SELECT r.*, rep.username AS reporter, rep.email AS reporter_account_email, dec.username AS decided_by_name,
			       au.username AS snapshot_author
			FROM report r
			LEFT JOIN app_user rep ON rep.id = r.reporter_id
			LEFT JOIN app_user dec ON dec.id = r.decided_by
			LEFT JOIN app_user au ON au.id = r.target_author_id
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

	/** Offene Meldungen zum Inhalt (vor der Entscheidung, damit alle Melder benachrichtigt werden). */
	List<StoredReport> findOpenForTarget(TargetType type, long targetId) {
		return jdbc.sql(SELECT + "WHERE r.target_type = :type AND r.target_id = :targetId AND r.status = 'OPEN'\n")
				.param("type", type.name())
				.param("targetId", targetId)
				.query((rs, n) -> map(rs))
				.list();
	}

	/** Offene Meldungen zu Bewertungen, Kommentaren und Fotos eines Spots. */
	List<StoredReport> findOpenForSpotContent(long spotId) {
		return jdbc.sql(SELECT + """
				WHERE r.status = 'OPEN' AND (
				      (r.target_type = 'RATING' AND r.target_id IN (SELECT id FROM rating WHERE spot_id = :spotId))
				   OR (r.target_type = 'COMMENT' AND r.target_id IN (SELECT id FROM comment WHERE spot_id = :spotId))
				   OR (r.target_type = 'PHOTO' AND r.target_id IN (SELECT id FROM spot_photo WHERE spot_id = :spotId)))
				""")
				.param("spotId", spotId)
				.query((rs, n) -> map(rs))
				.list();
	}

	/** Entfernte Inhalte einer Person, je Inhalt einmal (mehrere Meldungen zum selben Inhalt zählen einfach). */
	List<Decision> findRemovedForAuthor(long authorId) {
		return jdbc.sql("""
				SELECT DISTINCT ON (target_type, target_id) id, target_type, target_id, reason, decision_note, decided_at,
				       content_snapshot
				FROM report
				WHERE target_author_id = :authorId AND status = 'REMOVED'
				ORDER BY target_type, target_id, decided_at DESC
				""")
				.param("authorId", authorId)
				.query((rs, n) -> new Decision(rs.getLong("id"), TargetType.valueOf(rs.getString("target_type")),
						rs.getLong("target_id"), Reason.valueOf(rs.getString("reason")), rs.getString("decision_note"),
						rs.getTimestamp("decided_at").toInstant(), rs.getString("content_snapshot")))
				.list().stream()
				.sorted(java.util.Comparator.comparing(Decision::decidedAt).reversed())
				.toList();
	}

	/** Sperrt die Meldung bis Transaktionsende, damit zwei Admins sie nicht gleichzeitig entscheiden. */
	void lock(long id) {
		jdbc.sql("SELECT id FROM report WHERE id = :id FOR UPDATE").param("id", id).query(Long.class).optional();
	}

	/**
	 * Vor dem Entfernen eines Spots: offene Meldungen zu seinen Bewertungen, Kommentaren und Fotos mit entscheiden –
	 * die Inhalte verschwinden per Kaskade, die Meldungen blieben sonst ohne Inhalt dauerhaft offen.
	 */
	void decideOpenForSpotContent(long spotId, Status status, String note, long decidedBy) {
		jdbc.sql("""
				UPDATE report SET status = :status, decision_note = :note, decided_by = :decidedBy,
				                  decided_at = CURRENT_TIMESTAMP
				WHERE status = 'OPEN' AND (
				      (target_type = 'RATING' AND target_id IN (SELECT id FROM rating WHERE spot_id = :spotId))
				   OR (target_type = 'COMMENT' AND target_id IN (SELECT id FROM comment WHERE spot_id = :spotId))
				   OR (target_type = 'PHOTO' AND target_id IN (SELECT id FROM spot_photo WHERE spot_id = :spotId)))
				""")
				.param("status", status.name())
				.param("note", note)
				.param("decidedBy", decidedBy)
				.param("spotId", spotId)
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
				decidedAt == null ? null : decidedAt.toInstant(), rs.getTimestamp("created_at").toInstant(),
				rs.getString("reporter_account_email"), rs.getObject("target_author_id", Long.class),
				rs.getString("snapshot_author"), rs.getObject("target_spot_id", Long.class),
				rs.getString("content_snapshot"));
	}
}
