package de.robertegenolf.sausageapi.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
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

@RestController
@RequestMapping("/api/users")
class UserController {

	public record RegisterRequest(
			@NotBlank @Size(min = 3, max = 50) String username,
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
	private final PasswordEncoder passwordEncoder;

	UserController(UserRepository repository, PasswordEncoder passwordEncoder) {
		this.repository = repository;
		this.passwordEncoder = passwordEncoder;
	}

	@PostMapping
	ResponseEntity<RegisteredUser> register(@Valid @RequestBody RegisterRequest request) {
		try {
			long id = repository.create(request.username(), request.email(),
					passwordEncoder.encode(request.password()));
			return ResponseEntity.created(URI.create("/api/users/" + id))
					.body(new RegisteredUser(id, request.username()));
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
	ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request, Authentication auth) {
		UserRepository.StoredUser user = repository.findByUsername(auth.getName())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
		if (!passwordEncoder.matches(request.currentPassword(), user.passwordHash())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Aktuelles Passwort ist falsch");
		}
		repository.updatePasswordHash(user.id(), passwordEncoder.encode(request.newPassword()));
		return ResponseEntity.noContent().build();
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
}
