package de.robertegenolf.sausageapi.security;

import de.robertegenolf.sausageapi.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.Optional;

/**
 * Anmeldung per JWT. Das Token wird danach als {@code Authorization: Bearer <token>} mitgeschickt.
 */
@RestController
@RequestMapping("/api/auth")
class TokenController {


	/** {@code email} darf auch der Benutzername sein. */
	public record LoginRequest(@NotBlank String email, @NotBlank String password) {
	}

	public record RegisterRequest(
			@NotBlank @Email @Size(max = 255) String email,
			@NotBlank @Size(min = 8, max = 100) String password,
			@NotBlank @Size(min = 3, max = 50) @Pattern(regexp = UserRepository.USERNAME_PATTERN,
					message = UserRepository.USERNAME_MESSAGE) String displayName,
			/** Nutzungsbedingungen akzeptiert und Mindestalter (16) bestätigt. */
			@NotNull(message = UserRepository.TERMS_MESSAGE) @AssertTrue(message = UserRepository.TERMS_MESSAGE)
			Boolean acceptTerms) {
	}

	private final TokenService tokens;
	private final UserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final LoginThrottle throttle;
	/** Wird geprüft, wenn es den Account nicht gibt, damit die Antwortzeit nichts verrät. */
	private final String dummyHash;
	private final String termsVersion;

	TokenController(TokenService tokens, UserRepository users, PasswordEncoder passwordEncoder, LoginThrottle throttle,
			@Value("${app.terms.version}") String termsVersion) {
		this.tokens = tokens;
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.throttle = throttle;
		this.dummyHash = passwordEncoder.encode("kein-account-vorhanden");
		this.termsVersion = termsVersion;
	}

	/** Tauscht HTTP-Basic-Login (oder ein noch gültiges Token) gegen ein neues JWT. */
	@PostMapping("/token")
	TokenService.TokenResponse token(Authentication auth) {
		return TokenService.TokenResponse.of(tokens.issue(currentUser(auth)));
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
	TokenService.TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
		String login = request.email().trim();
		throttle.checkLogin(http.getRemoteAddr(), login);
		Optional<UserRepository.StoredUser> user = users.findByLogin(login);
		boolean valid = passwordEncoder.matches(request.password(), user.map(UserRepository.StoredUser::passwordHash)
				.orElse(dummyHash));
		if (!valid || user.isEmpty()) {
			throttle.loginFailed(http.getRemoteAddr(), login);
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "E-Mail oder Passwort ist falsch");
		}
		return TokenService.TokenResponse.of(tokens.issue(user.get()));
	}

	/** Registriert einen Account (Anzeigename = Benutzername) und meldet direkt an. */
	@PostMapping("/register")
	ResponseEntity<TokenService.TokenResponse> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
		throttle.checkRegistration(http.getRemoteAddr());
		String username = UserRepository.normalizeUsername(request.displayName());
		if (UserRepository.isReservedUsername(username)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Dieser Anzeigename ist reserviert");
		}
		try {
			long id = users.create(username, request.email().trim(), passwordEncoder.encode(request.password()),
					termsVersion);
			UserRepository.StoredUser user = users.findByUsername(username).orElseThrow();
			return ResponseEntity.created(URI.create("/api/users/" + id)).body(TokenService.TokenResponse.of(tokens.issue(user)));
		}
		catch (DuplicateKeyException ex) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Anzeigename oder E-Mail ist bereits vergeben");
		}
	}
}
