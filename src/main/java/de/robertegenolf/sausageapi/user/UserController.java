package de.robertegenolf.sausageapi.user;

import de.robertegenolf.sausageapi.security.LoginThrottle;
import de.robertegenolf.sausageapi.security.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
class UserController {

	public record RegisterRequest(
			@NotBlank @Size(min = 3, max = 50) @Pattern(regexp = UserRepository.USERNAME_PATTERN,
					message = UserRepository.USERNAME_MESSAGE) String username,
			@NotBlank @Email @Size(max = 255) String email,
			@NotBlank @Size(min = 8, max = 100) String password) {
	}

	public record RegisteredUser(long id, String username) {
	}

	public record DeleteAccountRequest(@NotBlank String password) {
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

	UserController(UserRepository repository, UserExportRepository exports, PasswordEncoder passwordEncoder,
			LoginThrottle throttle, TokenService tokens) {
		this.repository = repository;
		this.exports = exports;
		this.passwordEncoder = passwordEncoder;
		this.throttle = throttle;
		this.tokens = tokens;
	}

	@PostMapping
	ResponseEntity<RegisteredUser> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
		throttle.checkRegistration(http.getRemoteAddr());
		String username = UserRepository.normalizeUsername(request.username());
		if (UserRepository.isReservedUsername(username)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Dieser Benutzername ist reserviert");
		}
		try {
			long id = repository.create(username, request.email().trim(), passwordEncoder.encode(request.password()));
			return ResponseEntity.created(URI.create("/api/users/" + id)).body(new RegisteredUser(id, username));
		}
		catch (DuplicateKeyException ex) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Benutzername oder E-Mail ist bereits vergeben");
		}
	}

	@GetMapping("/me")
	UserRepository.UserProfile me(Authentication auth) {
		return repository.findProfile(auth.getName())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
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
}
