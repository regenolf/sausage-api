package de.robertegenolf.sausageapi.spot;

import de.robertegenolf.sausageapi.spot.SpotDtos.Category;
import de.robertegenolf.sausageapi.spot.SpotDtos.CategoryRequest;
import de.robertegenolf.sausageapi.spot.SpotDtos.Comment;
import de.robertegenolf.sausageapi.spot.SpotDtos.NearbySpot;
import de.robertegenolf.sausageapi.spot.SpotDtos.SpotDetail;
import de.robertegenolf.sausageapi.spot.SpotDtos.SpotRequest;
import de.robertegenolf.sausageapi.spot.SpotDtos.SpotSummary;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
class SpotRepository {

	private static final String SPOT_SELECT = """
			SELECT s.id, c.code AS category_code, s.name, s.description, s.street, s.house_number,
			       s.zip_code, s.city, s.latitude, s.longitude, s.opening_hours,
			       u.username AS created_by, s.created_at, s.updated_at,
			       AVG(r.score)::float8 AS average_rating,
			       COUNT(r.id) AS rating_count
			FROM spot s
			JOIN category c ON c.id = s.category_id
			LEFT JOIN app_user u ON u.id = s.created_by
			LEFT JOIN rating r ON r.spot_id = s.id
			""";

	private static final String SPOT_GROUP_BY = "GROUP BY s.id, c.code, u.username\n";

	/** Haversine-Distanz in km zwischen Spot (Tabellen-Alias als Platzhalter) und (:latitude, :longitude). */
	private static final String DISTANCE_KM = """
			6371 * 2 * asin(sqrt(
			        power(sin(radians(%1$s.latitude - :latitude) / 2), 2)
			        + cos(radians(:latitude)) * cos(radians(%1$s.latitude))
			        * power(sin(radians(%1$s.longitude - :longitude) / 2), 2)))""";

	private static final RowMapper<SpotSummary> SPOT_SUMMARY = (rs, n) -> new SpotSummary(rs.getLong("id"),
			rs.getString("category_code"), rs.getString("name"), rs.getString("city"),
			rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"),
			averageRating(rs), rs.getLong("rating_count"));

	private static final RowMapper<NearbySpot> NEARBY_SPOT = (rs, n) -> new NearbySpot(rs.getLong("id"),
			rs.getString("category_code"), rs.getString("name"), rs.getString("city"),
			rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"),
			averageRating(rs), rs.getLong("rating_count"), rs.getDouble("distance_km"));

	private static final RowMapper<SpotDetail> SPOT_DETAIL = (rs, n) -> new SpotDetail(rs.getLong("id"),
			rs.getString("category_code"), rs.getString("name"), rs.getString("description"),
			rs.getString("street"), rs.getString("house_number"),
			rs.getString("zip_code"), rs.getString("city"),
			rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"),
			rs.getString("opening_hours"),
			averageRating(rs), rs.getLong("rating_count"),
			rs.getString("created_by"),
			rs.getTimestamp("created_at").toInstant(),
			rs.getTimestamp("updated_at").toInstant());

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

	private static final String SPOT_FILTER = """
			WHERE (CAST(:categoryCode AS varchar) IS NULL OR c.code = :categoryCode)
			  AND (CAST(:search AS varchar) IS NULL OR s.name ILIKE '%' || CAST(:search AS varchar) || '%'
			       OR s.city ILIKE '%' || CAST(:search AS varchar) || '%')
			""";

	Category createCategory(CategoryRequest r) {
		return jdbc.sql("""
				INSERT INTO category (code, name, description)
				VALUES (:code, :name, :description)
				RETURNING id, code, name, description
				""")
				.param("code", r.code())
				.param("name", r.name())
				.param("description", r.description())
				.query((rs, n) -> new Category(rs.getLong("id"), rs.getString("code"),
						rs.getString("name"), rs.getString("description")))
				.single();
	}

	List<SpotSummary> findSpots(String categoryCode, String search, int page, int size) {
		String sql = SPOT_SELECT + SPOT_FILTER + SPOT_GROUP_BY + """
				ORDER BY s.name, s.id
				LIMIT :limit OFFSET :offset
				""";
		return jdbc.sql(sql)
				.param("categoryCode", categoryCode)
				.param("search", escapeLike(search))
				.param("limit", size)
				.param("offset", (long) page * size)
				.query(SPOT_SUMMARY)
				.list();
	}

