package de.robertegenolf.sausageapi;

import de.robertegenolf.sausageapi.security.OidcConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Ersetzt den Keycloak-Schlüssel durch den Test-Schlüssel aus {@link TestTokens}. Geprüft wird wie in Produktion
 * (Ablauf, Issuer, Audience) – nur ohne laufendes Keycloak.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestSecurityConfiguration {

	@Bean
	@Primary
	JwtDecoder testJwtDecoder() {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(TestTokens.publicKey()).build();
		decoder.setJwtValidator(OidcConfig.validator(TestTokens.ISSUER, TestTokens.AUDIENCE));
		return decoder;
	}
}
