package de.robertegenolf.sausageapi.spot;

import de.robertegenolf.sausageapi.spot.IssueRepository.IssueView;
import de.robertegenolf.sausageapi.spot.IssueRepository.Status;
import de.robertegenolf.sausageapi.spot.IssueRepository.Type;
import de.robertegenolf.sausageapi.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
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
import java.util.Locale;

/**
 * „Stimmt was nicht?“ an jeder Bude: angemeldete Nutzer weisen auf veraltete Angaben hin (geschlossen, falscher
 * Ort, falsche Öffnungszeiten …), die Moderation arbeitet die Hinweise unter {@code /api/admin/issues} ab.
 */
@RestController
@RequestMapping("/api")
class IssueController {

	record IssueRequest(@NotNull Type type, @Size(max = 1000) String message) {
	}

	/**
	 * {@code RESOLVED}: erledigt (Bude korrigiert oder gelöscht), {@code REJECTED}: unbegründet.
	 * {@code allForSpot}: alle offenen Hinweise zu dieser Bude gleich mit entscheiden.
	 */
	record DecisionRequest(@NotNull Status status, boolean allForSpot) {
	}

	private final IssueRepository issues;
	private final SpotRepository spots;
	private final UserRepository users;

	IssueController(IssueRepository issues, SpotRepository spots, UserRepository users) {
		this.issues = issues;
		this.spots = spots;
		this.users = users;
	}

	@PostMapping("/spots/{id}/issues")
	ResponseEntity<Void> report(@PathVariable long id, @Valid @RequestBody IssueRequest request, Authentication auth) {
		if (!spots.spotExists(id)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Spot " + id + " nicht gefunden");
		}
		long userId = currentUserId(auth);
		if (issues.countOpen(id, userId) >= IssueRepository.MAX_OPEN_PER_USER_AND_SPOT) {
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
					"Danke! Zu dieser Bude liegen schon Hinweise von dir vor – wir schauen sie uns an.");
		}
		String message = request.message() == null || request.message().isBlank() ? null : request.message().trim();
		long issueId = issues.create(id, userId, request.type(), message);
		return ResponseEntity.created(URI.create("/api/admin/issues/" + issueId)).build();
	}

	@GetMapping("/admin/issues")
	List<IssueView> adminIssues(@RequestParam(defaultValue = "OPEN") String status,
			@RequestParam(defaultValue = "200") @Min(1) @Max(500) int limit) {
		Status filter;
		try {
			filter = "ALL".equalsIgnoreCase(status) ? null : Status.valueOf(status.toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Unbekannter Status: " + status + " (erlaubt: OPEN, RESOLVED, REJECTED, ALL)");
		}
		return issues.find(filter, limit);
	}

	@Transactional
	@PutMapping("/admin/issues/{id}")
	ResponseEntity<Void> decide(@PathVariable long id, @Valid @RequestBody DecisionRequest request,
			Authentication auth) {
		if (request.status() == Status.OPEN) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status muss RESOLVED oder REJECTED sein");
		}
		Status current = issues.findStatus(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Hinweis " + id + " nicht gefunden"));
		if (current != Status.OPEN && !request.allForSpot()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Hinweis " + id + " ist bereits entschieden");
		}
		issues.decide(id, request.status(), currentUserId(auth), request.allForSpot());
		return ResponseEntity.noContent().build();
	}

	private long currentUserId(Authentication auth) {
		return users.findByUsername(auth.getName())
				.map(UserRepository.StoredUser::id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
	}
}
