package de.robertegenolf.sausageapi.spot;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class SpotDtos {

	private SpotDtos() {
	}

	public record Category(long id, String code, String name, String description) {
	}

	public record CategoryRequest(
			@NotBlank @Size(max = 50) @Pattern(regexp = "[A-Z][A-Z0-9_]*",
					message = "nur Großbuchstaben, Ziffern und Unterstriche") String code,
			@NotBlank @Size(max = 100) String name,
			@Size(max = 500) String description) {
	}

	/**
	 * {@code coverPhotoUrl}: erstes Foto (ohne Fotos blockierter Nutzer) als Vorschaubild, null ohne Fotos.
	 * {@code source}: USER (in der App angelegt) oder Importquelle wie OSM (dann Quellenangabe anzeigen);
	 * {@code verified}: false bei importierten Spots, die die Moderation noch nicht geprüft hat.
	 * {@code openingHours}: Öffnungszeiten im OpenStreetMap-Format (z. B. „Mo-Fr 10:00-18:00“) oder Freitext – die
	 * App wertet sie für „Jetzt geöffnet“ aus.
	 */
	public record SpotSummary(long id, String categoryCode, String name, String city,
			BigDecimal latitude, BigDecimal longitude, Double averageRating, long ratingCount, String coverPhotoUrl,
			String source, boolean verified, String openingHours) {
	}

	/**
	 * Buden in einem Kartenausschnitt. {@code total}: alle Treffer im Ausschnitt. Bis zur angefragten Grenze stehen
	 * sie einzeln in {@code spots} ({@code clusters} leer), darüber nur als {@code clusters} ({@code spots} leer).
	 */
	public record SpotArea(long total, List<SpotSummary> spots, List<SpotCluster> clusters) {
	}

	/** Mehrere Buden einer Rasterzelle, Position = Mittelwert ihrer Koordinaten. */
	public record SpotCluster(double latitude, double longitude, long count) {
	}

	public record NearbySpot(long id, String categoryCode, String name, String city,
			BigDecimal latitude, BigDecimal longitude, Double averageRating, long ratingCount,
			double distanceKm, String coverPhotoUrl, String source, boolean verified, String openingHours) {
	}

	/**
	 * {@code source}/{@code verified} wie bei {@link SpotSummary}; {@code sourceUrl} verweist bei importierten Spots
	 * auf den Eintrag in der Quelle (z. B. https://www.openstreetmap.org/node/123), sonst null.
	 * {@code coverPhotoId}: vom Ersteller bzw. der Moderation gewähltes Titelbild, null = erstes Foto. {@code photos}
	 * beginnt immer mit dem Titelbild. {@code subScores}: Schnitt der optionalen Teilnoten; {@code priceCents}: typischer
	 * Preis einer Bratwurst (Median der Preisangaben aus Bewertungen der letzten zwei Jahre), null ohne Angaben;
	 * {@code priceCount}: Anzahl dieser Angaben. {@code openIssueCount}: offene „Stimmt was nicht?“-Hinweise.
	 */
	public record SpotDetail(long id, String categoryCode, String name, String description,
			String street, String houseNumber, String zipCode, String city,
			BigDecimal latitude, BigDecimal longitude, String openingHours,
			Double averageRating, long ratingCount, long recommendCount, String createdBy, Instant createdAt,
			Instant updatedAt, String source, boolean verified, String sourceUrl, Long coverPhotoId,
			SubScores subScores, Integer priceCents, long priceCount, long openIssueCount,
			List<SpotPhoto> photos) {

		SpotDetail withPhotos(List<SpotPhoto> photos) {
			return new SpotDetail(id, categoryCode, name, description, street, houseNumber, zipCode, city,
					latitude, longitude, openingHours, averageRating, ratingCount, recommendCount, createdBy, createdAt,
					updatedAt, source, verified, sourceUrl, coverPhotoId, subScores, priceCents, priceCount,
					openIssueCount, photos);
		}
	}

	/** Schnitt der Teilnoten (je 1–5, null ohne Angaben) und wie viele Bewertungen welche enthalten. */
	public record SubScores(Double sausage, Double bun, Double mustard, Double value, long count) {
	}

	/** Link auf den Eintrag in der Importquelle, null für in der App angelegte Spots. */
	public static String sourceUrl(String source, String externalId) {
		if (externalId == null) {
			return null;
		}
		return switch (source) {
			case "OSM" -> "https://www.openstreetmap.org/" + externalId;
			default -> null;
		};
	}

	public record SpotPhoto(long id, String url, String uploadedBy, String contentType, int sizeBytes,
			Instant createdAt) {
	}

	/** Titelbild wählen; {@code photoId == null} setzt es zurück (erstes Foto). */
	public record CoverRequest(Long photoId) {
	}

	/** Bewertung für die Startseite („Aus der Community“), mit der bewerteten Bude und deren Titelbild. */
	public record RecentRating(long id, int score, String comment, String author, Instant createdAt,
			long spotId, String spotName, String city, String categoryCode, String coverPhotoUrl) {
	}

	/**
	 * Bewertung eines Users: Sterne plus optionaler Kommentar, Teilnoten und Preis. {@code author} ist der
	 * Benutzername (= JWT-sub).
	 */
	public record Rating(long id, int score, String comment, String author, String authorName, Instant createdAt,
			Integer sausageScore, Integer bunScore, Integer mustardScore, Integer valueScore, Integer priceCents) {
	}

	/**
	 * Wird für Anlegen (POST) und vollständiges Bearbeiten (PUT) eines Spots verwendet.
	 */
	public record SpotRequest(
			@NotBlank String categoryCode,
			@NotBlank @Size(max = 150) String name,
			@Size(max = 2000) String description,
			@Size(max = 150) String street,
			@Size(max = 20) String houseNumber,
			@Size(max = 10) String zipCode,
			@Size(max = 100) String city,
			@NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
			@NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
			@Size(max = 500) String openingHours) {
	}

	/** Gesamtsterne Pflicht; Teilnoten (1–5) und Preis einer Bratwurst in Cent (0,50–30,00 €) optional. */
	public record RatingRequest(
			@NotNull @Min(1) @Max(5) Integer score,
			@Size(max = 2000) String comment,
			@Min(1) @Max(5) Integer sausageScore,
			@Min(1) @Max(5) Integer bunScore,
			@Min(1) @Max(5) Integer mustardScore,
			@Min(1) @Max(5) Integer valueScore,
			@Min(50) @Max(3000) Integer priceCents) {

		public RatingRequest(Integer score, String comment) {
			this(score, comment, null, null, null, null, null);
		}
	}

	public record CommentRequest(
			@NotBlank @Size(max = 2000) String text) {
	}

	public record Comment(long id, long spotId, long userId, String username, String text, Instant createdAt) {
	}
}
