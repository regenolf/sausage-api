package de.robertegenolf.sausageapi.spot;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

public final class SpotDtos {

	private SpotDtos() {
	}

	public record Category(long id, String code, String name, String description) {
	}

	public record SpotSummary(long id, String categoryCode, String name, String city,
			BigDecimal latitude, BigDecimal longitude, Double averageRating, long ratingCount) {
	}

	public record SpotDetail(long id, String categoryCode, String name, String description,
			String street, String houseNumber, String zipCode, String city,
			BigDecimal latitude, BigDecimal longitude, String openingHours,
			Double averageRating, long ratingCount, Instant createdAt, Instant updatedAt) {
	}

	public record CreateSpotRequest(
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
			@NotNull @Min(1) @Max(5) Integer score) {
	}

	public record CommentRequest(
			@NotBlank @Size(max = 2000) String text) {
	}

	public record Comment(long id, long spotId, long userId, String text, Instant createdAt) {
	}
}
