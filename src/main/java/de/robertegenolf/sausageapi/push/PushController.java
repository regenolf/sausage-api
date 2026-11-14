package de.robertegenolf.sausageapi.push;

import de.robertegenolf.sausageapi.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Benachrichtigungen: Die App meldet nach der Erlaubnis durch den Nutzer ihr Push-Token an (und beim Abmelden
 * oder Abschalten wieder ab). Gesendet wird über Firebase Cloud Messaging, siehe {@link PushSender}.
 */
@RestController
@RequestMapping("/api/users/me/devices")
class PushController {

	record DeviceRequest(@NotBlank @Size(max = 4096) String token, @NotNull PushDeviceRepository.Platform platform) {
	}

	record TokenRequest(@NotBlank @Size(max = 4096) String token) {
	}

	private final PushDeviceRepository devices;
	private final UserRepository users;

	PushController(PushDeviceRepository devices, UserRepository users) {
		this.devices = devices;
		this.users = users;
	}

	@PutMapping
	ResponseEntity<Void> register(@Valid @RequestBody DeviceRequest request, Authentication auth) {
		devices.register(currentUserId(auth), request.token(), request.platform());
		return ResponseEntity.noContent().build();
	}

	/** Abmelden per POST, weil das Token für einen Pfad zu lang sein kann. */
	@PostMapping("/remove")
	ResponseEntity<Void> unregister(@Valid @RequestBody TokenRequest request, Authentication auth) {
		devices.unregister(currentUserId(auth), request.token());
		return ResponseEntity.noContent().build();
	}

	private long currentUserId(Authentication auth) {
		return users.findByUsername(auth.getName())
				.map(UserRepository.StoredUser::id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
	}
}
