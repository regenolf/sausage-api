package de.robertegenolf.sausageapi.spot;

import de.robertegenolf.sausageapi.spot.SpotDtos.Category;
import de.robertegenolf.sausageapi.spot.SpotDtos.Comment;
import de.robertegenolf.sausageapi.spot.SpotDtos.CreateSpotRequest;
import de.robertegenolf.sausageapi.spot.SpotDtos.SpotDetail;
import de.robertegenolf.sausageapi.spot.SpotDtos.SpotSummary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
class SpotRepository {

	private static final String SPOT_SELECT = """
			SELECT s.id, c.code AS category_code, s.name, s.description, s.street, s.house_number,
			       s.zip_code, s.city, s.latitude, s.longitude, s.opening_hours,
			       s.created_at, s.updated_at,
			       AVG(r.score)::float8 AS average_rating,
			       COUNT(r.id) AS rating_count
			FROM spot s
			JOIN category c ON c.id = s.category_id
			LEFT JOIN rating r ON r.spot_id = s.id
			""";

	private final JdbcClient jdbc;

	SpotRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	List<Category> findCategories() {
		return jdbc.sql("SELECT id, code, name, description FROM category ORDER BY name")
				.query((rs, n) -> new Category(rs.getLong("id"), rs.getString("code"),
						rs.getString("name"), rs.getString("description")))
				.list();
	}

	List<SpotSummary> findSpots(String categoryCode) {
		String sql = SPOT_SELECT + """
				WHERE (CAST(:categoryCode AS varchar) IS NULL OR c.code = :categoryCode)
				GROUP BY s.id, c.code
				ORDER BY s.name
				""";
		return jdbc.sql(sql)
				.param("categoryCode", categoryCode)
				.query((rs, n) -> new SpotSummary(rs.getLong("id"), rs.getString("category_code"),
						rs.getString("name"), rs.getString("city"),
						rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"),
						rs.getObject("average_rating") == null ? null : rs.getDouble("average_rating"),
						rs.getLong("rating_count")))
				.list();
	}

	List<SpotSummary> findSpotsNear(double latitude, double longitude, double radiusKm) {
		String sql = SPOT_SELECT + """
				WHERE 6371 * 2 * asin(sqrt(
				        power(sin(radians(s.latitude - :latitude) / 2), 2)
				        + cos(radians(:latitude)) * cos(radians(s.latitude))
				        * power(sin(radians(s.longitude - :longitude) / 2), 2))) <= :radiusKm
				GROUP BY s.id, c.code
				ORDER BY s.name
				""";
		return jdbc.sql(sql)
				.param("latitude", latitude)
				.param("longitude", longitude)
				.param("radiusKm", radiusKm)
				.query((rs, n) -> new SpotSummary(rs.getLong("id"), rs.getString("category_code"),
						rs.getString("name"), rs.getString("city"),
						rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"),
						rs.getObject("average_rating") == null ? null : rs.getDouble("average_rating"),
						rs.getLong("rating_count")))
				.list();
	}

	Optional<SpotDetail> findSpot(long id) {
		String sql = SPOT_SELECT + """
				WHERE s.id = :id
				GROUP BY s.id, c.code
				""";
		return jdbc.sql(sql)
				.param("id", id)
				.query((rs, n) -> new SpotDetail(rs.getLong("id"), rs.getString("category_code"),
						rs.getString("name"), rs.getString("description"),
						rs.getString("street"), rs.getString("house_number"),
						rs.getString("zip_code"), rs.getString("city"),
						rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"),
						rs.getString("opening_hours"),
						rs.getObject("average_rating") == null ? null : rs.getDouble("average_rating"),
						rs.getLong("rating_count"),
						rs.getTimestamp("created_at").toInstant(),
						rs.getTimestamp("updated_at").toInstant()))
				.optional();
	}

	boolean categoryExists(String code) {
		return jdbc.sql("SELECT COUNT(*) FROM category WHERE code = :code")
				.param("code", code)
				.query(Long.class)
				.single() > 0;
	}

	long createSpot(CreateSpotRequest r, long createdBy) {
		return jdbc.sql("""
				INSERT INTO spot (category_id, name, description, street, house_number, zip_code, city,
				                  latitude, longitude, opening_hours, created_by)
				VALUES ((SELECT id FROM category WHERE code = :categoryCode),
				        :name, :description, :street, :houseNumber, :zipCode, :city,
				        :latitude, :longitude, :openingHours, :createdBy)
				RETURNING id
				""")
				.param("categoryCode", r.categoryCode())
				.param("name", r.name())
				.param("description", r.description())
				.param("street", r.street())
				.param("houseNumber", r.houseNumber())
				.param("zipCode", r.zipCode())
				.param("city", r.city())
				.param("latitude", r.latitude())
				.param("longitude", r.longitude())
				.param("openingHours", r.openingHours())
				.param("createdBy", createdBy)
				.query(Long.class)
				.single();
	}

	boolean spotExists(long spotId) {
		return jdbc.sql("SELECT COUNT(*) FROM spot WHERE id = :id")
				.param("id", spotId)
				.query(Long.class)
				.single() > 0;
	}

	void upsertRating(long spotId, long userId, int score) {
		jdbc.sql("""
				INSERT INTO rating (spot_id, user_id, score)
				VALUES (:spotId, :userId, :score)
				ON CONFLICT (spot_id, user_id) DO UPDATE SET score = EXCLUDED.score, created_at = CURRENT_TIMESTAMP
				""")
				.param("spotId", spotId)
				.param("userId", userId)
				.param("score", score)
				.update();
	}

	long createComment(long spotId, long userId, String text) {
		return jdbc.sql("""
				INSERT INTO comment (spot_id, user_id, text)
				VALUES (:spotId, :userId, :text)
				RETURNING id
				""")
				.param("spotId", spotId)
				.param("userId", userId)
				.param("text", text)
				.query(Long.class)
				.single();
	}

	List<Comment> findComments(long spotId) {
		return jdbc.sql("""
				SELECT id, spot_id, user_id, text, created_at
				FROM comment WHERE spot_id = :spotId ORDER BY created_at DESC
				""")
				.param("spotId", spotId)
				.query((rs, n) -> new Comment(rs.getLong("id"), rs.getLong("spot_id"),
						rs.getLong("user_id"), rs.getString("text"),
						rs.getTimestamp("created_at").toInstant()))
				.list();
	}
}
