package de.robertegenolf.sausageapi.security;

import de.robertegenolf.sausageapi.user.UserRepository;
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
 * Stellt JWTs aus: {@code sub} und {@code name} sind der Benutzername, {@code roles} die Rolle (nur zur Info für
 * Clients, maßgeblich ist die Datenbank), {@code ver} die Token-Version des Users.
 */
@Service
public class TokenService {

	record IssuedToken(String token, long expiresIn) {
	}

	/** Antwort mit neuem JWT; {@code token} und {@code accessToken} sind identisch (für verschiedene Clients). */
	public record TokenResponse(String token, String accessToken, String tokenType, long expiresIn) {

		static TokenResponse of(IssuedToken issued) {
			return new TokenResponse(issued.token(), issued.token(), "Bearer", issued.expiresIn());
		}
	}

	/** Stellt ein Token für den (aktuellen Stand des) Users aus. */
	public TokenResponse issueResponse(UserRepository.StoredUser user) {
		return TokenResponse.of(issue(user));
	}

	private final JwtEncoder encoder;
	private final Duration validity;

	TokenService(JwtEncoder encoder, @Value("${app.jwt.validity:P30D}") Duration validity) {
		this.encoder = encoder;
		this.validity = validity;
	}

	IssuedToken issue(UserRepository.StoredUser user) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer("sausage-api")
				.issuedAt(now)
				.expiresAt(now.plus(validity))
				.subject(user.username())
				.claim("name", user.username())
				.claim(JwtConfig.ROLES_CLAIM, List.of(user.role()))
				.claim(UserJwtAuthenticationConverter.VERSION_CLAIM, user.tokenVersion())
				.build();
		String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
		return new IssuedToken(token, validity.toSeconds());
	}
}
