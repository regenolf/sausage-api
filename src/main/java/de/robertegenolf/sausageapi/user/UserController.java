package de.robertegenolf.sausageapi.user;

import de.robertegenolf.sausageapi.security.KeycloakAdminClient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Eigenes Konto. Registrierung, Anmeldung, Passwort ändern und „Passwort vergessen“ macht Keycloak; der Account der
 * App entsteht beim ersten Request mit Token (siehe {@link AccountLinker}).
 */
@RestController
@RequestMapping("/api/users")
class UserController {

	public record BlockRequest(@NotBlank String username) {
	}

	private final UserRepository repository;
	private final UserExportRepository exports;
	private final UserBlockRepository blocks;
	private final KeycloakAdminClient keycloak;
	private final String termsVersion;

	UserController(UserRepository repository, UserExportRepository exports, UserBlockRepository blocks,
			KeycloakAdminClient keycloak, @Value("${app.terms.version}") String termsVersion) {
		this.repository = repository;
		this.exports = exports;
		this.blocks = blocks;
		this.keycloak = keycloak;
		this.termsVersion = termsVersion;
	}

	@GetMapping("/me")
	UserRepository.UserProfile me(Authentication auth) {
		return repository.findProfile(auth.getName(), termsVersion)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
	}

	/** Akzeptiert die aktuelle Version der Nutzungsbedingungen (beim ersten Login und nach Änderungen). */
	@PostMapping("/me/terms")
	UserRepository.UserProfile acceptTerms(Authentication auth) {
		repository.acceptTerms(currentUser(auth).id(), termsVersion);
		return me(auth);
	}

	/** Meldet auf allen Geräten ab (beendet alle Keycloak-Sitzungen). */
	@PostMapping("/me/logout-all")
	ResponseEntity<Void> logoutAll(Authentication auth) {
		keycloak.logoutUser(currentUser(auth).externalId());
		return ResponseEntity.noContent().build();
	}

	/**
	 * Löscht das Konto in Keycloak und in der App samt Bewertungen, Kommentaren und Fotos. Angelegte Spots bleiben
	 * erhalten, verlieren aber den Bezug zum Account. Auch für gesperrte Konten (Art. 17 DSGVO). Zuerst Keycloak:
	 * Schlägt das fehl, bleibt alles unverändert und der Nutzer kann es erneut versuchen.
	 */
	@DeleteMapping("/me")
	ResponseEntity<Void> deleteAccount(Authentication auth) {
		UserRepository.StoredUser user = anyUser(auth);
		keycloak.deleteUser(user.externalId());
		repository.delete(user);
		return ResponseEntity.noContent().build();
	}

	/** Alle zum eigenen Account gespeicherten Daten als JSON-Datei (Art. 15 und 20 DSGVO), auch für gesperrte Konten. */
	@GetMapping("/me/export")
	ResponseEntity<Map<String, Object>> export(Authentication auth) {
		UserRepository.StoredUser user = anyUser(auth);
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
						.filename("sausage-daten-" + user.username() + ".json", StandardCharsets.UTF_8).build().toString())
				.body(exports.export(user.id()));
	}

	/** Nutzer, deren Bewertungen, Kommentare und Fotos für mich ausgeblendet sind. */
	@GetMapping("/me/blocks")
	List<UserBlockRepository.BlockedUser> blocked(Authentication auth) {
		return blocks.findBlocked(currentUser(auth).id());
	}

	/** Blockiert einen Nutzer: seine Beiträge werden für mich ausgeblendet (er erfährt davon nichts). */
	@PostMapping("/me/blocks")
	ResponseEntity<Void> block(@Valid @RequestBody BlockRequest request, Authentication auth) {
		UserRepository.StoredUser me = currentUser(auth);
		long blockedId = blocks.findUserId(request.username().trim())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Benutzer " + request.username() + " nicht gefunden"));
		if (blockedId == me.id()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Man kann sich nicht selbst blockieren");
		}
		blocks.block(me.id(), blockedId);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/me/blocks/{username}")
	ResponseEntity<Void> unblock(@PathVariable String username, Authentication auth) {
		UserRepository.StoredUser me = currentUser(auth);
		blocks.findUserId(username).ifPresent(blockedId -> blocks.unblock(me.id(), blockedId));
		return ResponseEntity.noContent().build();
	}

	private UserRepository.StoredUser currentUser(Authentication auth) {
		return repository.findByUsername(auth.getName())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
	}

	private UserRepository.StoredUser anyUser(Authentication auth) {
		return repository.findByUsernameIncludingDisabled(auth.getName())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
	}
}
