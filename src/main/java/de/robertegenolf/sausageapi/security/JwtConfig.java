package de.robertegenolf.sausageapi.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Signiert und prüft JWTs mit einem gemeinsamen HMAC-Schlüssel (HS256) aus {@code app.jwt.secret}.
 * Ohne konfigurierten Schlüssel wird beim Start ein zufälliger erzeugt: Tokens lassen sich dann nicht fälschen,
 * verlieren aber bei jedem Neustart ihre Gültigkeit. In Produktion daher JWT_SECRET setzen.
 */
@Configuration
class JwtConfig {

	static final String ROLES_CLAIM = "roles";

	private final SecretKey key;

	JwtConfig(@Value("${app.jwt.secret:}") String secret) {
		if (secret.isBlank()) {
			byte[] random = new byte[48];
			new SecureRandom().nextBytes(random);
			LoggerFactory.getLogger(JwtConfig.class).warn(
					"Kein JWT_SECRET gesetzt - es wird ein zufälliger Schlüssel verwendet. Anmeldungen verlieren beim Neustart ihre Gültigkeit.");
			this.key = new SecretKeySpec(random, "HmacSHA256");
			return;
		}
		byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
		if (bytes.length < 32) {
			throw new IllegalStateException("app.jwt.secret muss mindestens 32 Bytes lang sein");
		}
		this.key = new SecretKeySpec(bytes, "HmacSHA256");
	}

	@Bean
	JwtEncoder jwtEncoder() {
		return new NimbusJwtEncoder(new ImmutableSecret<>(key));
	}

	@Bean
	JwtDecoder jwtDecoder() {
		return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
	}
}
