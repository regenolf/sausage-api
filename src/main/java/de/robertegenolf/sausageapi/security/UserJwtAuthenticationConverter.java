package de.robertegenolf.sausageapi.security;

import de.robertegenolf.sausageapi.user.UserRepository;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Prüft bei jeder Anfrage gegen die Datenbank, ob das JWT noch gilt: Der Account muss existieren und aktiv sein,
 * und die Token-Version muss stimmen (Passwortänderung und "überall abmelden" erhöhen sie). Die Rolle kommt
 * ebenfalls frisch aus der Datenbank, damit entzogene Admin-Rechte sofort wirken.
 */
@Component
class UserJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

	static final String VERSION_CLAIM = "ver";

	/**
	 * User-ID: Benutzernamen werden nach einer Kontolöschung wieder frei, und ein neues Konto beginnt wieder bei
	 * Token-Version 0 – ohne ID würde ein altes Token für das neue Konto gelten.
	 */
	static final String USER_ID_CLAIM = "uid";

	/** iat ist auf Sekunden gekürzt, created_at nicht. */
	private static final Duration CLOCK_SLACK = Duration.ofSeconds(1);

	private final UserRepository users;

	UserJwtAuthenticationConverter(UserRepository users) {
		this.users = users;
	}

	@Override
	public AbstractAuthenticationToken convert(Jwt jwt) {
		Object version = jwt.getClaim(VERSION_CLAIM);
		UserRepository.StoredUser user = users.findByUsername(jwt.getSubject())
				.filter(u -> version instanceof Number n && n.longValue() == u.tokenVersion())
				.filter(u -> belongsTo(jwt, u))
				.orElseThrow(() -> new InvalidBearerTokenException("Token ist nicht mehr gültig"));
		return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + user.role())),
				user.username());
	}

	/**
	 * Gehört das Token zu genau diesem Account? Neuere Tokens tragen die User-ID; für ältere ohne ID gilt: vor der
	 * Registrierung ausgestellte Tokens stammen von einem gelöschten Vorgänger mit demselben Namen.
	 */
	static boolean belongsTo(Jwt jwt, UserRepository.StoredUser user) {
		Object userId = jwt.getClaim(USER_ID_CLAIM);
		if (userId != null) {
			return userId instanceof Number n && n.longValue() == user.id();
		}
		Instant issuedAt = jwt.getIssuedAt();
		return issuedAt != null && !issuedAt.plus(CLOCK_SLACK).isBefore(user.createdAt());
	}
}