	List<SpotSummary> findSpotsByCreator(long userId) {
		String sql = SPOT_SELECT + "WHERE s.created_by = :userId\n" + SPOT_GROUP_BY + "ORDER BY s.created_at DESC, s.id DESC\n";
		return jdbc.sql(sql)
				.param("userId", userId)
				.query(SPOT_SUMMARY)
				.list();
	}

	long countSpots(String categoryCode, String search) {
		return jdbc.sql("""
				SELECT COUNT(*) FROM spot s
				JOIN category c ON c.id = s.category_id
				""" + SPOT_FILTER)
				.param("categoryCode", categoryCode)
				.param("search", escapeLike(search))
				.query(Long.class)
				.single();
	}

	List<NearbySpot> findSpotsNear(double latitude, double longitude, double radiusKm, String categoryCode) {
		String sql = "SELECT spots.*, " + DISTANCE_KM.formatted("spots") + " AS distance_km\nFROM (\n"
				+ SPOT_SELECT + """
				WHERE (CAST(:categoryCode AS varchar) IS NULL OR c.code = :categoryCode)
				  AND s.latitude BETWEEN :minLat AND :maxLat
				  AND s.longitude BETWEEN :minLon AND :maxLon
				  AND\s""" + DISTANCE_KM.formatted("s") + " <= :radiusKm\n" + SPOT_GROUP_BY + """
				) spots
				ORDER BY distance_km, name
				""";
		BoundingBox box = BoundingBox.around(latitude, longitude, radiusKm);
		return jdbc.sql(sql)
				.param("latitude", latitude)
				.param("longitude", longitude)
				.param("radiusKm", radiusKm)
				.param("minLat", box.minLat())
				.param("maxLat", box.maxLat())
				.param("minLon", box.minLon())
				.param("maxLon", box.maxLon())
				.param("categoryCode", categoryCode)
				.query(NEARBY_SPOT)
				.list();
	}

	/**
	 * Rechteck, das den Suchkreis sicher enthält. Damit kann Postgres den Koordinaten-Index nutzen,
	 * bevor die exakte Haversine-Distanz nur noch für die Kandidaten berechnet wird. Die Grenzen sind
	 * BigDecimal, damit der Vergleich mit den DECIMAL-Spalten ohne Typumwandlung (und damit mit Index) läuft.
	 */
	record BoundingBox(BigDecimal minLat, BigDecimal maxLat, BigDecimal minLon, BigDecimal maxLon) {

		/** Etwas weniger als die tatsächlichen ~111,2 km je Breitengrad, damit das Rechteck großzügig ist. */
		private static final double KM_PER_DEGREE = 110.0;

		static BoundingBox around(double latitude, double longitude, double radiusKm) {
			double dLat = radiusKm / KM_PER_DEGREE;
			double minLat = latitude - dLat;
			double maxLat = latitude + dLat;
			double minLon = -180;
			double maxLon = 180;
			if (minLat > -90 && maxLat < 90) {
				double dLon = radiusKm / (KM_PER_DEGREE * Math.cos(Math.toRadians(Math.max(Math.abs(minLat),
						Math.abs(maxLat)))));
				// über die Datumsgrenze hinweg einfach nicht nach Länge filtern
				if (longitude - dLon >= -180 && longitude + dLon <= 180) {
					minLon = longitude - dLon;
					maxLon = longitude + dLon;
				}
			}
			return new BoundingBox(decimal(Math.max(minLat, -90)), decimal(Math.min(maxLat, 90)),
					decimal(minLon), decimal(maxLon));
		}

		private static BigDecimal decimal(double value) {
			return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
		}
	}

	Optional<SpotDetail> findSpot(long id) {
		String sql = SPOT_SELECT + "WHERE s.id = :id\n" + SPOT_GROUP_BY;
		return jdbc.sql(sql)
				.param("id", id)
				.query(SPOT_DETAIL)
				.optional();
	}

	boolean categoryExists(String code) {
		return jdbc.sql("SELECT COUNT(*) FROM category WHERE code = :code")
				.param("code", code)
				.query(Long.class)
				.single() > 0;
	}

