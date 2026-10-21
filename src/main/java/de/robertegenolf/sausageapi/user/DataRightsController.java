package de.robertegenolf.sausageapi.user;

import de.robertegenolf.sausageapi.security.LoginThrottle;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * DSGVO-Rechte auch für gesperrte Accounts (Art. 15, 17, 20): Export und Löschung mit Benutzername/E-Mail und
 * Passwort statt Token – gesperrte Accounts können sich nicht anmelden. Gedrosselt wie der Login.
 */
@RestController
@RequestMapping("/api/auth/data")
class DataRightsController {

	public record Credentials(@NotBlank @Size(max = 255) String login, @NotBlank @Size(max = 200) String password) {
	}

	private final UserRepository users;
	private final UserExportRepository exports;
	private final PasswordEncoder passwordEncoder;
	private final LoginThrottle throttle;

	DataRightsController(UserRepository users, UserExportRepository exports, PasswordEncoder passwordEncoder,
			LoginThrottle throttle) {
		this.users = users;
		this.exports = exports;
		this.passwordEncoder = passwordEncoder;
		this.throttle = throttle;
	}

	@PostMapping("/export")
	ResponseEntity<Map<String, Object>> export(@Valid @RequestBody Credentials credentials, HttpServletRequest http) {
		UserRepository.StoredUser user = authenticate(credentials, http);
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
						.filename("sausage-daten-" + user.username() + ".json", StandardCharsets.UTF_8).build().toString())
				.body(exports.export(user.id()));
	}

	@PostMapping("/delete")
	ResponseEntity<Void> delete(@Valid @RequestBody Credentials credentials, HttpServletRequest http) {
		users.delete(authenticate(credentials, http).id());
		return ResponseEntity.noContent().build();
	}

	private UserRepository.StoredUser authenticate(Credentials credentials, HttpServletRequest http) {
		String login = credentials.login().trim();
		throttle.checkLogin(http.getRemoteAddr(), login);
		return users.findByLogin(login, true)
				.filter(user -> passwordEncoder.matches(credentials.password(), user.passwordHash()))
				.orElseThrow(() -> {
					throttle.loginFailed(http.getRemoteAddr(), login);
					return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "E-Mail/Benutzername oder Passwort ist falsch");
				});
	}
}
