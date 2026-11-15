package de.robertegenolf.sausageapi.spot;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * „Stimmt was nicht?“: Hinweise auf veraltete oder falsche Angaben einer Bude. Anders als Meldungen (DSA) geht es
 * nicht um Regelverstöße, sondern um die Datenqualität – besonders bei aus OpenStreetMap importierten Buden.
 */
@Repository
class IssueRepository {

	enum Type { CLOSED, WRONG_LOCATION, WRONG_HOURS, WRONG_DETAILS, OTHER }

	enum Status { OPEN, RESOLVED, REJECTED }

	/** Hinweis für die Moderation, mit der Bude und wie viele offene Hinweise es zu ihr insgesamt gibt. */
	record IssueView(long id, Type type, String message, Status status, Instant createdAt, String reportedBy,
			long spotId, String spotName, String city, String source, boolean verified, long openForSpot) {
	}

	/** Höchstzahl offener Hinweise eines Nutzers zur selben Bude (gegen Doppelklicks und Spam). */
	static final int MAX_OPEN_PER_USER_AND_SPOT = 3;

	private final JdbcClient jdbc;

	IssueRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	long create(long spotId, long userId, Type type, String message) {
		return jdbc.sql("""
				INSERT INTO spot_issue (spot_id, user_id, type, message)
				VALUES (:spotId, :userId, :type, :message)
				RETURNING id
				""")
				.param("spotId", spotId)
				.param("userId", userId)
				.param("type", type.name())
				.param("message", message)
				.query(Long.class)
				.single();
	}

	long countOpen(long spotId, long userId) {
		return jdbc.sql("SELECT COUNT(*) FROM spot_issue WHERE spot_id = :spotId AND user_id = :userId AND status = 'OPEN'")
				.param("spotId", spotId)
				.param("userId", userId)
				.query(Long.class)
				.single();
	}

	/** {@code status == null}: alle; älteste zuerst, damit nichts liegen bleibt. */
	List<IssueView> find(Status status, int limit) {
		return jdbc.sql("""
				SELECT i.id, i.type, i.message, i.status, i.created_at, u.username,
				       s.id AS spot_id, s.name, s.city, s.source, s.verified,
				       (SELECT COUNT(*) FROM spot_issue o WHERE o.spot_id = s.id AND o.status = 'OPEN') AS open_for_spot
				FROM spot_issue i
				JOIN spot s ON s.id = i.spot_id
				LEFT JOIN app_user u ON u.id = i.user_id
				WHERE CAST(:status AS varchar) IS NULL OR i.status = :status
				ORDER BY i.created_at, i.id
				LIMIT :limit
				""")
				.param("status", status == null ? null : status.name())
				.param("limit", limit)
				.query((rs, n) -> new IssueView(rs.getLong("id"), Type.valueOf(rs.getString("type")),
						rs.getString("message"), Status.valueOf(rs.getString("status")),
						rs.getTimestamp("created_at").toInstant(), rs.getString("username"), rs.getLong("spot_id"),
						rs.getString("name"), rs.getString("city"), rs.getString("source"), rs.getBoolean("verified"),
						rs.getLong("open_for_spot")))
				.list();
	}

	Optional<Status> findStatus(long id) {
		return jdbc.sql("SELECT status FROM spot_issue WHERE id = :id FOR UPDATE")
				.param("id", id)
				.query((rs, n) -> Status.valueOf(rs.getString("status")))
				.optional();
	}

	/**
	 * Entscheidet den Hinweis. {@code allForSpot}: alle offenen Hinweise derselben Bude mit erledigen (z. B. wenn
	 * die Bude korrigiert oder gelöscht wurde).
	 */
	void decide(long id, Status status, long decidedBy, boolean allForSpot) {
		String where = allForSpot
				? "spot_id = (SELECT spot_id FROM spot_issue WHERE id = :id) AND status = 'OPEN'"
				: "id = :id";
		jdbc.sql("UPDATE spot_issue SET status = :status, decided_by = :decidedBy, decided_at = CURRENT_TIMESTAMP WHERE "
						+ where)
				.param("id", id)
				.param("status", status.name())
				.param("decidedBy", decidedBy)
				.update();
	}
}
