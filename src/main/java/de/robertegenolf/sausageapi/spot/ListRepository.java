package de.robertegenolf.sausageapi.spot;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Eigene Listen von Buden („Meine Top 5 in Erfurt“), privat oder öffentlich per Link. */
@Repository
class ListRepository {

	/** Höchstzahlen gegen Missbrauch. */
	static final int MAX_LISTS_PER_USER = 50;
	static final int MAX_SPOTS_PER_LIST = 200;

	record StoredList(long id, long ownerId, String owner, String title, String description, boolean isPublic,
			long spotCount, Long coverSpotId, Long coverPhotoId, Instant createdAt, Instant updatedAt,
			boolean containsSpot) {
	}

	private static final String SELECT = """
			SELECT l.id, l.user_id, u.username, l.title, l.description, l.is_public, l.created_at, l.updated_at,
			       (SELECT COUNT(*) FROM spot_list_item i WHERE i.list_id = l.id) AS spot_count,
			       cover.spot_id AS cover_spot_id, cover.photo_id AS cover_photo_id,
			       EXISTS (SELECT 1 FROM spot_list_item c WHERE c.list_id = l.id AND c.spot_id = :containsSpot)
			           AS contains_spot
			FROM spot_list l
			JOIN app_user u ON u.id = l.user_id
			LEFT JOIN LATERAL (
			    SELECT i.spot_id, p.id AS photo_id
			    FROM spot_list_item i
			    JOIN spot s ON s.id = i.spot_id
			    JOIN LATERAL (
			        SELECT p.id FROM spot_photo p WHERE p.spot_id = s.id
			        ORDER BY CASE WHEN p.id = s.cover_photo_id THEN 0 ELSE 1 END, p.created_at, p.id LIMIT 1
			    ) p ON true
			    WHERE i.list_id = l.id
			    ORDER BY i.added_at, i.spot_id
			    LIMIT 1
			) cover ON true
			""";

	private static final RowMapper<StoredList> MAPPER = (rs, n) -> new StoredList(rs.getLong("id"),
			rs.getLong("user_id"), rs.getString("username"), rs.getString("title"), rs.getString("description"),
			rs.getBoolean("is_public"), rs.getLong("spot_count"), (Long) rs.getObject("cover_spot_id", Long.class),
			(Long) rs.getObject("cover_photo_id", Long.class), rs.getTimestamp("created_at").toInstant(),
			rs.getTimestamp("updated_at").toInstant(), rs.getBoolean("contains_spot"));

	private final JdbcClient jdbc;

	ListRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** {@code containsSpot}: für „Zu Liste hinzufügen“ markieren, ob diese Bude schon drin ist (-1 = egal). */
	List<StoredList> findByOwner(long userId, long containsSpot) {
		return jdbc.sql(SELECT + "WHERE l.user_id = :userId ORDER BY l.updated_at DESC, l.id DESC")
				.param("userId", userId)
				.param("containsSpot", containsSpot)
				.query(MAPPER)
				.list();
	}

	Optional<StoredList> find(long id) {
		return jdbc.sql(SELECT + "WHERE l.id = :id")
				.param("id", id)
				.param("containsSpot", -1L)
				.query(MAPPER)
				.optional();
	}

	long countByOwner(long userId) {
		return jdbc.sql("SELECT COUNT(*) FROM spot_list WHERE user_id = :userId")
				.param("userId", userId)
				.query(Long.class)
				.single();
	}

	long create(long userId, String title, String description, boolean isPublic) {
		return jdbc.sql("""
				INSERT INTO spot_list (user_id, title, description, is_public)
				VALUES (:userId, :title, :description, :isPublic)
				RETURNING id
				""")
				.param("userId", userId)
				.param("title", title)
				.param("description", description)
				.param("isPublic", isPublic)
				.query(Long.class)
				.single();
	}

	void update(long id, String title, String description, boolean isPublic) {
		jdbc.sql("""
				UPDATE spot_list SET title = :title, description = :description, is_public = :isPublic,
				                     updated_at = CURRENT_TIMESTAMP
				WHERE id = :id
				""")
				.param("id", id)
				.param("title", title)
				.param("description", description)
				.param("isPublic", isPublic)
				.update();
	}

	void delete(long id) {
		jdbc.sql("DELETE FROM spot_list WHERE id = :id").param("id", id).update();
	}

	/** Spot-IDs in der Reihenfolge, in der sie hinzugefügt wurden. */
	List<Long> spotIds(long listId) {
		return jdbc.sql("SELECT spot_id FROM spot_list_item WHERE list_id = :listId ORDER BY added_at, spot_id")
				.param("listId", listId)
				.query(Long.class)
				.list();
	}

	/** Fügt hinzu (schon enthalten: nichts passiert) und setzt den Änderungszeitpunkt der Liste. */
	void addSpot(long listId, long spotId) {
		int added = jdbc.sql("""
				INSERT INTO spot_list_item (list_id, spot_id) VALUES (:listId, :spotId)
				ON CONFLICT (list_id, spot_id) DO NOTHING
				""")
				.param("listId", listId)
				.param("spotId", spotId)
				.update();
		if (added > 0) {
			touch(listId);
		}
	}

	void removeSpot(long listId, long spotId) {
		if (jdbc.sql("DELETE FROM spot_list_item WHERE list_id = :listId AND spot_id = :spotId")
				.param("listId", listId)
				.param("spotId", spotId)
				.update() > 0) {
			touch(listId);
		}
	}

	private void touch(long listId) {
		jdbc.sql("UPDATE spot_list SET updated_at = CURRENT_TIMESTAMP WHERE id = :id").param("id", listId).update();
	}
}
