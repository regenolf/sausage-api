package de.robertegenolf.sausageapi.spot;

import de.robertegenolf.sausageapi.spot.SpotDtos.SpotSummary;
import de.robertegenolf.sausageapi.user.UserRepository;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Wurst-Tagebuch: „Hier gegessen“ an der Bude, Liste der eigenen Einträge, Kennzahlen und Abzeichen. Alles privat –
 * andere Nutzer sehen das Tagebuch nicht.
 */
@RestController
@RequestMapping("/api")
class DiaryController {

	/** Eintrag mit der Bude ({@code spot} null, falls sie inzwischen gelöscht wurde – kommt praktisch nicht vor). */
	record CheckIn(long id, LocalDate eatenOn, Instant createdAt, SpotSummary spot) {
	}

	record Visits(long count, LocalDate lastEatenOn) {
	}

	record FavoriteSpot(SpotSummary spot, long count) {
	}

	record Stats(long checkIns, long spots, long cities, long ratings, long photos, long spotsCreated,
			FavoriteSpot favorite, List<Badges.Badge> badges) {
	}

	private final DiaryRepository diary;
	private final SpotRepository spots;
	private final UserRepository users;

	DiaryController(DiaryRepository diary, SpotRepository spots, UserRepository users) {
		this.diary = diary;
		this.spots = spots;
		this.users = users;
	}

	/** „Hier gegessen“ – 409, wenn es für heute schon einen Eintrag zu dieser Bude gibt. */
	@PostMapping("/spots/{id}/checkins")
	ResponseEntity<CheckIn> checkIn(@PathVariable long id, Authentication auth) {
		long userId = currentUserId(auth);
		if (!spots.spotExists(id)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Spot " + id + " nicht gefunden");
		}
		DiaryRepository.StoredCheckIn created = diary.create(userId, id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
						"Heute hast du hier schon eine Wurst eingetragen"));
		SpotSummary spot = spots.findSummaries(List.of(id), userId).get(id);
		return ResponseEntity.created(URI.create("/api/users/me/checkins/" + created.id()))
				.body(new CheckIn(created.id(), created.eatenOn(), created.createdAt(), spot));
	}

	@GetMapping("/spots/{id}/checkins/me")
	Visits myVisits(@PathVariable long id, Authentication auth) {
		DiaryRepository.SpotVisits visits = diary.visits(currentUserId(auth), id);
		return new Visits(visits.count(), visits.lastEatenOn());
	}

	@GetMapping("/users/me/checkins")
	List<CheckIn> myCheckIns(@RequestParam(defaultValue = "200") @Min(1) @Max(1000) int limit, Authentication auth) {
		long userId = currentUserId(auth);
		List<DiaryRepository.StoredCheckIn> entries = diary.findByUser(userId, limit);
		Map<Long, SpotSummary> summaries = spots.findSummaries(
				entries.stream().map(DiaryRepository.StoredCheckIn::spotId).toList(), userId);
		return entries.stream()
				.map(e -> new CheckIn(e.id(), e.eatenOn(), e.createdAt(), summaries.get(e.spotId())))
				.toList();
	}

	@DeleteMapping("/users/me/checkins/{checkInId}")
	ResponseEntity<Void> deleteCheckIn(@PathVariable long checkInId, Authentication auth) {
		if (!diary.delete(currentUserId(auth), checkInId)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Eintrag " + checkInId + " nicht gefunden");
		}
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/users/me/stats")
	Stats stats(Authentication auth) {
		long userId = currentUserId(auth);
		DiaryRepository.Counts counts = diary.counts(userId);
		FavoriteSpot favorite = diary.favorite(userId)
				.map(f -> new FavoriteSpot(spots.findSummaries(List.of(f.spotId()), userId).get(f.spotId()), f.count()))
				.filter(f -> f.spot() != null)
				.orElse(null);
		return new Stats(counts.checkIns(), counts.spots(), counts.cities(), counts.ratings(), counts.photos(),
				counts.spotsCreated(), favorite, Badges.of(counts));
	}

	private long currentUserId(Authentication auth) {
		return users.findByUsername(auth.getName())
				.map(UserRepository.StoredUser::id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
	}
}
