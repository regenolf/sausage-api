package de.robertegenolf.sausageapi.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.util.Collection;

/**
 * Anmeldung über Keycloak (OpenID Connect). Die API stellt selbst keine Tokens mehr aus, sondern prüft die Access
 * Tokens des Realms: Signatur (Schlüssel vom JWKS-Endpunkt), Ablauf, Aussteller ({@code iss}) und Empfänger
 * ({@code aud} muss {@code app.oidc.audience} enthalten – ID-Tokens und Tokens anderer Apps werden abgelehnt).
 * <p>
 * Die Schlüssel werden erst beim ersten Request geladen; die API startet also auch, wenn Keycloak gerade nicht
 * erreichbar ist.
 */
@Configuration
public class OidcConfig {

	@Bean
	JwtDecoder jwtDecoder(@Value("${app.oidc.issuer-uri}") String issuer,
			@Value("${app.oidc.jwk-set-uri:}") String jwkSetUri,
			@Value("${app.oidc.audience}") String audience) {
		String jwks = jwkSetUri.isBlank() ? issuer.replaceAll("/+$", "") + "/protocol/openid-connect/certs" : jwkSetUri;
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwks).jwsAlgorithm(SignatureAlgorithm.RS256).build();
		decoder.setJwtValidator(validator(issuer, audience));
		return decoder;
	}

	/** Standardprüfungen (Zeitstempel) plus Aussteller und Empfänger. Auch von den Tests verwendet. */
	public static OAuth2TokenValidator<Jwt> validator(String issuer, String audience) {
		OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<Collection<String>>(JwtClaimNames.AUD,
				aud -> aud != null && aud.contains(audience));
		return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audienceValidator);
	}
}
