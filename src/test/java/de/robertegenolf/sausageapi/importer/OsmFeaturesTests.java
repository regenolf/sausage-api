package de.robertegenolf.sausageapi.importer;

import de.robertegenolf.sausageapi.importer.GeoJson.Feature;
import de.robertegenolf.sausageapi.importer.GeoJson.Geometry;
import de.robertegenolf.sausageapi.importer.OsmFeatures.Outcome;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/** Erkennung von Bratwurstbuden in OSM-Daten, ohne Datenbank. */
class OsmFeaturesTests {

	private static Feature point(String id, Map<String, Object> tags) {
		return new Feature("Feature", id, new Geometry("Point", List.of(11.03, 50.97)), tags);
	}

	@Test
	void erkenntBudenAnCuisineFoodTagUndName() {
		assertThat(OsmFeatures.classify(point("n1", Map.of("amenity", "fast_food", "name", "Imbiss Krause",
				"cuisine", "burger;sausage"))).outcome()).isEqualTo(Outcome.MATCH);
		assertThat(OsmFeatures.classify(point("n2", Map.of("amenity", "fast_food", "name", "Imbiss am Markt",
				"food:bratwurst", "yes"))).outcome()).isEqualTo(Outcome.MATCH);
		assertThat(OsmFeatures.classify(point("n3", Map.of("amenity", "fast_food",
				"name", "Original Thüringer Rostbratwurst"))).outcome()).isEqualTo(Outcome.MATCH);
		assertThat(OsmFeatures.classify(point("n4", Map.of("amenity", "restaurant",
				"name", "WÜRSTCHENBUDE"))).outcome()).isEqualTo(Outcome.MATCH);
		assertThat(OsmFeatures.classify(point("n5", Map.of("amenity", "fast_food", "name", "Rostbräter"))).outcome())
				.isEqualTo(Outcome.MATCH);
	}

	@Test
	void ignoriertAndereOrte() {
		assertThat(OsmFeatures.classify(point("n1", Map.of("amenity", "fast_food", "name", "Döner Palast",
				"cuisine", "kebab"))).outcome()).isEqualTo(Outcome.NO_MATCH);
		assertThat(OsmFeatures.classify(point("n2", Map.of("amenity", "fast_food", "name", "Imbiss",
				"food:bratwurst", "no"))).outcome()).isEqualTo(Outcome.NO_MATCH);
		// Wurst im Namen, aber eine Metzgerei ist (noch) keine Bude
		assertThat(OsmFeatures.classify(point("n3", Map.of("shop", "butcher", "name", "Wurst-Müller"))).outcome())
				.isEqualTo(Outcome.NO_MATCH);
		assertThat(OsmFeatures.classify(point("n4", Map.of("name", "Wurststand"))).outcome())
				.isEqualTo(Outcome.NO_MATCH);
		assertThat(OsmFeatures.classify(point("n5", Map.of("amenity", "fast_food", "cuisine", "sausage"))).outcome())
				.isEqualTo(Outcome.NO_NAME);
	}

	@Test
	void uebernimmtAdresseUndOeffnungszeiten() {
		var result = OsmFeatures.classify(point("n42", Map.of("amenity", "fast_food", "name", "  Bratwurststand  ",
				"addr:street", "Anger", "addr:housenumber", "1a", "addr:postcode", "99084", "addr:city", "Erfurt",
				"opening_hours", "Mo-Sa 10:00-18:00")));

		assertThat(result.outcome()).isEqualTo(Outcome.MATCH);
		var spot = result.candidate();
		assertThat(spot.externalId()).isEqualTo("node/42");
		assertThat(spot.name()).isEqualTo("Bratwurststand");
		assertThat(spot.street()).isEqualTo("Anger");
		assertThat(spot.houseNumber()).isEqualTo("1a");
		assertThat(spot.zipCode()).isEqualTo("99084");
		assertThat(spot.city()).isEqualTo("Erfurt");
		assertThat(spot.openingHours()).isEqualTo("Mo-Sa 10:00-18:00");
		assertThat(spot.latitude()).isEqualByComparingTo(new BigDecimal("50.970000"));
		assertThat(spot.longitude()).isEqualByComparingTo(new BigDecimal("11.030000"));
	}

	@Test
	void kuerztZuLangeWerte() {
		var result = OsmFeatures.classify(point("n1", Map.of("amenity", "fast_food", "name", "Wurst".repeat(40),
				"addr:postcode", "99084-12345")));

		assertThat(result.candidate().name()).hasSize(150);
		assertThat(result.candidate().zipCode()).isEqualTo("99084-1234");
	}

	@Test
	void uebersetztOsmiumIds() {
		assertThat(OsmFeatures.externalId("n123")).isEqualTo("node/123");
		assertThat(OsmFeatures.externalId("w123")).isEqualTo("way/123");
		assertThat(OsmFeatures.externalId("r7")).isEqualTo("relation/7");
		// Flächen: gerade = Way (ID * 2), ungerade = Relation (ID * 2 + 1)
		assertThat(OsmFeatures.externalId("a246")).isEqualTo("way/123");
		assertThat(OsmFeatures.externalId("a15")).isEqualTo("relation/7");
		assertThat(OsmFeatures.externalId("way/5")).isEqualTo("way/5");
		assertThat(OsmFeatures.externalId("x1")).isNull();
		assertThat(OsmFeatures.externalId("n")).isNull();
		assertThat(OsmFeatures.externalId(17)).isNull();
		assertThat(OsmFeatures.externalId(null)).isNull();
	}

	@Test
	void nimmtBeiFlaechenDenSchwerpunkt() {
		Object multiPolygon = List.of(List.of(List.of(List.of(11.0, 50.0), List.of(11.2, 50.0), List.of(11.2, 50.2),
				List.of(11.0, 50.2), List.of(11.0, 50.0))));
		assertThat(OsmFeatures.position(multiPolygon)).containsExactly(new double[] {11.1, 50.1}, offset(1e-9));

		Object polygon = List.of(List.of(List.of(10.0, 50.0), List.of(10.4, 50.0), List.of(10.2, 50.3), List.of(10.0, 50.0)));
		assertThat(OsmFeatures.position(polygon)).containsExactly(new double[] {10.2, 50.1}, offset(1e-9));
	}

	@Test
	void verwirftUngueltigeGeometrien() {
		Map<String, Object> tags = Map.of("amenity", "fast_food", "name", "Wurststand");
		assertThat(OsmFeatures.classify(new Feature("Feature", "n1", null, tags)).outcome()).isEqualTo(Outcome.INVALID);
		assertThat(OsmFeatures.classify(new Feature("Feature", "n1", new Geometry("Point", List.of(200, 50)), tags))
				.outcome()).isEqualTo(Outcome.INVALID);
		assertThat(OsmFeatures.classify(new Feature("Feature", "n1", new Geometry("Point", List.of("a", "b")), tags))
				.outcome()).isEqualTo(Outcome.INVALID);
		assertThat(OsmFeatures.classify(new Feature("Feature", "n1", new Geometry("Point", List.of()), tags))
				.outcome()).isEqualTo(Outcome.INVALID);
		assertThat(OsmFeatures.classify(new Feature("Feature", null, new Geometry("Point", List.of(11, 50)), tags))
				.outcome()).isEqualTo(Outcome.INVALID);
	}
}
