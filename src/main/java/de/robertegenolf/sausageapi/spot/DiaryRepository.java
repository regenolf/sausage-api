package de.robertegenolf.sausageapi.spot;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Wurst-Tagebuch: „Hier gegessen“ (höchstens einmal pro Bude und Tag) und Kennzahlen für die Abzeichen. */
@Repository
class DiaryRepository {

	record StoredCheckIn(long id, long spotId, LocalDate eatenOn, Instant createdAt) {
	}

	/** Zahlen eines Nutzers, aus denen {@link Badges} die Abzeichen berechnet. */
	record Counts(long checkIns, long spots, long cities, long maxAtOneSpot, long ratings, long ratingsWithPrice,
			long photos, long spotsCreated, long issues) {
	}

	/** Am häufigsten besuchte Bude. */
	record Favorite(long spotId, long count) {
	}

	private final JdbcClient jdbc;

	DiaryRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Leer, wenn es für heute schon einen Eintrag zu dieser Bude gibt. */
	Optional<StoredCheckIn> create(long userId, long spotId) {
		return jdbc.sql("""
				INSERT INTO check_in (user_id, spot_id) VALUES (:userId, :spotId)
				ON CONFLICT (user_id, spot_id, eaten_on) DO NOTHING
				RETURNING id, spot_id, eaten_on, created_at
				""")
				.param("userId", userId)
				.param("spotId", spotId)
				.query((rs, n) -> new StoredCheckIn(rs.getLong("id"), rs.getLong("spot_id"),
						rs.getDate("eaten_on").toLocalDate(), rs.getTimestamp("created_at").toInstant()))
				.optional();
	}

	List<StoredCheckIn> findByUser(long userId, int limit) {
		return jdbc.sql("""
				SELECT id, spot_id, eaten_on, created_at FROM check_in
				WHERE user_id = :userId
				ORDER BY created_at DESC, id DESC
				LIMIT :limit
				""")
				.param("userId", userId)
				.param("limit", limit)
				.query((rs, n) -> new StoredCheckIn(rs.getLong("id"), rs.getLong("spot_id"),
						rs.getDate("eaten_on").toLocalDate(), rs.getTimestamp("created_at").toInstant()))
				.list();
	}

	/** Anzahl und letzter Besuch einer Bude. */
	record SpotVisits(long count, LocalDate lastEatenOn) {
	}

	SpotVisits visits(long userId, long spotId) {
		return jdbc.sql("SELECT COUNT(*) AS count, MAX(eaten_on) AS last FROM check_in WHERE user_id = :userId AND spot_id = :spotId")
				.param("userId", userId)
				.param("spotId", spotId)
				.query((rs, n) -> new SpotVisits(rs.getLong("count"),
						rs.getDate("last") == null ? null : rs.getDate("last").toLocalDate()))
				.single();
	}

	boolean delete(long userId, long checkInId) {
		return jdbc.sql("DELETE FROM check_in WHERE id = :id AND user_id = :userId")
				.param("id", checkInId)
				.param("userId", userId)
				.update() > 0;
	}

	Counts counts(long userId) {
		return jdbc.sql("""
				SELECT
				  (SELECT COUNT(*) FROM check_in WHERE user_id = :userId) AS check_ins,
				  (SELECT COUNT(DISTINCT spot_id) FROM check_in WHERE user_id = :userId) AS spots,
				  (SELECT COUNT(DISTINCT lower(trim(s.city))) FROM check_in ci JOIN spot s ON s.id = ci.spot_id
				   WHERE ci.user_id = :userId AND s.city IS NOT NULL AND trim(s.city) <> '') AS cities,
				  (SELECT COALESCE(MAX(cnt), 0) FROM (SELECT COUNT(*) AS cnt FROM check_in WHERE user_id = :userId
				   GROUP BY spot_id) per_spot) AS max_at_one_spot,
				  (SELECT COUNT(*) FROM rating WHERE user_id = :userId) AS ratings,
				  (SELECT COUNT(*) FROM rating WHERE user_id = :userId AND price_cents IS NOT NULL) AS ratings_with_price,
				  (SELECT COUNT(*) FROM spot_photo WHERE user_id = :userId) AS photos,
				  (SELECT COUNT(*) FROM spot WHERE created_by = :userId) AS spots_created,
				  (SELECT COUNT(*) FROM spot_issue WHERE user_id = :userId AND status = 'RESOLVED') AS issues
				""")
				.param("userId", userId)
				.query((rs, n) -> new Counts(rs.getLong("check_ins"), rs.getLong("spots"), rs.getLong("cities"),
						rs.getLong("max_at_one_spot"), rs.getLong("ratings"), rs.getLong("ratings_with_price"),
						rs.getLong("photos"), rs.getLong("spots_created"), rs.getLong("issues")))
				.single();
	}

	Optional<Favorite> favorite(long userId) {
		return jdbc.sql("""
				SELECT spot_id, COUNT(*) AS cnt FROM check_in WHERE user_id = :userId
				GROUP BY spot_id ORDER BY cnt DESC, MAX(created_at) DESC LIMIT 1
				""")
				.param("userId", userId)
				.query((rs, n) -> new Favorite(rs.getLong("spot_id"), rs.getLong("cnt")))
				.optional();
	}
}
