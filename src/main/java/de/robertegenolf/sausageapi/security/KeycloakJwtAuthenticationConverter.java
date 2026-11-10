package de.robertegenolf.sausageapi.security;

import de.robertegenolf.sausageapi.user.AccountLinker;
import de.robertegenolf.sausageapi.user.UserRepository;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Macht aus einem Keycloak-Token den App-User: {@link AccountLinker} findet bzw. legt den Account zur Keycloak-ID
 * ({@code sub}) an. Name der Anmeldung ist der Benutzername der App (wie bisher {@code auth.getName()}).
 * <p>
 * Rolle und Sperre kommen bei jeder Anfrage frisch aus der Datenbank, damit Admin-Rechte und Sperren sofort wirken.
 * Gesperrte Accounts bekommen nur {@value #DISABLED_AUTHORITY}: Damit dürfen sie ihre Daten exportieren und das
 * Konto löschen (DSGVO), sonst nichts (siehe {@link SecurityConfig}).
 */
@Component
class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

	static final String DISABLED_AUTHORITY = "ACCOUNT_DISABLED";

	private final AccountLinker accounts;

	KeycloakJwtAuthenticationConverter(AccountLinker accounts) {
		this.accounts = accounts;
	}

	@Override
	public AbstractAuthenticationToken convert(Jwt jwt) {
		UserRepository.StoredUser user = accounts.resolve(jwt);
		GrantedAuthority authority = new SimpleGrantedAuthority(
				user.enabled() ? "ROLE_" + user.role() : DISABLED_AUTHORITY);
		return new JwtAuthenticationToken(jwt, List.of(authority), user.username());
	}
}
