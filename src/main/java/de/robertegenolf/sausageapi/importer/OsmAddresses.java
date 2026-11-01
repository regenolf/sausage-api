package de.robertegenolf.sausageapi.importer;

import de.robertegenolf.sausageapi.importer.GeoJson.Feature;
import de.robertegenolf.sausageapi.importer.OsmFeatures.Candidate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Ergänzt fehlende Adressen von Buden aus Adresspunkten derselben OSM-Daten.
 * <p>
 * Buden und Kioske sind in OSM oft nur ein Punkt mit Namen, ohne eigene {@code addr:*}-Tags. Die Adresse steht
 * dann am Gebäude oder an einem Adresspunkt daneben. Das Import-Skript schickt deshalb zusätzlich die Adressen in
 * der Nähe der Kandidaten mit; hier wird für jede Bude ohne Straße die nächste Adresse im Umkreis von
 * {@link #ADDRESS_RADIUS_METERS} übernommen. Liegt keine so nah, wird wenigstens der Ort (und die PLZ) der
 * nächsten Adresse im Umkreis von {@link #CITY_RADIUS_METERS} gesetzt.
 * <p>
 * Die Daten stammen aus OSM wie die Bude selbst (gleiche Lizenz, keine externen Dienste).
 */
final class OsmAddresses {

	static final double ADDRESS_RADIUS_METERS = 50;

	static final double CITY_RADIUS_METERS = 500;

	/** Rasterweite des Suchindex in Grad (ca. 1,1 km Nord-Süd, in Deutschland ca. 0,7 km Ost-West). */
	private static final double CELL_DEGREES = 0.01;

	private static final double METERS_PER_DEGREE = 111_320;

	/** Adresspunkt; {@code city} ist {@code addr:city}, ersatzweise {@code addr:place}. */
	record Address(double latitude, double longitude, String street, String houseNumber, String zipCode,
			String city) {
	}

	/** Wie die Adresse einer Bude zustande kam. */
	enum Completion {
		NONE, ADDRESS, CITY
	}

	record Completed(Candidate candidate, Completion completion) {
	}

	private final Map<Long, List<Address>> cells = new HashMap<>();

	private int size;

	/**
	 * Adresspunkt aus einem GeoJSON-Feature: braucht eine Hausnummer und eine Straße oder einen Ort.
	 * Null, wenn das Feature keine Adresse ist.
	 */
	static Address parse(Feature feature) {
		if (feature == null || feature.geometry() == null || feature.properties() == null) {
			return null;
		}
		Map<String, Object> tags = feature.properties();
		String houseNumber = OsmFeatures.tag(tags, "addr:housenumber");
		String street = OsmFeatures.tag(tags, "addr:street");
		String city = OsmFeatures.tag(tags, "addr:city");
		if (city == null) {
			city = OsmFeatures.tag(tags, "addr:place");
		}
		if (houseNumber == null || (street == null && city == null)) {
			return null;
		}
		double[] position = OsmFeatures.position(feature.geometry().coordinates());
		if (position == null) {
			return null;
		}
		return new Address(position[1], position[0],
				OsmFeatures.truncate(street, OsmFeatures.MAX_STREET),
				OsmFeatures.truncate(houseNumber, OsmFeatures.MAX_HOUSE_NUMBER),
				OsmFeatures.truncate(OsmFeatures.tag(tags, "addr:postcode"), OsmFeatures.MAX_ZIP_CODE),
				OsmFeatures.truncate(city, OsmFeatures.MAX_CITY));
	}

	void add(Address address) {
		cells.computeIfAbsent(cell(cellIndex(address.latitude()), cellIndex(address.longitude())),
				key -> new ArrayList<>()).add(address);
		size++;
	}

	int size() {
		return size;
	}

	/**
	 * Füllt fehlende Felder der Bude. Vorhandene Werte aus OSM werden nie überschrieben. Ohne Straße wird die
	 * nächste Adresse mit Straße (≤ 50 m) übernommen, sonst nur Ort/PLZ der nächsten Adresse (≤ 500 m).
	 */
	Completed complete(Candidate c) {
		double lat = c.latitude().doubleValue();
		double lon = c.longitude().doubleValue();
		if (c.street() == null && c.houseNumber() == null) {
			Address near = nearest(lat, lon, ADDRESS_RADIUS_METERS, a -> a.street() != null);
			if (near != null) {
				return new Completed(new Candidate(c.externalId(), c.name(), near.street(), near.houseNumber(),
						c.zipCode() != null ? c.zipCode() : near.zipCode(),
						c.city() != null ? c.city() : near.city(),
						c.latitude(), c.longitude(), c.openingHours()), Completion.ADDRESS);
			}
		}
		if (c.city() == null) {
			Address near = nearest(lat, lon, CITY_RADIUS_METERS, a -> a.city() != null);
			if (near != null) {
				return new Completed(new Candidate(c.externalId(), c.name(), c.street(), c.houseNumber(),
						c.zipCode() != null ? c.zipCode() : near.zipCode(), near.city(),
						c.latitude(), c.longitude(), c.openingHours()), Completion.CITY);
			}
		}
		return new Completed(c, Completion.NONE);
	}

	/** Nächste Adresse im Umkreis, die {@code filter} erfüllt; null, wenn keine. */
	Address nearest(double lat, double lon, double radiusMeters, Predicate<Address> filter) {
		if (size == 0) {
			return null;
		}
		double cos = Math.max(Math.cos(Math.toRadians(lat)), 0.01);
		int rings = (int) Math.ceil(radiusMeters / (CELL_DEGREES * METERS_PER_DEGREE * cos));
		long row = cellIndex(lat);
		long col = cellIndex(lon);
		Address best = null;
		double bestDistance = radiusMeters;
		for (long r = row - rings; r <= row + rings; r++) {
			for (long k = col - rings; k <= col + rings; k++) {
				for (Address a : cells.getOrDefault(cell(r, k), List.of())) {
					double distance = distanceMeters(lat, lon, a.latitude(), a.longitude());
					if (distance <= bestDistance && filter.test(a)) {
						best = a;
						bestDistance = distance;
					}
				}
			}
		}
		return best;
	}

	/** Abstand in Metern (Näherung für kurze Strecken, genau genug für ≤ 1 km). */
	static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
		double x = (lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2));
		double y = lat2 - lat1;
		return Math.sqrt(x * x + y * y) * METERS_PER_DEGREE;
	}

	private static long cellIndex(double degrees) {
		return (long) Math.floor(degrees / CELL_DEGREES);
	}

	private static long cell(long row, long col) {
		return row * 100_000L + col;
	}
}
