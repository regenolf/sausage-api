package de.robertegenolf.sausageapi.spot;

import de.robertegenolf.sausageapi.spot.SpotDtos.SpotPhoto;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
class PhotoRepository {

	record PhotoData(String contentType, byte[] data) {
	}

	private final JdbcClient jdbc;

	PhotoRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	long create(long spotId, long userId, String contentType, byte[] data) {
		return jdbc.sql("""
				INSERT INTO spot_photo (spot_id, user_id, content_type, size_bytes, data)
				VALUES (:spotId, :userId, :contentType, :size, :data)
				RETURNING id
				""")
				.param("spotId", spotId)
				.param("userId", userId)
				.param("contentType", contentType)
				.param("size", data.length)
				.param("data", data)
				.query(Long.class)
				.single();
	}

	List<SpotPhoto> findBySpot(long spotId) {
		return jdbc.sql("""
				SELECT p.id, p.spot_id, u.username, p.content_type, p.size_bytes, p.created_at
				FROM spot_photo p
				JOIN app_user u ON u.id = p.user_id
				WHERE p.spot_id = :spotId
				ORDER BY p.created_at, p.id
				""")
				.param("spotId", spotId)
				.query((rs, n) -> new SpotPhoto(rs.getLong("id"), url(spotId, rs.getLong("id")),
						rs.getString("username"), rs.getString("content_type"), rs.getInt("size_bytes"),
						rs.getTimestamp("created_at").toInstant()))
				.list();
	}

	Optional<PhotoData> findData(long spotId, long photoId) {
		return jdbc.sql("SELECT content_type, data FROM spot_photo WHERE id = :id AND spot_id = :spotId")
				.param("id", photoId)
				.param("spotId", spotId)
				.query((rs, n) -> new PhotoData(rs.getString("content_type"), rs.getBytes("data")))
				.optional();
	}

	/** Liefert die User-ID des Hochladenden, leer wenn es das Foto an diesem Spot nicht gibt. */
	Optional<Long> findUploader(long spotId, long photoId) {
		return jdbc.sql("SELECT user_id FROM spot_photo WHERE id = :id AND spot_id = :spotId")
				.param("id", photoId)
				.param("spotId", spotId)
				.query(Long.class)
				.optional();
	}

	long countBySpot(long spotId) {
		return jdbc.sql("SELECT COUNT(*) FROM spot_photo WHERE spot_id = :spotId")
				.param("spotId", spotId)
				.query(Long.class)
				.single();
	}

	static String url(long spotId, long photoId) {
		return "/api/spots/" + spotId + "/photos/" + photoId;
	}

	void delete(long photoId) {
		jdbc.sql("DELETE FROM spot_photo WHERE id = :id")
				.param("id", photoId)
				.update();
	}
}
