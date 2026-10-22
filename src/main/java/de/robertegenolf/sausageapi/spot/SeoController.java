package de.robertegenolf.sausageapi.spot;

import de.robertegenolf.sausageapi.spot.SpotDtos.SpotDetail;
import de.robertegenolf.sausageapi.spot.SpotDtos.SpotPhoto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Für Suchmaschinen und Link-Vorschauen (WhatsApp, Messenger, Social Media), die kein JavaScript ausführen:
 * nginx leitet deren Abrufe von /spots/{id} hierher und /sitemap.xml auf die Sitemap. Der Inhalt entspricht der
 * App-Seite (kein Cloaking): Name, Kategorie, Adresse, Bewertung, Beschreibung, Öffnungszeiten, Titelbild.
 */
@RestController
@RequestMapping("/api/seo")
class SeoController {

	private static final MediaType HTML = new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8);
	private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic();

	private final SpotRepository spots;
	private final PhotoRepository photos;
	private final ObjectMapper json;
	private final String publicUrl;

	SeoController(SpotRepository spots, PhotoRepository photos, ObjectMapper json,
			@Value("${app.public-url:}") String publicUrl) {
		this.spots = spots;
		this.photos = photos;
		this.json = json;
		this.publicUrl = publicUrl;
	}

	@GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
	ResponseEntity<String> sitemap() {
		String base = baseUrl();
		StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
				.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
		for (String page : List.of("/home", "/map", "/legal/terms", "/legal/privacy", "/legal/imprint")) {
			xml.append("  <url><loc>").append(base).append(page).append("</loc></url>\n");
		}
		for (SpotRepository.SitemapEntry entry : spots.findSitemapEntries()) {
			xml.append("  <url><loc>").append(base).append("/spots/").append(entry.id()).append("</loc><lastmod>")
					.append(DateTimeFormatter.ISO_INSTANT.format(entry.updatedAt())).append("</lastmod></url>\n");
		}
		xml.append("</urlset>\n");
		return ResponseEntity.ok().cacheControl(CACHE).body(xml.toString());
	}

	@GetMapping(value = "/spots/{id}", produces = "text/html")
	ResponseEntity<String> spot(@PathVariable long id) {
		SpotDetail spot = spots.findSpot(id).orElse(null);
		if (spot == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(HTML)
					.body(page("Bude nicht gefunden – Sausage", "Diese Bude gibt es nicht mehr.", null, null,
							"<h1>Bude nicht gefunden</h1>", null, true));
		}
		String base = baseUrl();
		String url = base + "/spots/" + spot.id();
		String category = categoryName(spot.categoryCode());
		List<SpotPhoto> spotPhotos = photos.findBySpot(spot.id(), -1);
		String image = spotPhotos.isEmpty() ? base + "/assets/icon/icon-512.png" : spotPhotos.get(0).url();
		String description = description(spot, category);

		StringBuilder body = new StringBuilder()
				.append("<h1>").append(esc(spot.name())).append("</h1>\n")
				.append("<p>").append(esc(category)).append("</p>\n")
				.append("<p>").append(esc(ratingText(spot))).append("</p>\n");
		String address = address(spot);
		if (!address.isEmpty()) {
			body.append("<p>Adresse: ").append(esc(address)).append("</p>\n");
		}
		if (spot.openingHours() != null) {
			body.append("<p>Öffnungszeiten: ").append(esc(spot.openingHours())).append("</p>\n");
		}
		if (spot.description() != null) {
			body.append("<p>").append(esc(spot.description())).append("</p>\n");
		}
		body.append("<p><a href=\"").append(esc(url)).append("\">In der Sausage-App ansehen</a></p>\n");

		return ResponseEntity.ok().contentType(HTML).cacheControl(CACHE)
				.body(page(spot.name() + " – Sausage", description, url, image, body.toString(),
						structuredData(spot, url, image, category), false));
	}

	private String page(String title, String description, String url, String image, String body,
			String jsonLd, boolean noindex) {
		StringBuilder html = new StringBuilder("<!DOCTYPE html>\n<html lang=\"de\">\n<head>\n<meta charset=\"utf-8\">\n")
				.append("<title>").append(esc(title)).append("</title>\n")
				.append("<meta name=\"description\" content=\"").append(esc(description)).append("\">\n")
				.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
		if (noindex) {
			html.append("<meta name=\"robots\" content=\"noindex\">\n");
		}
		if (url != null) {
			html.append("<link rel=\"canonical\" href=\"").append(esc(url)).append("\">\n")
					.append("<meta property=\"og:url\" content=\"").append(esc(url)).append("\">\n");
		}
		html.append("<meta property=\"og:type\" content=\"website\">\n")
				.append("<meta property=\"og:site_name\" content=\"Sausage\">\n")
				.append("<meta property=\"og:locale\" content=\"de_DE\">\n")
				.append("<meta property=\"og:title\" content=\"").append(esc(title)).append("\">\n")
				.append("<meta property=\"og:description\" content=\"").append(esc(description)).append("\">\n");
		if (image != null) {
			html.append("<meta property=\"og:image\" content=\"").append(esc(image)).append("\">\n")
					.append("<meta name=\"twitter:card\" content=\"summary_large_image\">\n");
		}
		if (jsonLd != null) {
			html.append("<script type=\"application/ld+json\">").append(jsonLd).append("</script>\n");
		}
		return html.append("</head>\n<body>\n").append(body).append("</body>\n</html>\n").toString();
	}

	/** schema.org FoodEstablishment; "<" wird maskiert, damit Nutzertexte das Script-Tag nicht schließen. */
	private String structuredData(SpotDetail spot, String url, String image, String category) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("@context", "https://schema.org");
		data.put("@type", "FoodEstablishment");
		data.put("name", spot.name());
		data.put("url", url);
		data.put("image", image);
		data.put("servesCuisine", category);
		if (spot.description() != null) {
			data.put("description", spot.description());
		}
		Map<String, Object> address = new LinkedHashMap<>();
		address.put("@type", "PostalAddress");
		String street = String.join(" ", nonNull(spot.street(), spot.houseNumber()));
		if (!street.isEmpty()) {
			address.put("streetAddress", street);
		}
		if (spot.zipCode() != null) {
			address.put("postalCode", spot.zipCode());
		}
		if (spot.city() != null) {
			address.put("addressLocality", spot.city());
		}
		data.put("address", address);
		data.put("geo", Map.of("@type", "GeoCoordinates", "latitude", spot.latitude(), "longitude", spot.longitude()));
		if (spot.averageRating() != null && spot.ratingCount() > 0) {
			data.put("aggregateRating", Map.of("@type", "AggregateRating",
					"ratingValue", BigDecimal.valueOf(spot.averageRating()).setScale(1, java.math.RoundingMode.HALF_UP),
					"ratingCount", spot.ratingCount(), "bestRating", 5, "worstRating", 1));
		}
		return json.writeValueAsString(data).replace("<", "\\u003c");
	}

	private String description(SpotDetail spot, String category) {
		String where = spot.city() == null ? "" : " in " + spot.city();
		return category + where + " – " + ratingText(spot) + ". Adresse, Öffnungszeiten, Fotos und Bewertungen auf Sausage.";
	}

	private static String ratingText(SpotDetail spot) {
		if (spot.averageRating() == null || spot.ratingCount() == 0) {
			return "Noch nicht bewertet";
		}
		return String.format(Locale.GERMANY, "%.1f von 5 Sternen (%d %s)", spot.averageRating(), spot.ratingCount(),
				spot.ratingCount() == 1 ? "Bewertung" : "Bewertungen");
	}

	private static String address(SpotDetail spot) {
		String street = String.join(" ", nonNull(spot.street(), spot.houseNumber()));
		String place = String.join(" ", nonNull(spot.zipCode(), spot.city()));
		return nonNull(street.isEmpty() ? null : street, place.isEmpty() ? null : place).stream()
				.collect(Collectors.joining(", "));
	}

	private String categoryName(String code) {
		return spots.findCategories().stream().filter(c -> c.code().equals(code)).map(SpotDtos.Category::name)
				.findFirst().orElse(code);
	}

	private static List<String> nonNull(String... values) {
		return java.util.Arrays.stream(values).filter(v -> v != null && !v.isBlank()).toList();
	}

	/** Basis wie bei den Foto-URLs: app.public-url oder Schema/Host der Anfrage (hinter Proxy aus X-Forwarded-*). */
	private String baseUrl() {
		if (!publicUrl.isBlank()) {
			return publicUrl.replaceAll("/+$", "");
		}
		return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
	}

	private static String esc(String text) {
		return HtmlUtils.htmlEscape(text, "UTF-8");
	}
}
