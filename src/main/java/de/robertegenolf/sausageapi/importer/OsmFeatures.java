package de.robertegenolf.sausageapi.importer;

import de.robertegenolf.sausageapi.importer.GeoJson.Feature;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Entscheidet, ob ein OSM-Objekt eine Bratwurstbude ist, und macht daraus einen Spot-Kandidaten.
 * <p>
 * Treffer sind Imbisse, Restaurants, Biergärten und Food-Courts, die laut {@code cuisine} oder {@code food:*}
 * Würste anbieten oder "Wurst"/"Würstchen"/"Rostbrat…" im Namen tragen. Fehltreffer sind möglich; deshalb
 * landen importierte Spots ungeprüft in der Prüfliste der Moderation.
 */
final class OsmFeatures {

	static final Set<String> AMENITIES = Set.of("fast_food", "restaurant", "biergarten", "food_court");

	static final Set<String> CUISINES = Set.of("sausage", "bratwurst", "currywurst", "hot_dog");

	static final Pattern NAME = Pattern.compile("w[uü]rst|rostbr[aä]t",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

	/** Spaltenlängen der Tabelle spot. */
	private static final int MAX_NAME = 150;
	private static final int MAX_STREET = 150;
	private static final int MAX_HOUSE_NUMBER = 20;
	private static final int MAX_ZIP_CODE = 10;
	private static final int MAX_CITY = 100;
	private static final int MAX_OPENING_HOURS = 500;

	enum Outcome {
		MATCH, NO_MATCH, NO_NAME, INVALID
	}

	/** {@code externalId} im Format der OSM-URLs: node/123, way/123, relation/123. */
	record Candidate(String externalId, String name, String street, String houseNumber, String zipCode, String city,
			BigDecimal latitude, BigDecimal longitude, String openingHours) {
	}

	/** {@code candidate} nur bei {@link Outcome#MATCH} gesetzt. */
	record Result(Outcome outcome, Candidate candidate) {

		static Result of(Outcome outcome) {
			return new Result(outcome, null);
		}
	}

	private OsmFeatures() {
	}

	static Result classify(Feature feature) {
		if (feature == null || feature.geometry() == null) {
			return Result.of(Outcome.INVALID);
		}
		String externalId = externalId(feature.id());
		double[] position = position(feature.geometry().coordinates());
		if (externalId == null || position == null) {
			return Result.of(Outcome.INVALID);
		}
		Map<String, Object> tags = feature.properties() == null ? Map.of() : feature.properties();
		String name = tag(tags, "name");
		if (!isSausageStand(tags, name)) {
			return Result.of(Outcome.NO_MATCH);
		}
		if (name == null) {
			return Result.of(Outcome.NO_NAME);
		}
		String city = tag(tags, "addr:city");
		return new Result(Outcome.MATCH, new Candidate(externalId,
				truncate(name, MAX_NAME),
				truncate(tag(tags, "addr:street"), MAX_STREET),
				truncate(tag(tags, "addr:housenumber"), MAX_HOUSE_NUMBER),
				truncate(tag(tags, "addr:postcode"), MAX_ZIP_CODE),
				truncate(city != null ? city : tag(tags, "addr:place"), MAX_CITY),
				coordinate(position[1]), coordinate(position[0]),
				truncate(tag(tags, "opening_hours"), MAX_OPENING_HOURS)));
	}

	static boolean isSausageStand(Map<String, Object> tags, String name) {
		String amenity = tag(tags, "amenity");
		// Set.of(...).contains(null) wirft eine NullPointerException
		if (amenity == null || !AMENITIES.contains(amenity)) {
			return false;
		}
		String cuisine = tag(tags, "cuisine");
		if (cuisine != null) {
			for (String value : cuisine.split(";")) {
				if (CUISINES.contains(value.trim().toLowerCase(Locale.ROOT))) {
					return true;
				}
			}
		}
		for (Map.Entry<String, Object> tag : tags.entrySet()) {
			if (tag.getKey().startsWith("food:")
					&& CUISINES.contains(tag.getKey().substring("food:".length()).toLowerCase(Locale.ROOT))
					&& tag.getValue() != null && !"no".equalsIgnoreCase(tag.getValue().toString().trim())) {
				return true;
			}
		}
		return name != null && NAME.matcher(name).find();
	}

	/**
	 * Übersetzt die IDs von {@code osmium export --add-unique-id=type_id}: n/w/r + OSM-ID, bei Flächen a + Flächen-ID
	 * (Way-ID * 2, Relation-ID * 2 + 1). Auch "node/123" usw. wird akzeptiert. Null bei unbekanntem Format.
	 */
	static String externalId(Object id) {
		if (id == null) {
			return null;
		}
		String value = id.toString().trim();
		if (value.matches("(node|way|relation)/[1-9][0-9]{0,18}")) {
			return value;
		}
		if (!value.matches("[nwra][1-9][0-9]{0,18}")) {
			return null;
		}
		long number;
		try {
			number = Long.parseLong(value.substring(1));
		}
		catch (NumberFormatException ex) {
			return null;
		}
		return switch (value.charAt(0)) {
			case 'n' -> "node/" + number;
			case 'w' -> "way/" + number;
			case 'r' -> "relation/" + number;
			default -> number % 2 == 0 ? "way/" + number / 2 : "relation/" + (number - 1) / 2;
		};
	}

	/**
	 * Position als [lon, lat]: der Punkt selbst, bei Linien und Flächen der Schwerpunkt der Eckpunkte des ersten
	 * (äußeren) Rings. Für Buden-Grundrisse ist das genau genug. Null bei ungültigen Koordinaten.
	 */
	static double[] position(Object coordinates) {
		List<double[]> points = firstRing(coordinates);
		if (points == null || points.isEmpty()) {
			return null;
		}
		if (points.size() > 1 && points.get(0)[0] == points.get(points.size() - 1)[0]
				&& points.get(0)[1] == points.get(points.size() - 1)[1]) {
			points = points.subList(0, points.size() - 1);
		}
		double lon = 0;
		double lat = 0;
		for (double[] point : points) {
			lon += point[0];
			lat += point[1];
		}
		lon /= points.size();
		lat /= points.size();
		if (Double.isNaN(lon) || Double.isNaN(lat) || lon < -180 || lon > 180 || lat < -90 || lat > 90) {
			return null;
		}
		return new double[] {lon, lat};
	}

	/** Steigt in den verschachtelten Listen bis zur ersten Liste von Punkten ab. */
	private static List<double[]> firstRing(Object coordinates) {
		if (!(coordinates instanceof List<?> list) || list.isEmpty()) {
			return null;
		}
		if (list.get(0) instanceof Number) {
			double[] point = point(list);
			return point == null ? null : List.of(point);
		}
		if (list.get(0) instanceof List<?> first && !first.isEmpty() && first.get(0) instanceof Number) {
			List<double[]> points = new ArrayList<>(list.size());
			for (Object element : list) {
				double[] point = element instanceof List<?> pair ? point(pair) : null;
				if (point == null) {
					return null;
				}
				points.add(point);
			}
			return points;
		}
		return firstRing(list.get(0));
	}

	private static double[] point(List<?> pair) {
		if (pair.size() < 2 || !(pair.get(0) instanceof Number lon) || !(pair.get(1) instanceof Number lat)) {
			return null;
		}
		return new double[] {lon.doubleValue(), lat.doubleValue()};
	}

	private static BigDecimal coordinate(double value) {
		return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
	}

	private static String tag(Map<String, Object> tags, String key) {
		Object value = tags.get(key);
		if (value == null) {
			return null;
		}
		String text = value.toString().trim();
		return text.isEmpty() ? null : text;
	}

	private static String truncate(String value, int max) {
		if (value == null || value.length() <= max) {
			return value;
		}
		return value.substring(0, max).trim();
	}
}
