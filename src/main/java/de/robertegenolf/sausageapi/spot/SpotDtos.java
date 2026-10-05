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

	public record SpotSummary(long id, String categoryCode, String name, String city,
			BigDecimal latitude, BigDecimal longitude, Double averageRating, long ratingCount) {
	}

	public record NearbySpot(long id, String categoryCode, String name, String city,
			BigDecimal latitude, BigDecimal longitude, Double averageRating, long ratingCount,
			double distanceKm) {
	}

	public record SpotDetail(long id, String categoryCode, String name, String description,
			String street, String houseNumber, String zipCode, String city,
			BigDecimal latitude, BigDecimal longitude, String openingHours,
			Double averageRating, long ratingCount, String createdBy, Instant createdAt, Instant updatedAt,
			List<SpotPhoto> photos) {

		SpotDetail withPhotos(List<SpotPhoto> photos) {
			return new SpotDetail(id, categoryCode, name, description, street, houseNumber, zipCode, city,
					latitude, longitude, openingHours, averageRating, ratingCount, createdBy, createdAt, updatedAt,
					photos);
		}
	}

	public record SpotPhoto(long id, String url, String uploadedBy, String contentType, int sizeBytes,
			Instant createdAt) {
	}

	/** Bewertung eines Users: Sterne plus optionaler Kommentar. {@code author} ist der Benutzername (= JWT-sub). */
	public record Rating(long id, int score, String comment, String author, String authorName, Instant createdAt) {
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

	public record RatingRequest(
			@NotNull @Min(1) @Max(5) Integer score,
			@Size(max = 2000) String comment) {
	}

	public record CommentRequest(
			@NotBlank @Size(max = 2000) String text) {
	}

	public record Comment(long id, long spotId, long userId, String username, String text, Instant createdAt) {
	}
}
