package de.robertegenolf.sausageapi.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Tauscht Benutzername/Passwort (HTTP Basic) gegen ein JWT, das danach als
 * {@code Authorization: Bearer <token>} mitgeschickt wird.
 */
@RestController
@RequestMapping("/api/auth")
class TokenController {

	public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
	}

	private final JwtEncoder encoder;
	private final Duration validity;

	TokenController(JwtEncoder encoder, @Value("${app.jwt.validity:PT1H}") Duration validity) {
		this.encoder = encoder;
		this.validity = validity;
	}

	@PostMapping("/token")
	TokenResponse token(Authentication auth) {
		Instant now = Instant.now();
		List<String> roles = auth.getAuthorities().stream()
				.map(GrantedAuthority::getAuthority)
				.filter(a -> a.startsWith("ROLE_"))
				.map(a -> a.substring("ROLE_".length()))
				.toList();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer("sausage-api")
				.issuedAt(now)
				.expiresAt(now.plus(validity))
				.subject(auth.getName())
				.claim(JwtConfig.ROLES_CLAIM, roles)
				.build();
		String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
		return new TokenResponse(token, "Bearer", validity.toSeconds());
	}
}
