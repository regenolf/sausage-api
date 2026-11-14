package de.robertegenolf.sausageapi.spot;

import de.robertegenolf.sausageapi.spot.SpotDtos.Category;
import de.robertegenolf.sausageapi.spot.SpotDtos.CategoryRequest;
import de.robertegenolf.sausageapi.spot.SpotDtos.Comment;
import de.robertegenolf.sausageapi.spot.SpotDtos.NearbySpot;
import de.robertegenolf.sausageapi.spot.SpotDtos.Rating;
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
			       s.source, s.verified, s.external_id, s.cover_photo_id AS chosen_cover_id,
			       AVG(r.score)::float8 AS average_rating,
			       COUNT(r.id) FILTER (WHERE r.score >= 4) AS recommend_count,
			       COUNT(r.id) AS rating_count
			FROM spot s
			JOIN category c ON c.id = s.category_id
			LEFT JOIN app_user u ON u.id = s.created_by
			LEFT JOIN rating r ON r.spot_id = s.id
			""";

	private static final String SPOT_GROUP_BY = "GROUP BY s.id, c.code, u.username\n";

	/**
	 * Für Listen: SPOT_SELECT plus Vorschaubild – das gewählte Titelbild, sonst das erste Foto (jeweils ohne Fotos
	 * von Nutzern, die :viewerId blockiert hat). Der LATERAL-Join liefert höchstens eine Zeile je Spot und
	 * vervielfacht die Bewertungen nicht.
	 */
	private static final String LIST_SELECT = SPOT_SELECT.replace("COUNT(r.id) AS rating_count",
			"COUNT(r.id) AS rating_count, cover.id AS cover_photo_id") + """
			LEFT JOIN LATERAL (
			    SELECT p.id FROM spot_photo p
			    WHERE p.spot_id = s.id AND %s
			    ORDER BY CASE WHEN p.id = s.cover_photo_id THEN 0 ELSE 1 END, p.created_at, p.id
			    LIMIT 1
			) cover ON true
			""".formatted(SpotRepository.NOT_BLOCKED.formatted("p.user_id"));

	private static final String LIST_GROUP_BY = "GROUP BY s.id, c.code, u.username, cover.id\n";

	/** Haversine-Distanz in km zwischen Spot (Tabellen-Alias als Platzhalter) und (:latitude, :longitude). */
	private static final String DISTANCE_KM = """
			6371 * 2 * asin(sqrt(
			        power(sin(radians(%1$s.latitude - :latitude) / 2), 2)
			        + cos(radians(:latitude)) * cos(radians(%1$s.latitude))
			        * power(sin(radians(%1$s.longitude - :longitude) / 2), 2)))""";

	private final RowMapper<SpotSummary> spotSummary = (rs, n) -> new SpotSummary(rs.getLong("id"),
			rs.getString("category_code"), rs.getString("name"), rs.getString("city"),
			rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"),
			averageRating(rs), rs.getLong("rating_count"), coverPhotoUrl(rs),
			rs.getString("source"), rs.getBoolean("verified"));

	private final RowMapper<NearbySpot> nearbySpot = (rs, n) -> new NearbySpot(rs.getLong("id"),
			rs.getString("category_code"), rs.getString("name"), rs.getString("city"),
			rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"),
			averageRating(rs), rs.getLong("rating_count"), rs.getDouble("distance_km"), coverPhotoUrl(rs),
			rs.getString("source"), rs.getBoolean("verified"));

	private static final RowMapper<SpotDetail> SPOT_DETAIL = (rs, n) -> new SpotDetail(rs.getLong("id"),
			rs.getString("category_code"), rs.getString("name"), rs.getString("description"),
			rs.getString("street"), rs.getString("house_number"),
			rs.getString("zip_code"), rs.getString("city"),
			rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"),
			rs.getString("opening_hours"),
			averageRating(rs), rs.getLong("rating_count"), rs.getLong("recommend_count"),
			rs.getString("created_by"),
			rs.getTimestamp("created_at").toInstant(),
			rs.getTimestamp("updated_at").toInstant(),
			rs.getString("source"), rs.getBoolean("verified"),
			SpotDtos.sourceUrl(rs.getString("source"), rs.getString("external_id")),
			nullableLong(rs, "chosen_cover_id"),
			List.of());

	private final JdbcClient jdbc;
	private final PhotoRepository photos;

	SpotRepository(JdbcClient jdbc, PhotoRepository photos) {
		this.jdbc = jdbc;
		this.photos = photos;
	}

	private String recentCover(java.sql.ResultSet rs) throws java.sql.SQLException {
		long photoId = rs.getLong("cover_photo_id");
		return rs.wasNull() ? null : photos.url(rs.getLong("spot_id"), photoId);
	}

	private String coverPhotoUrl(java.sql.ResultSet rs) throws java.sql.SQLException {
		long photoId = rs.getLong("cover_photo_id");
		return rs.wasNull() ? null : photos.url(rs.getLong("id"), photoId);
	}

	List<Category> findCategories() {
		return jdbc.sql("SELECT id, code, name, description FROM category ORDER BY name")
				.query((rs, n) -> new Category(rs.getLong("id"), rs.getString("code"),
						rs.getString("name"), rs.getString("description")))
				.list();
	}

	private static final String SPOT_FILTER = """
			WHERE (CAST(:categoryCode AS varchar) IS NULL OR c.code = :categoryCode)
			  AND (CAST(:source AS varchar) IS NULL OR s.source = :source)
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

	/** Sortierung der Spot-Liste. */
	enum SpotSort {
		/** alphabetisch (Standard) */
		NAME("s.name, s.id"),
		/** zuletzt eingetragen zuerst */
		NEWEST("s.created_at DESC, s.id DESC"),
		/**
		 * Beste zuerst, gedämpft durch zwei gedachte Bewertungen mit 3,5 Sternen: eine einzelne 5 schlägt nicht
		 * zehn 4,8er. Unbewertete ans Ende.
		 */
		RATING("COUNT(r.id) = 0, (COALESCE(SUM(r.score), 0) + 7.0) / (COUNT(r.id) + 2) DESC, COUNT(r.id) DESC, s.name, s.id");

		final String orderBy;

		SpotSort(String orderBy) {
			this.orderBy = orderBy;
		}
	}

	/**
	 * {@code limit == null} liefert alle Treffer; {@code source} filtert nach Herkunft (z. B. USER), null = alle;
	 * {@code viewerId} für das Vorschaubild (-1 = anonym).
	 */
	List<SpotSummary> findSpots(String categoryCode, String search, String source, SpotSort sort, Integer limit,
			long offset, long viewerId) {
		String sql = LIST_SELECT + SPOT_FILTER + LIST_GROUP_BY + "ORDER BY " + sort.orderBy + """

				LIMIT CAST(:limit AS integer) OFFSET :offset
				""";
		return jdbc.sql(sql)
				.param("categoryCode", categoryCode)
				.param("source", source)
				.param("search", escapeLike(search))
				.param("limit", limit)
				.param("offset", offset)
				.param("viewerId", viewerId)
				.query(spotSummary)
				.list();
	}

	List<SpotSummary> findSpotsByCreator(long userId) {
		String sql = LIST_SELECT + "WHERE s.created_by = :userId\n" + LIST_GROUP_BY + "ORDER BY s.created_at DESC, s.id DESC\n";
		return jdbc.sql(sql)
				.param("userId", userId)
				.param("viewerId", userId)
				.query(spotSummary)
				.list();
	}

	long countSpots(String categoryCode, String search, String source) {
		return jdbc.sql("""
				SELECT COUNT(*) FROM spot s
				JOIN category c ON c.id = s.category_id
				""" + SPOT_FILTER)
				.param("categoryCode", categoryCode)
				.param("search", escapeLike(search))
				.param("source", source)
				.query(Long.class)
				.single();
	}

	List<NearbySpot> findSpotsNear(double latitude, double longitude, double radiusKm, String categoryCode,
			long viewerId) {
		String sql = "SELECT spots.*, " + DISTANCE_KM.formatted("spots") + " AS distance_km\nFROM (\n"
				+ LIST_SELECT + """
				WHERE (CAST(:categoryCode AS varchar) IS NULL OR c.code = :categoryCode)
				  AND s.latitude BETWEEN :minLat AND :maxLat
				  AND s.longitude BETWEEN :minLon AND :maxLon
				  AND\s""" + DISTANCE_KM.formatted("s") + " <= :radiusKm\n" + LIST_GROUP_BY + """
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
				.param("viewerId", viewerId)
				.query(nearbySpot)
				.list();
	}

	/**
	 * Filter der Karte: optional eine Kategorie und die Schnellfilter der App („Top bewertet“ = Schnitt ab
	 * {@link #TOP_RATED_MIN} Sternen, „Mit Foto“, „Geprüft“). Gilt für einzelne Buden und Cluster gleichermaßen.
	 */
	record AreaFilter(String categoryCode, boolean topRated, boolean withPhoto, boolean verified) {
	}

	/** Ab diesem Schnitt gilt eine Bude als „top bewertet“ (wie in der App). */
	static final int TOP_RATED_MIN = 4;

	/**
	 * Bedingung für Buden im Ausschnitt (nutzt idx_spot_coordinates) plus {@link AreaFilter}. Bewertungen und Fotos
	 * als Unterabfragen, damit Zählung und Cluster ohne Join auskommen.
	 */
	private static final String AREA_FILTER = """
			WHERE (CAST(:categoryCode AS varchar) IS NULL OR c.code = :categoryCode)
			  AND s.latitude BETWEEN :minLat AND :maxLat
			  AND s.longitude BETWEEN :minLon AND :maxLon
			  AND (NOT CAST(:verifiedOnly AS boolean) OR s.verified IS TRUE)
			  AND (NOT CAST(:withPhoto AS boolean) OR EXISTS (SELECT 1 FROM spot_photo fp WHERE fp.spot_id = s.id))
			  AND (NOT CAST(:topRated AS boolean)
			       OR (SELECT AVG(fr.score) FROM rating fr WHERE fr.spot_id = s.id) >= %d)
			""".formatted(TOP_RATED_MIN);

	long countSpotsInArea(BoundingBox box, AreaFilter filter) {
		return jdbc.sql("SELECT COUNT(*) FROM spot s JOIN category c ON c.id = s.category_id\n" + AREA_FILTER)
				.params(areaParams(box, filter))
				.query(Long.class)
				.single();
	}

	/** Buden im Ausschnitt, bestbewertete zuerst – {@code limit} begrenzt nur zur Sicherheit. */
	List<SpotSummary> findSpotsInArea(BoundingBox box, AreaFilter filter, int limit, long viewerId) {
		String sql = LIST_SELECT + AREA_FILTER + LIST_GROUP_BY + "ORDER BY " + SpotSort.RATING.orderBy
				+ "\nLIMIT :limit\n";
		return jdbc.sql(sql)
				.params(areaParams(box, filter))
				.param("limit", limit)
				.param("viewerId", viewerId)
				.query(spotSummary)
				.list();
	}

	/**
	 * Fasst die Buden im Ausschnitt in einem Raster zusammen. Das Raster hängt an festen Koordinaten (nicht am
	 * Ausschnitt), damit Cluster beim Verschieben der Karte nicht springen; {@code cellLat}/{@code cellLon} in Grad.
	 */
	List<SpotDtos.SpotCluster> findClustersInArea(BoundingBox box, AreaFilter filter, double cellLat,
			double cellLon) {
		return jdbc.sql("""
				SELECT AVG(s.latitude)::float8 AS latitude, AVG(s.longitude)::float8 AS longitude, COUNT(*) AS count
				FROM spot s
				JOIN category c ON c.id = s.category_id
				""" + AREA_FILTER + """
				GROUP BY FLOOR(s.latitude / :cellLat), FLOOR(s.longitude / :cellLon)
				ORDER BY count DESC
				""")
				.params(areaParams(box, filter))
				.param("cellLat", BigDecimal.valueOf(cellLat))
				.param("cellLon", BigDecimal.valueOf(cellLon))
				.query((rs, n) -> new SpotDtos.SpotCluster(rs.getDouble("latitude"), rs.getDouble("longitude"),
						rs.getLong("count")))
				.list();
	}

	private static Map<String, Object> areaParams(BoundingBox box, AreaFilter filter) {
		Map<String, Object> params = new HashMap<>();
		params.put("categoryCode", filter.categoryCode());
		params.put("topRated", filter.topRated());
		params.put("withPhoto", filter.withPhoto());
		params.put("verifiedOnly", filter.verified());
		params.put("minLat", box.minLat());
		params.put("maxLat", box.maxLat());
		params.put("minLon", box.minLon());
		params.put("maxLon", box.maxLon());
		return params;
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

		/** Kartenausschnitt; Grenzen werden auf gültige Koordinaten beschnitten. */
		static BoundingBox of(double south, double west, double north, double east) {
			return new BoundingBox(decimal(Math.max(south, -90)), decimal(Math.min(north, 90)),
					decimal(Math.max(west, -180)), decimal(Math.min(east, 180)));
		}

		private static BigDecimal decimal(double value) {
			return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
		}
	}

	record SitemapEntry(long id, java.time.Instant updatedAt) {
	}

	/** Alle Spots für die Sitemap, zuletzt geänderte zuerst (Sitemaps erlauben bis 50.000 Einträge). */
	List<SitemapEntry> findSitemapEntries() {
		return jdbc.sql("SELECT id, updated_at FROM spot ORDER BY updated_at DESC, id LIMIT 50000")
				.query((rs, n) -> new SitemapEntry(rs.getLong("id"), rs.getTimestamp("updated_at").toInstant()))
				.list();
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

	/** Bearbeiten gilt als Prüfung: importierte Spots sind danach {@code verified} und werden vom Import nicht mehr überschrieben. */
	void updateSpot(long id, SpotRequest r) {
		jdbc.sql("""
				UPDATE spot SET
				    category_id = (SELECT id FROM category WHERE code = :categoryCode),
				    name = :name, description = :description, street = :street,
				    house_number = :houseNumber, zip_code = :zipCode, city = :city,
				    latitude = :latitude, longitude = :longitude, opening_hours = :openingHours,
				    verified = TRUE, updated_at = CURRENT_TIMESTAMP
				WHERE id = :id
				""").params(spotParams(r))
				.param("id", id)
				.update();
	}

	/** True, wenn andere als der Ersteller Bewertungen, Kommentare oder Fotos zu diesem Spot beigetragen haben. */
	boolean hasForeignContent(long spotId, long ownerId) {
		return jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM rating WHERE spot_id = :spotId AND user_id <> :ownerId)
				    OR EXISTS (SELECT 1 FROM comment WHERE spot_id = :spotId AND user_id <> :ownerId)
				    OR EXISTS (SELECT 1 FROM spot_photo WHERE spot_id = :spotId AND user_id <> :ownerId)
				""")
				.param("spotId", spotId)
				.param("ownerId", ownerId)
				.query(Boolean.class)
				.single();
	}

	/** Setzt das Titelbild ({@code null} = wieder das erste Foto). Das Foto muss zum Spot gehören. */
	void setCoverPhoto(long spotId, Long photoId) {
		jdbc.sql("UPDATE spot SET cover_photo_id = :photoId WHERE id = :id")
				.param("photoId", photoId)
				.param("id", spotId)
				.update();
	}

	/**
	 * Zuletzt abgegebene Bewertungen über alle Spots (Startseite), ohne die von Nutzern, die {@code viewerId}
	 * blockiert hat. {@code withComment}: nur Bewertungen mit Text.
	 */
	List<SpotDtos.RecentRating> findRecentRatings(int limit, boolean withComment, long viewerId) {
		return jdbc.sql("""
				SELECT r.id, r.score, r.comment, r.created_at, u.username,
				       s.id AS spot_id, s.name AS spot_name, s.city, c.code AS category_code,
				       cover.id AS cover_photo_id
				FROM rating r
				JOIN app_user u ON u.id = r.user_id
				JOIN spot s ON s.id = r.spot_id
				JOIN category c ON c.id = s.category_id
				LEFT JOIN LATERAL (
				    SELECT p.id FROM spot_photo p
				    WHERE p.spot_id = s.id AND %2$s
				    ORDER BY CASE WHEN p.id = s.cover_photo_id THEN 0 ELSE 1 END, p.created_at, p.id
				    LIMIT 1
				) cover ON true
				WHERE (NOT :withComment OR r.comment IS NOT NULL) AND %1$s
				ORDER BY r.created_at DESC, r.id DESC
				LIMIT :limit
				""".formatted(NOT_BLOCKED.formatted("r.user_id"), NOT_BLOCKED.formatted("p.user_id")))
				.param("withComment", withComment)
				.param("viewerId", viewerId)
				.param("limit", limit)
				.query((rs, n) -> new SpotDtos.RecentRating(rs.getLong("id"), rs.getInt("score"),
						rs.getString("comment"), rs.getString("username"), rs.getTimestamp("created_at").toInstant(),
						rs.getLong("spot_id"), rs.getString("spot_name"), rs.getString("city"),
						rs.getString("category_code"), recentCover(rs)))
				.list();
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

	/** SQL-Bedingung: Verfasser (Spalte als Platzhalter) ist nicht von :viewerId blockiert. */
	static final String NOT_BLOCKED = "NOT EXISTS (SELECT 1 FROM user_block b WHERE b.blocker_id = :viewerId AND b.blocked_id = %s)";

	private static final RowMapper<Rating> RATING = (rs, n) -> new Rating(rs.getLong("id"), rs.getInt("score"),
			rs.getString("comment"), rs.getString("username"), rs.getString("username"),
			rs.getTimestamp("created_at").toInstant());

	private static final String RATING_SELECT = """
			SELECT r.id, r.score, r.comment, u.username, r.created_at
			FROM rating r
			JOIN app_user u ON u.id = r.user_id
			""";

	void upsertRating(long spotId, long userId, int score, String comment) {
		jdbc.sql("""
				INSERT INTO rating (spot_id, user_id, score, comment)
				VALUES (:spotId, :userId, :score, :comment)
				ON CONFLICT (spot_id, user_id)
				DO UPDATE SET score = EXCLUDED.score, comment = EXCLUDED.comment, created_at = CURRENT_TIMESTAMP
				""")
				.param("spotId", spotId)
				.param("userId", userId)
				.param("score", score)
				.param("comment", comment)
				.update();
	}

	/** Bewertungen eines Spots ohne die von Nutzern, die {@code viewerId} blockiert hat (-1 = anonym). */
	List<Rating> findRatings(long spotId, long viewerId) {
		return jdbc.sql(RATING_SELECT + "WHERE r.spot_id = :spotId AND " + NOT_BLOCKED.formatted("r.user_id")
						+ "\nORDER BY r.created_at DESC, r.id DESC\n")
				.param("spotId", spotId)
				.param("viewerId", viewerId)
				.query(RATING)
				.list();
	}

	Optional<Rating> findRating(long spotId, long userId) {
		return jdbc.sql(RATING_SELECT + "WHERE r.spot_id = :spotId AND r.user_id = :userId\n")
				.param("spotId", spotId)
				.param("userId", userId)
				.query(RATING)
				.optional();
	}

	/** Liefert die User-ID des Verfassers, leer wenn es die Bewertung an diesem Spot nicht gibt. */
	Optional<Long> findRatingAuthor(long spotId, long ratingId) {
		return jdbc.sql("SELECT user_id FROM rating WHERE id = :id AND spot_id = :spotId")
				.param("id", ratingId)
				.param("spotId", spotId)
				.query(Long.class)
				.optional();
	}

	void updateRating(long ratingId, int score, String comment) {
		jdbc.sql("UPDATE rating SET score = :score, comment = :comment, created_at = CURRENT_TIMESTAMP WHERE id = :id")
				.param("score", score)
				.param("comment", comment)
				.param("id", ratingId)
				.update();
	}

	void deleteRatingById(long ratingId) {
		jdbc.sql("DELETE FROM rating WHERE id = :id")
				.param("id", ratingId)
				.update();
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

	/** Kommentare eines Spots ohne die von Nutzern, die {@code viewerId} blockiert hat (-1 = anonym). */
	List<Comment> findComments(long spotId, long viewerId) {
		return jdbc.sql("""
				SELECT c.id, c.spot_id, c.user_id, u.username, c.text, c.created_at
				FROM comment c
				JOIN app_user u ON u.id = c.user_id
				WHERE c.spot_id = :spotId AND %s
				ORDER BY c.created_at DESC, c.id DESC
				""".formatted(NOT_BLOCKED.formatted("c.user_id")))
				.param("spotId", spotId)
				.param("viewerId", viewerId)
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

	private static Long nullableLong(ResultSet rs, String column) throws SQLException {
		long value = rs.getLong(column);
		return rs.wasNull() ? null : value;
	}

	private static Double averageRating(ResultSet rs) throws SQLException {
		return rs.getObject("average_rating") == null ? null : rs.getDouble("average_rating");
	}
}
