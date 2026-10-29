package de.robertegenolf.sausageapi.importer;

import java.util.List;
import java.util.Map;

/**
 * Minimales GeoJSON, wie es {@code osmium export --add-unique-id=type_id} liefert: {@code id} ist z. B. "n123"
 * (Node), "w123" (Way) oder "a246" (Fläche), {@code properties} sind die OSM-Tags.
 */
public final class GeoJson {

	private GeoJson() {
	}

	public record FeatureCollection(String type, List<Feature> features) {
	}

	/** {@code id} als Object, weil GeoJSON Zahl oder Text erlaubt. */
	public record Feature(String type, Object id, Geometry geometry, Map<String, Object> properties) {
	}

	/** {@code coordinates} je nach Typ verschachtelte Listen von Zahlen ([lon, lat]). */
	public record Geometry(String type, Object coordinates) {
	}
}
