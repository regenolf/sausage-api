package de.robertegenolf.sausageapi.importer;

import de.robertegenolf.sausageapi.importer.GeoJson.Feature;
import de.robertegenolf.sausageapi.importer.GeoJson.Geometry;
import de.robertegenolf.sausageapi.importer.OsmAddresses.Completion;
import de.robertegenolf.sausageapi.importer.OsmFeatures.Candidate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Ergänzen fehlender Adressen aus OSM-Adresspunkten, ohne Datenbank. */
class OsmAddressesTests {

	/** Krämerbrücke Erfurt */
	private static final double LAT = 50.978600;

	private static final double LON = 11.031500;

	/** Ca. 1 m Nord-Süd in Grad */
	private static final double METER = 1 / 111_320.0;

	private static Feature address(double lat, double lon, Map<String, Object> tags) {
		return new Feature("Feature", "n1", new Geometry("Point", List.of(lon, lat)), tags);
	}

	private static Candidate stand(String street, String houseNumber, String zipCode, String city) {
		return new Candidate("node/1", "Bratwurststand", street, houseNumber, zipCode, city,
				BigDecimal.valueOf(LAT), BigDecimal.valueOf(LON), null);
	}

	private static OsmAddresses index(Feature... features) {
		OsmAddresses addresses = new OsmAddresses();
		for (Feature f : features) {
			OsmAddresses.Address a = OsmAddresses.parse(f);
			assertThat(a).as("Adresspunkt %s", f.properties()).isNotNull();
			addresses.add(a);
		}
		return addresses;
	}

	@Test
	void erkenntAdresspunkte() {
		assertThat(OsmAddresses.parse(address(LAT, LON, Map.of("addr:street", "Krämerbrücke",
				"addr:housenumber", "5", "addr:postcode", "99084", "addr:city", "Erfurt"))))
				.isEqualTo(new OsmAddresses.Address(LAT, LON, "Krämerbrücke", "5", "99084", "Erfurt"));
		// Dorf ohne Straßennamen: addr:place statt addr:city
		assertThat(OsmAddresses.parse(address(LAT, LON, Map.of("addr:place", "Oberhof", "addr:housenumber", "3"))))
				.extracting(OsmAddresses.Address::city).isEqualTo("Oberhof");
		// ohne Hausnummer bzw. ohne Straße und Ort keine Adresse
		assertThat(OsmAddresses.parse(address(LAT, LON, Map.of("addr:street", "Anger")))).isNull();
		assertThat(OsmAddresses.parse(address(LAT, LON, Map.of("addr:housenumber", "1")))).isNull();
		assertThat(OsmAddresses.parse(address(LAT, LON, Map.of("amenity", "bench")))).isNull();
	}

	@Test
	void uebernimmtDieNaechsteAdresseImUmkreis() {
		OsmAddresses addresses = index(
				address(LAT + 40 * METER, LON, Map.of("addr:street", "Weit", "addr:housenumber", "9",
						"addr:postcode", "99084", "addr:city", "Erfurt")),
				address(LAT + 12 * METER, LON, Map.of("addr:street", "Krämerbrücke", "addr:housenumber", "5",
						"addr:postcode", "99084", "addr:city", "Erfurt")));

		var completed = addresses.complete(stand(null, null, null, null));

		assertThat(completed.completion()).isEqualTo(Completion.ADDRESS);
		assertThat(completed.candidate().street()).isEqualTo("Krämerbrücke");
		assertThat(completed.candidate().houseNumber()).isEqualTo("5");
		assertThat(completed.candidate().zipCode()).isEqualTo("99084");
		assertThat(completed.candidate().city()).isEqualTo("Erfurt");
		// Position und Name bleiben
		assertThat(completed.candidate().latitude()).isEqualByComparingTo(BigDecimal.valueOf(LAT));
		assertThat(completed.candidate().name()).isEqualTo("Bratwurststand");
	}

	@Test
	void ueberschreibtVorhandeneWerteNicht() {
		OsmAddresses addresses = index(address(LAT + 10 * METER, LON, Map.of("addr:street", "Anger",
				"addr:housenumber", "1", "addr:postcode", "99084", "addr:city", "Erfurt")));

		// Straße vorhanden: nichts zu tun
		var withStreet = addresses.complete(stand("Marktstraße", null, null, "Erfurt"));
		assertThat(withStreet.completion()).isEqualTo(Completion.NONE);
		assertThat(withStreet.candidate().street()).isEqualTo("Marktstraße");

		// Ort aus der Bude bleibt, Straße und PLZ kommen dazu
		var withCity = addresses.complete(stand(null, null, null, "Erfurt-Altstadt"));
		assertThat(withCity.completion()).isEqualTo(Completion.ADDRESS);
		assertThat(withCity.candidate().street()).isEqualTo("Anger");
		assertThat(withCity.candidate().city()).isEqualTo("Erfurt-Altstadt");
		assertThat(withCity.candidate().zipCode()).isEqualTo("99084");
	}

	@Test
	void setztNurDenOrtWennKeineAdresseNahGenugIst() {
		OsmAddresses addresses = index(address(LAT + 300 * METER, LON, Map.of("addr:street", "Anger",
				"addr:housenumber", "1", "addr:postcode", "99084", "addr:city", "Erfurt")));

		var completed = addresses.complete(stand(null, null, null, null));

		assertThat(completed.completion()).isEqualTo(Completion.CITY);
		assertThat(completed.candidate().street()).isNull();
		assertThat(completed.candidate().houseNumber()).isNull();
		assertThat(completed.candidate().zipCode()).isEqualTo("99084");
		assertThat(completed.candidate().city()).isEqualTo("Erfurt");
	}

	@Test
	void laesstBudenOhneAdresseInDerNaeheUnveraendert() {
		OsmAddresses addresses = index(address(LAT + 800 * METER, LON, Map.of("addr:street", "Anger",
				"addr:housenumber", "1", "addr:city", "Erfurt")));
		Candidate stand = stand(null, null, null, null);

		assertThat(addresses.complete(stand).completion()).isEqualTo(Completion.NONE);
		assertThat(addresses.complete(stand).candidate()).isEqualTo(stand);
		assertThat(new OsmAddresses().complete(stand).candidate()).isEqualTo(stand);
	}

	@Test
	void findetAdressenUeberZellgrenzenHinweg() {
		// Bude knapp nördlich einer Rasterlinie (0,01°), Adresse knapp südlich davon
		double lat = 50.970_100;
		OsmAddresses addresses = index(address(lat - 30 * METER, LON, Map.of("addr:street", "Süd",
				"addr:housenumber", "2", "addr:city", "Erfurt")));
		Candidate stand = new Candidate("node/2", "Wurst", null, null, null, null, BigDecimal.valueOf(lat),
				BigDecimal.valueOf(LON), null);

		assertThat(addresses.complete(stand).candidate().street()).isEqualTo("Süd");
	}

	@Test
	void berechnetAbstaendeInMetern() {
		assertThat(OsmAddresses.distanceMeters(LAT, LON, LAT + 100 * METER, LON)).isCloseTo(100, within(0.5));
		// 0,001° Länge sind auf 51° Breite gut 70 m
		assertThat(OsmAddresses.distanceMeters(LAT, LON, LAT, LON + 0.001)).isCloseTo(70.1, within(0.5));
	}
}
