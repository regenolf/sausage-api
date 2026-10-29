package de.robertegenolf.sausageapi.importer;

import de.robertegenolf.sausageapi.importer.OsmFeatures.Candidate;
import de.robertegenolf.sausageapi.spot.SpotDtos;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Repository
class ImportRepository {

	/** Bereits importierter Spot, für den Abgleich mit dem neuen Importstand. */
	record ExistingSpot(long id, long categoryId, boolean verified, String name, String street, String houseNumber,
			String zipCode, String city, BigDecimal latitude, BigDecimal longitude, String openingHours) {

		boolean sameAs(Candidate c) {
			return Objects.equals(name, c.name()) && Objects.equals(street, c.street())
					&& Objects.equals(houseNumber, c.houseNumber())
					&& Objects.equals(zipCode, c.zipCode()) && Objects.equals(city, c.city())
					&& Objects.equals(openingHours, c.openingHours())
					&& latitude.compareTo(c.latitude()) == 0 && longitude.compareTo(c.longitude()) == 0;
		}
	}

	/** Eintrag der Prüfliste für die Moderation. */
	public record UnverifiedSpot(long id, String categoryCode, String name, String street, String houseNumber,
			String zipCode, String city, BigDecimal latitude, BigDecimal longitude, String openingHours,
			String source, String externalId, String sourceUrl, Instant createdAt, Instant importedAt) {
	}

	private static final int CHUNK = 1000;

	private final JdbcClient jdbc;

	ImportRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Optional<Long> findCategoryId(String code) {
		return jdbc.sql("SELECT id FROM category WHERE code = :code")
				.param("code", code)
				.query(Long.class)
				.optional();
	}

	Map<String, ExistingSpot> findImported(String source) {
		Map<String, ExistingSpot> spots = new HashMap<>();
		jdbc.sql("""
				SELECT id, external_id, category_id, verified, name, street, house_number, zip_code, city,
				       latitude, longitude, opening_hours
				FROM spot WHERE source = :source
				""")
				.param("source", source)
				.query(rs -> {
					spots.put(rs.getString("external_id"), new ExistingSpot(rs.getLong("id"),
							rs.getLong("category_id"), rs.getBoolean("verified"), rs.getString("name"),
							rs.getString("street"), rs.getString("house_number"), rs.getString("zip_code"),
							rs.getString("city"), rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"),
							rs.getString("opening_hours")));
				});
		return spots;
	}

	Set<String> findRejected(String source) {
		return new HashSet<>(jdbc.sql("SELECT external_id FROM spot_import_rejection WHERE source = :source")
				.param("source", source)
				.query(String.class)
				.list());
	}

	void insert(String source, long categoryId, Candidate c, Timestamp now) {
		jdbc.sql("""
				INSERT INTO spot (category_id, name, street, house_number, zip_code, city, latitude, longitude,
				                  opening_hours, created_by, source, external_id, verified, imported_at,
				                  created_at, updated_at)
				VALUES (:categoryId, :name, :street, :houseNumber, :zipCode, :city, :latitude, :longitude,
				        :openingHours, NULL, :source, :externalId, FALSE, :now, :now, :now)
				""")
				.params(candidateParams(c))
				.param("categoryId", categoryId)
				.param("source", source)
				.param("externalId", c.externalId())
				.param("now", now)
				.update();
	}

	/** Übernimmt den neuen Stand der Quelle; nur für ungeprüfte Spots aufrufen. */
	void update(long id, Candidate c, Timestamp now) {
		jdbc.sql("""
				UPDATE spot SET name = :name, street = :street, house_number = :houseNumber, zip_code = :zipCode,
				       city = :city, latitude = :latitude, longitude = :longitude, opening_hours = :openingHours,
				       imported_at = :now, updated_at = :now
				WHERE id = :id AND verified = FALSE
				""")
				.params(candidateParams(c))
				.param("id", id)
				.param("now", now)
				.update();
	}

	/** Merkt sich für unveränderte und geprüfte Spots nur, dass sie im Import noch vorkommen. */
	void touch(List<Long> ids, Timestamp now) {
		for (int from = 0; from < ids.size(); from += CHUNK) {
			jdbc.sql("UPDATE spot SET imported_at = :now WHERE id IN (:ids)")
					.param("now", now)
					.param("ids", ids.subList(from, Math.min(from + CHUNK, ids.size())))
					.update();
		}
	}

	/** Ungeprüfte Spots, älteste zuerst; {@code source} null = alle Quellen. */
	List<UnverifiedSpot> findUnverified(String source, int limit) {
		return jdbc.sql("""
				SELECT s.id, c.code AS category_code, s.name, s.street, s.house_number, s.zip_code, s.city,
				       s.latitude, s.longitude, s.opening_hours, s.source, s.external_id, s.created_at, s.imported_at
				FROM spot s
				JOIN category c ON c.id = s.category_id
				WHERE s.verified = FALSE AND (CAST(:source AS varchar) IS NULL OR s.source = :source)
				ORDER BY s.created_at, s.id
				LIMIT :limit
				""")
				.param("source", source)
				.param("limit", limit)
				.query((rs, n) -> new UnverifiedSpot(rs.getLong("id"), rs.getString("category_code"),
						rs.getString("name"), rs.getString("street"), rs.getString("house_number"),
						rs.getString("zip_code"), rs.getString("city"), rs.getBigDecimal("latitude"),
						rs.getBigDecimal("longitude"), rs.getString("opening_hours"), rs.getString("source"),
						rs.getString("external_id"),
						SpotDtos.sourceUrl(rs.getString("source"), rs.getString("external_id")),
						rs.getTimestamp("created_at").toInstant(),
						rs.getTimestamp("imported_at") == null ? null : rs.getTimestamp("imported_at").toInstant()))
				.list();
	}

	long countUnverified(String source) {
		return jdbc.sql("""
				SELECT COUNT(*) FROM spot
				WHERE verified = FALSE AND (CAST(:source AS varchar) IS NULL OR source = :source)
				""")
				.param("source", source)
				.query(Long.class)
				.single();
	}

	/** True, wenn es den Spot gibt. */
	boolean verify(long id) {
		return jdbc.sql("UPDATE spot SET verified = TRUE WHERE id = :id")
				.param("id", id)
				.update() > 0;
	}

	private static Map<String, Object> candidateParams(Candidate c) {
		Map<String, Object> params = new HashMap<>();
		params.put("name", c.name());
		params.put("street", c.street());
		params.put("houseNumber", c.houseNumber());
		params.put("zipCode", c.zipCode());
		params.put("city", c.city());
		params.put("latitude", c.latitude());
		params.put("longitude", c.longitude());
		params.put("openingHours", c.openingHours());
		return params;
	}
}
