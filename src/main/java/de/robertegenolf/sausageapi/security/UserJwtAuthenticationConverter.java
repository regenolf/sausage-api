package de.robertegenolf.sausageapi.security;

import de.robertegenolf.sausageapi.user.UserRepository;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Prüft bei jeder Anfrage gegen die Datenbank, ob das JWT noch gilt: Der Account muss existieren und aktiv sein,
 * und die Token-Version muss stimmen (Passwortänderung und "überall abmelden" erhöhen sie). Die Rolle kommt
 * ebenfalls frisch aus der Datenbank, damit entzogene Admin-Rechte sofort wirken.
 */
@Component
class UserJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

	static final String VERSION_CLAIM = "ver";

	private final UserRepository users;

	UserJwtAuthenticationConverter(UserRepository users) {
		this.users = users;
	}

	@Override
	public AbstractAuthenticationToken convert(Jwt jwt) {
		Object version = jwt.getClaim(VERSION_CLAIM);
		UserRepository.StoredUser user = users.findByUsername(jwt.getSubject())
				.filter(u -> version instanceof Number n && n.longValue() == u.tokenVersion())
				.orElseThrow(() -> new InvalidBearerTokenException("Token ist nicht mehr gültig"));
		return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + user.role())),
				user.username());
	}
}
