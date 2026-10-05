package de.robertegenolf.sausageapi.security;

import de.robertegenolf.sausageapi.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;

/**
 * Anmeldung per JWT. Das Token wird danach als {@code Authorization: Bearer <token>} mitgeschickt.
 */
@RestController
@RequestMapping("/api/auth")
class TokenController {

	public record TokenResponse(String token, String accessToken, String tokenType, long expiresIn) {

		static TokenResponse of(TokenService.IssuedToken issued) {
			return new TokenResponse(issued.token(), issued.token(), "Bearer", issued.expiresIn());
		}
	}

	/** {@code email} darf auch der Benutzername sein. */
	public record LoginRequest(@NotBlank String email, @NotBlank String password) {
	}

	public record RegisterRequest(
			@NotBlank @Email @Size(max = 255) String email,
			@NotBlank @Size(min = 8, max = 100) String password,
			@NotBlank @Size(min = 3, max = 50) String displayName) {
	}

	private final TokenService tokens;
	private final UserRepository users;
	private final PasswordEncoder passwordEncoder;

	TokenController(TokenService tokens, UserRepository users, PasswordEncoder passwordEncoder) {
		this.tokens = tokens;
		this.users = users;
		this.passwordEncoder = passwordEncoder;
	}

	/** Tauscht HTTP-Basic-Login (oder ein noch gültiges Token) gegen ein neues JWT. */
	@PostMapping("/token")
	TokenResponse token(Authentication auth) {
		return TokenResponse.of(tokens.issue(currentUser(auth)));
	}

	/** Meldet auf allen Geräten ab: Alle bisher ausgestellten Tokens werden ungültig. */
	@PostMapping("/logout-all")
	ResponseEntity<Void> logoutAll(Authentication auth) {
		users.incrementTokenVersion(currentUser(auth).id());
		return ResponseEntity.noContent().build();
	}

	private UserRepository.StoredUser currentUser(Authentication auth) {
		return users.findByUsername(auth.getName())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
	}

	/** Anmeldung mit E-Mail (oder Benutzername) und Passwort im JSON-Body. */
	@PostMapping("/login")
	TokenResponse login(@Valid @RequestBody LoginRequest request) {
		UserRepository.StoredUser user = users.findByLogin(request.email().trim())
				.filter(u -> passwordEncoder.matches(request.password(), u.passwordHash()))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
						"E-Mail oder Passwort ist falsch"));
		return TokenResponse.of(tokens.issue(user));
	}

	/** Registriert einen Account (Anzeigename = Benutzername) und meldet direkt an. */
	@PostMapping("/register")
	ResponseEntity<TokenResponse> register(@Valid @RequestBody RegisterRequest request) {
		String username = request.displayName().trim();
		try {
			long id = users.create(username, request.email().trim(), passwordEncoder.encode(request.password()));
			UserRepository.StoredUser user = users.findByUsername(username).orElseThrow();
			return ResponseEntity.created(URI.create("/api/users/" + id)).body(TokenResponse.of(tokens.issue(user)));
		}
		catch (DuplicateKeyException ex) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Anzeigename oder E-Mail ist bereits vergeben");
		}
	}
}
