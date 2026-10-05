package de.robertegenolf.sausageapi.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
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
}
