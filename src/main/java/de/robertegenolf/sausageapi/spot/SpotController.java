package de.robertegenolf.sausageapi.spot;

import de.robertegenolf.sausageapi.spot.SpotDtos.Category;
import de.robertegenolf.sausageapi.spot.SpotDtos.CategoryRequest;
import de.robertegenolf.sausageapi.spot.SpotDtos.Comment;
import de.robertegenolf.sausageapi.spot.SpotDtos.CommentRequest;
import de.robertegenolf.sausageapi.spot.SpotDtos.NearbySpot;
import de.robertegenolf.sausageapi.spot.SpotDtos.Page;
import de.robertegenolf.sausageapi.spot.SpotDtos.RatingRequest;
import de.robertegenolf.sausageapi.spot.SpotDtos.SpotDetail;
import de.robertegenolf.sausageapi.spot.SpotDtos.SpotRequest;
import de.robertegenolf.sausageapi.spot.SpotDtos.SpotSummary;
import de.robertegenolf.sausageapi.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.List;

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

	@PostMapping("/categories")
	ResponseEntity<Category> createCategory(@Valid @RequestBody CategoryRequest request) {
		try {
			Category created = repository.createCategory(request);
			return ResponseEntity.created(URI.create("/api/categories/" + created.id())).body(created);
		}
		catch (DuplicateKeyException ex) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Kategorie " + request.code() + " existiert bereits");
		}
	}

	@GetMapping("/spots")
	Page<SpotSummary> spots(@RequestParam(required = false) String category,
			@RequestParam(required = false) @Size(max = 150) String q,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		String search = q == null || q.isBlank() ? null : q.trim();
		return Page.of(repository.findSpots(category, search, page, size), page, size,
				repository.countSpots(category, search));
	}

	@GetMapping("/users/me/spots")
	List<SpotSummary> mySpots(Authentication auth) {
		return repository.findSpotsByCreator(currentUserId(auth));
	}

	@GetMapping("/spots/nearby")
	List<NearbySpot> nearby(
			@RequestParam @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
			@RequestParam @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
			@RequestParam(defaultValue = "5") @DecimalMin("0.1") @DecimalMax("50") Double radiusKm,
			@RequestParam(required = false) String category) {
		return repository.findSpotsNear(latitude, longitude, radiusKm, category);
	}

	@GetMapping("/spots/{id}")
	SpotDetail spot(@PathVariable long id) {
		return repository.findSpot(id)
				.orElseThrow(() -> notFound(id));
	}

	@PostMapping("/spots")
	ResponseEntity<SpotDetail> createSpot(@Valid @RequestBody SpotRequest request, Authentication auth) {
		requireCategory(request.categoryCode());
		long id = repository.createSpot(request, currentUserId(auth));
		SpotDetail created = repository.findSpot(id).orElseThrow(() -> notFound(id));
		return ResponseEntity.created(URI.create("/api/spots/" + id)).body(created);
	}

	@Transactional
	@PutMapping("/spots/{id}")
	SpotDetail updateSpot(@PathVariable long id, @Valid @RequestBody SpotRequest request, Authentication auth) {
		requireSpotOwner(id, currentUserId(auth));
		requireCategory(request.categoryCode());
		repository.updateSpot(id, request);
		return repository.findSpot(id).orElseThrow(() -> notFound(id));
	}

	@Transactional
	@DeleteMapping("/spots/{id}")
	ResponseEntity<Void> deleteSpot(@PathVariable long id, Authentication auth) {
		if (isAdmin(auth)) {
			requireSpot(id);
		}
		else {
			requireSpotOwner(id, currentUserId(auth));
		}
		repository.deleteSpot(id);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/spots/{id}/ratings")
	ResponseEntity<Void> rate(@PathVariable long id, @Valid @RequestBody RatingRequest request,
			Authentication auth) {
		requireSpot(id);
		repository.upsertRating(id, currentUserId(auth), request.score());
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/spots/{id}/ratings/me")
	RatingRequest myRating(@PathVariable long id, Authentication auth) {
		requireSpot(id);
		return repository.findRating(id, currentUserId(auth))
				.map(RatingRequest::new)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
						"Spot " + id + " wurde noch nicht bewertet"));
	}

	@DeleteMapping("/spots/{id}/ratings/me")
	ResponseEntity<Void> deleteMyRating(@PathVariable long id, Authentication auth) {
		requireSpot(id);
		repository.deleteRating(id, currentUserId(auth));
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/spots/{id}/comments")
	List<Comment> comments(@PathVariable long id) {
		requireSpot(id);
		return repository.findComments(id);
	}

	@PostMapping("/spots/{id}/comments")
	ResponseEntity<Void> comment(@PathVariable long id, @Valid @RequestBody CommentRequest request,
			Authentication auth) {
		requireSpot(id);
		long commentId = repository.createComment(id, currentUserId(auth), request.text());
		return ResponseEntity.created(URI.create("/api/spots/" + id + "/comments/" + commentId)).build();
	}

	@Transactional
	@DeleteMapping("/spots/{id}/comments/{commentId}")
	ResponseEntity<Void> deleteComment(@PathVariable long id, @PathVariable long commentId, Authentication auth) {
		long authorId = repository.findCommentAuthor(id, commentId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
						"Kommentar " + commentId + " nicht gefunden"));
		if (!isAdmin(auth) && authorId != currentUserId(auth)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Nur eigene Kommentare dürfen gelöscht werden");
		}
		repository.deleteComment(commentId);
		return ResponseEntity.noContent().build();
	}

	private static boolean isAdmin(Authentication auth) {
		return auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
	}

	private void requireSpot(long id) {
		if (!repository.spotExists(id)) {
			throw notFound(id);
		}
	}

	private void requireSpotOwner(long id, long userId) {
		long ownerId = repository.findSpotOwner(id).orElseThrow(() -> notFound(id));
		if (ownerId != userId) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Nur der Ersteller darf den Spot ändern");
		}
	}

	private void requireCategory(String code) {
		if (!repository.categoryExists(code)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unbekannte Kategorie: " + code);
		}
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
