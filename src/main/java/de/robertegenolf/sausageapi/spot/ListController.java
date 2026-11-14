package de.robertegenolf.sausageapi.spot;

import de.robertegenolf.sausageapi.spot.SpotDtos.SpotSummary;
import de.robertegenolf.sausageapi.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Eigene Listen. Private Listen sieht nur der Besitzer; öffentliche jeder mit dem Link (sie sind über „Melden“ mit
 * {@code targetType: LIST} meldbar). Nicht öffentliche und unbekannte Listen liefern gleichermaßen 404.
 */
@RestController
@RequestMapping("/api")
class ListController {

	/** {@code isPublic} fehlt = privat. */
	record ListRequest(@NotBlank @Size(max = 100) String title, @Size(max = 500) String description,
			Boolean isPublic) {

		boolean visible() {
			return Boolean.TRUE.equals(isPublic);
		}
	}

	/** {@code containsSpot}: nur bei {@code GET /users/me/lists?spotId=…} gesetzt. */
	record ListSummary(long id, String title, String description, boolean isPublic, String owner, long spotCount,
			String coverPhotoUrl, Instant createdAt, Instant updatedAt, boolean containsSpot) {
	}

	record ListDetail(long id, String title, String description, boolean isPublic, String owner, boolean mine,
			Instant createdAt, Instant updatedAt, List<SpotSummary> spots) {
	}

	private final ListRepository lists;
	private final SpotRepository spots;
	private final PhotoRepository photos;
	private final UserRepository users;

	ListController(ListRepository lists, SpotRepository spots, PhotoRepository photos, UserRepository users) {
		this.lists = lists;
		this.spots = spots;
		this.photos = photos;
		this.users = users;
	}

	@GetMapping("/users/me/lists")
	List<ListSummary> myLists(@RequestParam(required = false) Long spotId, Authentication auth) {
		return lists.findByOwner(currentUserId(auth), spotId == null ? -1 : spotId).stream()
				.map(this::summary)
				.toList();
	}

	@PostMapping("/users/me/lists")
	ResponseEntity<ListSummary> create(@Valid @RequestBody ListRequest request, Authentication auth) {
		long userId = currentUserId(auth);
		if (lists.countByOwner(userId) >= ListRepository.MAX_LISTS_PER_USER) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"Du hast schon " + ListRepository.MAX_LISTS_PER_USER + " Listen – lösche eine, um eine neue anzulegen");
		}
		long id = lists.create(userId, request.title().trim(), blankToNull(request.description()), request.visible());
		return ResponseEntity.created(URI.create("/api/lists/" + id)).body(summary(lists.find(id).orElseThrow()));
	}

	@GetMapping("/lists/{id}")
	ListDetail list(@PathVariable long id, Authentication auth) {
		long viewerId = viewerId(auth);
		ListRepository.StoredList list = lists.find(id)
				.filter(l -> l.isPublic() || l.ownerId() == viewerId)
				.orElseThrow(() -> notFound(id));
		List<Long> ids = lists.spotIds(id);
		Map<Long, SpotSummary> summaries = spots.findSummaries(ids, viewerId);
		return new ListDetail(list.id(), list.title(), list.description(), list.isPublic(), list.owner(),
				list.ownerId() == viewerId, list.createdAt(), list.updatedAt(),
				ids.stream().map(summaries::get).filter(Objects::nonNull).toList());
	}

	@Transactional
	@PutMapping("/lists/{id}")
	ListSummary update(@PathVariable long id, @Valid @RequestBody ListRequest request, Authentication auth) {
		requireOwnList(id, auth);
		lists.update(id, request.title().trim(), blankToNull(request.description()), request.visible());
		return summary(lists.find(id).orElseThrow());
	}

	@Transactional
	@DeleteMapping("/lists/{id}")
	ResponseEntity<Void> delete(@PathVariable long id, Authentication auth) {
		requireOwnList(id, auth);
		lists.delete(id);
		return ResponseEntity.noContent().build();
	}

	@Transactional
	@PutMapping("/lists/{id}/spots/{spotId}")
	ResponseEntity<Void> addSpot(@PathVariable long id, @PathVariable long spotId, Authentication auth) {
		ListRepository.StoredList list = requireOwnList(id, auth);
		if (!spots.spotExists(spotId)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Spot " + spotId + " nicht gefunden");
		}
		if (list.spotCount() >= ListRepository.MAX_SPOTS_PER_LIST) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"Eine Liste fasst höchstens " + ListRepository.MAX_SPOTS_PER_LIST + " Buden");
		}
		lists.addSpot(id, spotId);
		return ResponseEntity.noContent().build();
	}

	@Transactional
	@DeleteMapping("/lists/{id}/spots/{spotId}")
	ResponseEntity<Void> removeSpot(@PathVariable long id, @PathVariable long spotId, Authentication auth) {
		requireOwnList(id, auth);
		lists.removeSpot(id, spotId);
		return ResponseEntity.noContent().build();
	}

	private ListSummary summary(ListRepository.StoredList l) {
		String cover = l.coverPhotoId() == null ? null : photos.url(l.coverSpotId(), l.coverPhotoId());
		return new ListSummary(l.id(), l.title(), l.description(), l.isPublic(), l.owner(), l.spotCount(), cover,
				l.createdAt(), l.updatedAt(), l.containsSpot());
	}

	/** Nur der Besitzer darf ändern; fremde Listen verhalten sich wie nicht vorhanden. */
	private ListRepository.StoredList requireOwnList(long id, Authentication auth) {
		long userId = currentUserId(auth);
		return lists.find(id).filter(l -> l.ownerId() == userId).orElseThrow(() -> notFound(id));
	}

	private long viewerId(Authentication auth) {
		if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
			return -1;
		}
		return users.findByUsername(auth.getName()).map(UserRepository.StoredUser::id).orElse(-1L);
	}

	private long currentUserId(Authentication auth) {
		return users.findByUsername(auth.getName())
				.map(UserRepository.StoredUser::id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
	}

	private static String blankToNull(String text) {
		return text == null || text.isBlank() ? null : text.trim();
	}

	private static ResponseStatusException notFound(long id) {
		return new ResponseStatusException(HttpStatus.NOT_FOUND, "Liste " + id + " nicht gefunden");
	}
}
