package de.robertegenolf.sausageapi;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/**
 * Stellt Tokens aus, wie Keycloak sie für die App ausstellt (Realm-Issuer, {@code aud=sausage-api},
 * {@code preferred_username}, {@code email}). Signiert mit einem Test-Schlüssel, den
 * {@link TestSecurityConfiguration} der API als Realm-Schlüssel gibt – die Tokens durchlaufen also dieselbe Prüfung
 * und dieselbe Account-Verknüpfung wie in Produktion.
 */
public final class TestTokens {

	public static final String ISSUER = "https://auth.test/realms/sausage";

	public static final String AUDIENCE = "sausage-api";

	private static final RSAKey KEY = generate();

	private static final JwtEncoder ENCODER = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(KEY)));

	private TestTokens() {
	}

	/** Angemeldet als {@code username} (Keycloak-ID {@code sub-<username>}, E-Mail {@code <username>@test.de}). */
	public static RequestPostProcessor login(String username) {
		return bearer(token(username));
	}

	/** Wie {@link #login(String)}, aber mit eigener Keycloak-ID (z. B. neues Konto mit demselben Namen). */
	public static RequestPostProcessor login(String username, String subject) {
		return bearer(token(username, subject));
	}

	public static RequestPostProcessor bearer(String token) {
		return request -> {
			request.addHeader("Authorization", "Bearer " + token);
			return request;
		};
	}

	public static String token(String username) {
		return token(username, subject(username));
	}

	public static String token(String username, String subject) {
		return token(claims -> claims.subject(subject)
				.claim("preferred_username", username)
				.claim("email", username + "@test.de")
				.claim("email_verified", false));
	}

	/** Gültiges Token mit Standard-Claims (Issuer, Audience, 5 Minuten), anpassbar über {@code customizer}. */
	public static String token(Consumer<JwtClaimsSet.Builder> customizer) {
		Instant now = Instant.now();
		JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
				.issuer(ISSUER)
				.audience(List.of(AUDIENCE))
				.issuedAt(now)
				.expiresAt(now.plusSeconds(300))
				.claim("azp", "sausage-app")
				.claim("typ", "Bearer");
		customizer.accept(claims);
		JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(KEY.getKeyID()).build();
		return ENCODER.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
	}

	public static String subject(String username) {
		return "sub-" + username;
	}

	static RSAPublicKey publicKey() {
		try {
			return KEY.toRSAPublicKey();
		}
		catch (JOSEException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static RSAKey generate() {
		try {
			return new RSAKeyGenerator(2048).keyID("test").generate();
		}
		catch (JOSEException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
