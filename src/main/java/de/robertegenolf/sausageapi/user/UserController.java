package de.robertegenolf.sausageapi.user;

import de.robertegenolf.sausageapi.security.LoginThrottle;
import de.robertegenolf.sausageapi.security.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
class UserController {

	public record RegisterRequest(
			@NotBlank @Size(min = 3, max = 50) @Pattern(regexp = UserRepository.USERNAME_PATTERN,
					message = UserRepository.USERNAME_MESSAGE) String username,
			@NotBlank @Email @Size(max = 255) String email,
			@NotBlank @Size(min = 8, max = 100) String password,
			/**
			 * Nutzungsbedingungen akzeptiert und Mindestalter (16) bestätigt. Noch optional, bis die App das Feld
			 * mitschickt; danach mit @NotNull zur Pflicht machen. {@code false} wird abgelehnt.
			 */
			@AssertTrue(message = UserRepository.TERMS_MESSAGE) Boolean acceptTerms) {
	}

	public record RegisteredUser(long id, String username) {
	}

	public record DeleteAccountRequest(@NotBlank String password) {
	}

	public record BlockRequest(@NotBlank String username) {
	}

	public record ChangePasswordRequest(
			@NotBlank String currentPassword,
			@NotBlank @Size(min = 8, max = 100) String newPassword) {
	}

	private final UserRepository repository;
	private final UserExportRepository exports;
	private final PasswordEncoder passwordEncoder;
	private final LoginThrottle throttle;
	private final TokenService tokens;
	private final UserBlockRepository blocks;
	private final String termsVersion;

	UserController(UserRepository repository, UserExportRepository exports, PasswordEncoder passwordEncoder,
			LoginThrottle throttle, TokenService tokens, UserBlockRepository blocks,
			@Value("${app.terms.version}") String termsVersion) {
		this.repository = repository;
		this.exports = exports;
		this.passwordEncoder = passwordEncoder;
		this.throttle = throttle;
		this.tokens = tokens;
		this.blocks = blocks;
		this.termsVersion = termsVersion;
	}

	@PostMapping
	ResponseEntity<RegisteredUser> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
		throttle.checkRegistration(http.getRemoteAddr());
		String username = UserRepository.normalizeUsername(request.username());
		if (UserRepository.isReservedUsername(username)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Dieser Benutzername ist reserviert");
		}
		try {
			long id = repository.create(username, request.email().trim(), passwordEncoder.encode(request.password()),
					Boolean.TRUE.equals(request.acceptTerms()) ? termsVersion : null);
			return ResponseEntity.created(URI.create("/api/users/" + id)).body(new RegisteredUser(id, username));
		}
		catch (DuplicateKeyException ex) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Benutzername oder E-Mail ist bereits vergeben");
		}
	}

	@GetMapping("/me")
	UserRepository.UserProfile me(Authentication auth) {
		return repository.findProfile(auth.getName(), termsVersion)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
	}

	/** Akzeptiert die aktuelle Version der Nutzungsbedingungen (z. B. nach einer Änderung). */
	@PostMapping("/me/terms")
	UserRepository.UserProfile acceptTerms(Authentication auth) {
		UserRepository.StoredUser user = repository.findByUsername(auth.getName())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
		repository.acceptTerms(user.id(), termsVersion);
		return me(auth);
	}

	@PutMapping("/me/password")
	/**
	 * Ändert das Passwort. Alle bisherigen Tokens (auch auf anderen Geräten) werden ungültig; die Antwort enthält ein
	 * neues Token, damit das aktuelle Gerät angemeldet bleibt.
	 */
	TokenService.TokenResponse changePassword(@Valid @RequestBody ChangePasswordRequest request, Authentication auth) {
		UserRepository.StoredUser user = repository.findByUsername(auth.getName())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
		if (!passwordEncoder.matches(request.currentPassword(), user.passwordHash())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Aktuelles Passwort ist falsch");
		}
		repository.updatePasswordHash(user.id(), passwordEncoder.encode(request.newPassword()));
		return tokens.issueResponse(repository.findByUsername(user.username()).orElseThrow());
	}

	/**
	 * Löscht den eigenen Account samt Bewertungen, Kommentaren und Fotos. Angelegte Spots bleiben
	 * erhalten, verlieren aber den Bezug zum Account. Zur Sicherheit muss das Passwort bestätigt werden.
	 */
	@DeleteMapping("/me")
	ResponseEntity<Void> deleteAccount(@Valid @RequestBody DeleteAccountRequest request, Authentication auth) {
		UserRepository.StoredUser user = repository.findByUsername(auth.getName())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
		if (!passwordEncoder.matches(request.password(), user.passwordHash())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Passwort ist falsch");
		}
		repository.delete(user.id());
		return ResponseEntity.noContent().build();
	}

	/** Alle zum eigenen Account gespeicherten Daten als JSON-Datei (Art. 15 und 20 DSGVO). */
	@GetMapping("/me/export")
	ResponseEntity<Map<String, Object>> export(Authentication auth) {
		UserRepository.StoredUser user = repository.findByUsername(auth.getName())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
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
}
