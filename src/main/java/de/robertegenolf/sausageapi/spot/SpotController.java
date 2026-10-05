package de.robertegenolf.sausageapi.spot;

import de.robertegenolf.sausageapi.spot.SpotDtos.Category;
import de.robertegenolf.sausageapi.spot.SpotDtos.Comment;
import de.robertegenolf.sausageapi.spot.SpotDtos.CommentRequest;
import de.robertegenolf.sausageapi.spot.SpotDtos.CreateSpotRequest;
import de.robertegenolf.sausageapi.spot.SpotDtos.RatingRequest;
import de.robertegenolf.sausageapi.spot.SpotDtos.SpotDetail;
import de.robertegenolf.sausageapi.spot.SpotDtos.SpotSummary;
import de.robertegenolf.sausageapi.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api")
class SpotController {

	private final SpotRepository repository;
	private final UserRepository users;

	SpotController(SpotRepository repository, UserRepository users) {
		this.repository = repository;
		this.users = users;
	}

	@GetMapping("/categories")
	List<Category> categories() {
		return repository.findCategories();
	}

	@GetMapping("/spots")
	List<SpotSummary> spots(@RequestParam(required = false) String category) {
		return repository.findSpots(category);
	}

	@GetMapping("/spots/nearby")
	List<SpotSummary> nearby(
			@RequestParam @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
			@RequestParam @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
			@RequestParam(defaultValue = "5") @DecimalMin("0.1") @DecimalMax("50") Double radiusKm) {
		return repository.findSpotsNear(latitude, longitude, radiusKm);
	}

	@GetMapping("/spots/{id}")
	SpotDetail spot(@PathVariable long id) {
		return repository.findSpot(id)
				.orElseThrow(() -> notFound(id));
	}

	@PostMapping("/spots")
	ResponseEntity<SpotDetail> createSpot(@Valid @RequestBody CreateSpotRequest request, Authentication auth) {
		if (!repository.categoryExists(request.categoryCode())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Unbekannte Kategorie: " + request.categoryCode());
		}
		long id = repository.createSpot(request, currentUserId(auth));
		SpotDetail created = repository.findSpot(id).orElseThrow(() -> notFound(id));
		return ResponseEntity.created(URI.create("/api/spots/" + id)).body(created);
	}

	@PostMapping("/spots/{id}/ratings")
	ResponseEntity<Void> rate(@PathVariable long id, @Valid @RequestBody RatingRequest request,
			Authentication auth) {
		if (!repository.spotExists(id)) {
			throw notFound(id);
		}
		repository.upsertRating(id, currentUserId(auth), request.score());
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/spots/{id}/comments")
	List<Comment> comments(@PathVariable long id) {
		if (!repository.spotExists(id)) {
			throw notFound(id);
		}
		return repository.findComments(id);
	}

	@PostMapping("/spots/{id}/comments")
	ResponseEntity<Void> comment(@PathVariable long id, @Valid @RequestBody CommentRequest request,
			Authentication auth) {
		if (!repository.spotExists(id)) {
			throw notFound(id);
		}
		long commentId = repository.createComment(id, currentUserId(auth), request.text());
		return ResponseEntity.created(URI.create("/api/spots/" + id + "/comments/" + commentId)).build();
	}

	private long currentUserId(Authentication auth) {
		return users.findByUsername(auth.getName())
				.map(UserRepository.StoredUser::id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
	}

	private static ResponseStatusException notFound(long id) {
		return new ResponseStatusException(HttpStatus.NOT_FOUND, "Spot " + id + " nicht gefunden");
	}
}