	long createSpot(SpotRequest r, long createdBy) {
		return jdbc.sql("""
				INSERT INTO spot (category_id, name, description, street, house_number, zip_code, city,
				                  latitude, longitude, opening_hours, created_by)
				VALUES ((SELECT id FROM category WHERE code = :categoryCode),
				        :name, :description, :street, :houseNumber, :zipCode, :city,
				        :latitude, :longitude, :openingHours, :createdBy)
				RETURNING id
				""").params(spotParams(r))
				.param("createdBy", createdBy)
				.query(Long.class)
				.single();
	}

	void updateSpot(long id, SpotRequest r) {
		jdbc.sql("""
				UPDATE spot SET
				    category_id = (SELECT id FROM category WHERE code = :categoryCode),
				    name = :name, description = :description, street = :street,
				    house_number = :houseNumber, zip_code = :zipCode, city = :city,
				    latitude = :latitude, longitude = :longitude, opening_hours = :openingHours,
				    updated_at = CURRENT_TIMESTAMP
				WHERE id = :id
				""").params(spotParams(r))
				.param("id", id)
				.update();
	}

	void deleteSpot(long id) {
		jdbc.sql("DELETE FROM spot WHERE id = :id")
				.param("id", id)
				.update();
	}

	/**
	 * Liefert die User-ID des Erstellers, leer wenn es den Spot nicht gibt. Spots gelöschter
	 * Accounts liefern {@link #NO_OWNER}, das zu keinem User passt.
	 */
	Optional<Long> findSpotOwner(long spotId) {
		return jdbc.sql("SELECT COALESCE(created_by, " + NO_OWNER + ") FROM spot WHERE id = :id")
				.param("id", spotId)
				.query(Long.class)
				.optional();
	}

	static final long NO_OWNER = -1;

	boolean spotExists(long spotId) {
		return jdbc.sql("SELECT EXISTS (SELECT 1 FROM spot WHERE id = :id)")
				.param("id", spotId)
				.query(Boolean.class)
				.single();
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

	Optional<Integer> findRating(long spotId, long userId) {
		return jdbc.sql("SELECT score FROM rating WHERE spot_id = :spotId AND user_id = :userId")
				.param("spotId", spotId)
				.param("userId", userId)
				.query(Integer.class)
				.optional();
	}

	boolean deleteRating(long spotId, long userId) {
		return jdbc.sql("DELETE FROM rating WHERE spot_id = :spotId AND user_id = :userId")
				.param("spotId", spotId)
				.param("userId", userId)
				.update() > 0;
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
				SELECT c.id, c.spot_id, c.user_id, u.username, c.text, c.created_at
				FROM comment c
				JOIN app_user u ON u.id = c.user_id
				WHERE c.spot_id = :spotId
				ORDER BY c.created_at DESC, c.id DESC
				""")
				.param("spotId", spotId)
				.query((rs, n) -> new Comment(rs.getLong("id"), rs.getLong("spot_id"),
						rs.getLong("user_id"), rs.getString("username"), rs.getString("text"),
						rs.getTimestamp("created_at").toInstant()))
				.list();
	}

	/** Liefert die User-ID des Autors, leer wenn es den Kommentar an diesem Spot nicht gibt. */
	Optional<Long> findCommentAuthor(long spotId, long commentId) {
		return jdbc.sql("SELECT user_id FROM comment WHERE id = :id AND spot_id = :spotId")
				.param("id", commentId)
				.param("spotId", spotId)
				.query(Long.class)
				.optional();
	}

	void deleteComment(long commentId) {
		jdbc.sql("DELETE FROM comment WHERE id = :id")
				.param("id", commentId)
				.update();
	}

	private static Map<String, Object> spotParams(SpotRequest r) {
		Map<String, Object> params = new HashMap<>();
		params.put("categoryCode", r.categoryCode());
		params.put("name", r.name());
		params.put("description", r.description());
		params.put("street", r.street());
		params.put("houseNumber", r.houseNumber());
		params.put("zipCode", r.zipCode());
		params.put("city", r.city());
		params.put("latitude", r.latitude());
		params.put("longitude", r.longitude());
		params.put("openingHours", r.openingHours());
		return params;
	}

	/** Maskiert LIKE-Platzhalter, damit z. B. "%" im Suchbegriff wörtlich gesucht wird. */
	private static String escapeLike(String search) {
		return search == null ? null
				: search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	private static Double averageRating(ResultSet rs) throws SQLException {
		return rs.getObject("average_rating") == null ? null : rs.getDouble("average_rating");
	}
}
