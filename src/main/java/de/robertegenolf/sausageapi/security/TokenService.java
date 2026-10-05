package de.robertegenolf.sausageapi.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Stellt JWTs aus: {@code sub} und {@code name} sind der Benutzername, {@code roles} die Rollen.
 */
@Service
class TokenService {

	record IssuedToken(String token, long expiresIn) {
	}

	private final JwtEncoder encoder;
	private final Duration validity;

	TokenService(JwtEncoder encoder, @Value("${app.jwt.validity:PT1H}") Duration validity) {
		this.encoder = encoder;
		this.validity = validity;
	}

	IssuedToken issue(String username, List<String> roles) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer("sausage-api")
				.issuedAt(now)
				.expiresAt(now.plus(validity))
				.subject(username)
				.claim("name", username)
				.claim(JwtConfig.ROLES_CLAIM, roles)
				.build();
		String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
		return new IssuedToken(token, validity.toSeconds());
	}
}
