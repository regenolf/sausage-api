package de.robertegenolf.sausageapi.user;

import de.robertegenolf.sausageapi.mail.Mailer;
import de.robertegenolf.sausageapi.security.LoginThrottle;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * „Passwort vergessen“: Link per E-Mail, 60 Minuten gültig und einmal verwendbar. Gespeichert wird nur der Hash
 * des Tokens. Die Anfrage antwortet immer gleich (204), damit niemand herausfindet, welche Adressen registriert sind.
 */
@RestController
@RequestMapping("/api/auth/password-reset")
class PasswordResetController {

	static final int VALIDITY_MINUTES = 60;

	public record ResetRequest(@NotBlank @Email @Size(max = 255) String email) {
	}

	public record ResetConfirm(@NotBlank @Size(max = 200) String token,
			@NotBlank @Size(min = 8, max = 72) @BcryptPassword String newPassword) {
	}

	private static final SecureRandom RANDOM = new SecureRandom();

	private final UserRepository users;
	private final JdbcClient jdbc;
	private final PasswordEncoder passwordEncoder;
	private final LoginThrottle throttle;
	private final Mailer mailer;
	private final String frontendUrl;

	PasswordResetController(UserRepository users, JdbcClient jdbc, PasswordEncoder passwordEncoder,
			LoginThrottle throttle, Mailer mailer, @Value("${app.frontend-url:}") String frontendUrl,
			@Value("${app.public-url:}") String publicUrl) {
		this.users = users;
		this.jdbc = jdbc;
		this.passwordEncoder = passwordEncoder;
		this.throttle = throttle;
		this.mailer = mailer;
		this.frontendUrl = frontendUrl.isBlank() ? publicUrl : frontendUrl;
	}

	@PostMapping
	@Transactional
	ResponseEntity<Void> request(@Valid @RequestBody ResetRequest request, HttpServletRequest http) {
		String email = request.email().trim();
		throttle.checkPasswordReset(http.getRemoteAddr(), email);
		Optional<UserRepository.StoredUser> user = users.findByLogin(email);
		if (user.isPresent()) {
			long userId = user.get().id();
			String token = newToken();
			// Ein neuer Link macht ältere ungültig
			jdbc.sql("DELETE FROM password_reset_token WHERE user_id = :userId").param("userId", userId).update();
			jdbc.sql("""
					INSERT INTO password_reset_token (user_id, token_hash, expires_at)
					VALUES (:userId, :hash, CURRENT_TIMESTAMP + make_interval(mins => :minutes))
					""")
					.param("userId", userId)
					.param("hash", hash(token))
					.param("minutes", VALIDITY_MINUTES)
					.update();
			String link = baseUrl() + "/reset-password?token=" + token;
			mailer.send(email, "Sausage: Passwort zurücksetzen", """
					Hallo %s,

					für dein Sausage-Konto wurde ein neues Passwort angefordert. Über diesen Link kannst du es
					in den nächsten %d Minuten festlegen:

					%s

					Wenn du das nicht warst, ignoriere diese Mail einfach – dein Passwort bleibt unverändert.
					""".formatted(user.get().username(), VALIDITY_MINUTES, link));
		}
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/confirm")
	@Transactional
	ResponseEntity<Void> confirm(@Valid @RequestBody ResetConfirm request) {
		// Löschen statt markieren: ein Link funktioniert genau einmal
		Long userId = jdbc.sql("""
				DELETE FROM password_reset_token
				WHERE token_hash = :hash AND expires_at > CURRENT_TIMESTAMP
				RETURNING user_id
				""")
				.param("hash", hash(request.token().trim()))
				.query(Long.class)
				.optional()
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
						"Der Link ist ungültig oder abgelaufen. Bitte fordere einen neuen an."));
		// Macht auch alle bestehenden Anmeldungen ungültig (token_version)
		users.updatePasswordHash(userId, passwordEncoder.encode(request.newPassword()));
		jdbc.sql("DELETE FROM password_reset_token WHERE user_id = :userId").param("userId", userId).update();
		return ResponseEntity.noContent().build();
	}

	private String baseUrl() {
		if (!frontendUrl.isBlank()) {
			return frontendUrl.replaceAll("/+$", "");
		}
		return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
	}

	private static String newToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	static String hash(String token) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
