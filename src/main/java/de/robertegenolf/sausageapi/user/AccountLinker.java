package de.robertegenolf.sausageapi.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Verknüpft Keycloak-Konten mit den Accounts der App ({@code app_user}), an denen Spots, Bewertungen, Kommentare,
 * Fotos und die Rolle hängen. Schlüssel ist die Keycloak-User-ID ({@code sub}, Spalte {@code external_id}).
 * <ul>
 * <li>Bekannte ID: Account laden, geänderte E-Mail übernehmen.</li>
 * <li>Unbekannte ID mit <b>bestätigter</b> E-Mail eines noch nicht verknüpften Accounts (aus der Zeit vor Keycloak):
 * diesen übernehmen. Nur bei bestätigter Adresse – sonst könnte jemand mit fremder Adresse ein altes Konto kapern.</li>
 * <li>Sonst neuen Account anlegen. Benutzername = Keycloak-Benutzername; ist er vergeben, wird eine Zahl angehängt,
 * reservierte Namen (z. B. "Admin") werden zu "nutzer".</li>
 * </ul>
 */
@Service
public class AccountLinker {

	private static final Logger log = LoggerFactory.getLogger(AccountLinker.class);

	private static final int MAX_USERNAME = 50;

	private final UserRepository users;

	AccountLinker(UserRepository users) {
		this.users = users;
	}

	public UserRepository.StoredUser resolve(Jwt jwt) {
		String externalId = jwt.getSubject();
		if (externalId == null || externalId.isBlank()) {
			throw new InvalidBearerTokenException("Token ohne Benutzer-ID");
		}
		Optional<UserRepository.StoredUser> existing = users.findByExternalId(externalId);
		if (existing.isPresent()) {
			syncEmail(existing.get(), jwt.getClaimAsString("email"));
			return existing.get();
		}
		try {
			return provision(jwt, externalId);
		}
		catch (DuplicateKeyException ex) {
			// Parallele erste Anfragen desselben Nutzers: der andere Request hat den Account schon angelegt
			return users.findByExternalId(externalId)
					.orElseThrow(() -> new InvalidBearerTokenException("Konto konnte nicht angelegt werden"));
		}
	}

	private UserRepository.StoredUser provision(Jwt jwt, String externalId) {
		if (users.recentlyDeleted(externalId)) {
			throw new InvalidBearerTokenException("Das Konto wurde gelöscht");
		}
		String email = jwt.getClaimAsString("email");
		if (email == null || email.isBlank()) {
			throw new InvalidBearerTokenException("Token ohne E-Mail-Adresse");
		}
		email = email.trim();
		if (Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))) {
			Optional<UserRepository.StoredUser> legacy = users.findUnlinkedByEmail(email);
			if (legacy.isPresent()) {
				users.linkExternalId(legacy.get().id(), externalId);
				log.info("Bestehenden Account {} mit Keycloak-Konto {} verknüpft", legacy.get().username(), externalId);
				return users.findByExternalId(externalId).orElseThrow();
			}
		}
		if (users.emailTaken(email)) {
			// Gehört einem alten, nicht verknüpften Account – übernehmen geht nur mit bestätigter Adresse
			throw new InvalidBearerTokenException(
					"Die E-Mail-Adresse gehört zu einem bestehenden Konto. Bitte die Adresse bestätigen lassen.");
		}
		String username = freeUsername(jwt.getClaimAsString("preferred_username"), email);
		users.createExternal(externalId, username, email);
		log.info("Neuen Account {} für Keycloak-Konto {} angelegt", username, externalId);
		return users.findByExternalId(externalId).orElseThrow();
	}

	/** Benutzername aus Keycloak, sonst aus der E-Mail; bei Konflikt mit Zahl ("name-2", "name-3" …). */
	String freeUsername(String preferred, String email) {
		String base = preferred == null || preferred.isBlank() ? email.substring(0, email.indexOf('@')) : preferred;
		base = UserRepository.normalizeUsername(base).replaceAll("[^\\p{L}\\p{N} ._-]", "").strip();
		base = base.replaceAll("^[ ._-]+|[ ._-]+$", "");
		if (base.length() > MAX_USERNAME) {
			base = base.substring(0, MAX_USERNAME).replaceAll("[ ._-]+$", "");
		}
		if (base.length() < 3 || UserRepository.isReservedUsername(base)) {
			base = "nutzer";
		}
		if (usable(base)) {
			return base;
		}
		for (int i = 2; i < 10_000; i++) {
			String suffix = "-" + i;
			String candidate = base.substring(0, Math.min(base.length(), MAX_USERNAME - suffix.length()))
					.replaceAll("[ ._-]+$", "") + suffix;
			if (usable(candidate)) {
				return candidate;
			}
		}
		throw new IllegalStateException("Kein freier Benutzername für " + base);
	}

	private boolean usable(String username) {
		return UserRepository.isValidNormalizedUsername(username) && !UserRepository.isReservedUsername(username)
				&& !users.usernameTaken(username);
	}

	private void syncEmail(UserRepository.StoredUser user, String email) {
		if (email == null || email.isBlank() || email.trim().equalsIgnoreCase(user.email())) {
			return;
		}
		try {
			users.updateEmail(user.id(), email.trim());
		}
		catch (DuplicateKeyException ex) {
			log.warn("E-Mail von {} nicht übernommen: Adresse gehört einem anderen Account", user.username());
		}
	}
}
