package de.robertegenolf.sausageapi.push;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

/**
 * Versendet Push-Nachrichten über Firebase Cloud Messaging (HTTP v1) – für Android direkt, für iOS über den in
 * Firebase hinterlegten APNs-Schlüssel. Konfiguriert über {@code FIREBASE_CREDENTIALS_JSON} (Inhalt des
 * JSON-Schlüssels eines Dienstkontos mit der Rolle „Firebase Cloud Messaging API Admin“). Ohne Schlüssel werden
 * Nachrichten nur protokolliert – die App funktioniert unverändert.
 * <p>
 * Das Zugriffstoken holt sich die API selbst (signiertes JWT gegen den Google-Token-Endpunkt, Nimbus ist über den
 * Resource Server ohnehin dabei) – so braucht es kein Firebase-SDK.
 */
@Component
class PushSender {

	private static final Logger log = LoggerFactory.getLogger(PushSender.class);

	private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

	/** Ergebnis je Token: zugestellt, Token ungültig (löschen) oder vorübergehender Fehler. */
	enum Result { SENT, INVALID_TOKEN, FAILED, DISABLED }

	private record Credentials(String projectId, String clientEmail, RSAPrivateKey key, String tokenUri) {
	}

	private final Credentials credentials;
	private final RestClient http;
	private String accessToken;
	private Instant accessTokenExpires = Instant.EPOCH;

	PushSender(@Value("${app.push.firebase-credentials:}") String credentialsJson) {
		this.credentials = credentialsJson == null || credentialsJson.isBlank() ? null : parse(credentialsJson);
		this.http = RestClient.create();
		if (credentials == null) {
			log.info("Push-Nachrichten deaktiviert (FIREBASE_CREDENTIALS_JSON nicht gesetzt)");
		}
	}

	boolean enabled() {
		return credentials != null;
	}

	/** {@code data}: zusätzliche Werte für die App, z. B. {@code route} = Seite, die beim Antippen öffnet. */
	Result send(String token, String title, String body, Map<String, String> data) {
		if (credentials == null) {
			log.info("Push (deaktiviert) an …{}: {} – {}", token.substring(Math.max(0, token.length() - 6)), title, body);
			return Result.DISABLED;
		}
		Map<String, Object> message = Map.of("message", Map.of(
				"token", token,
				"notification", Map.of("title", title, "body", body),
				"data", data,
				"android", Map.of("notification", Map.of("channel_id", "default")),
				"apns", Map.of("payload", Map.of("aps", Map.of("sound", "default")))));
		try {
			http.post()
					.uri("https://fcm.googleapis.com/v1/projects/{project}/messages:send", credentials.projectId())
					.header("Authorization", "Bearer " + accessToken())
					.contentType(MediaType.APPLICATION_JSON)
					.body(message)
					.retrieve()
					.toBodilessEntity();
			return Result.SENT;
		}
		catch (HttpClientErrorException ex) {
			// 404 UNREGISTERED: App deinstalliert; 400 INVALID_ARGUMENT bei kaputtem Token
			String response = ex.getResponseBodyAsString();
			if (ex.getStatusCode().value() == 404 || response.contains("UNREGISTERED")
					|| (ex.getStatusCode().value() == 400 && response.contains("registration token"))) {
				return Result.INVALID_TOKEN;
			}
			log.warn("Push fehlgeschlagen ({}): {}", ex.getStatusCode(), response);
			return Result.FAILED;
		}
		catch (RuntimeException ex) {
			log.warn("Push fehlgeschlagen: {}", ex.getMessage());
			return Result.FAILED;
		}
	}

	private synchronized String accessToken() throws RuntimeException {
		if (accessToken != null && Instant.now().isBefore(accessTokenExpires.minusSeconds(60))) {
			return accessToken;
		}
		try {
			Instant now = Instant.now();
			SignedJWT assertion = new SignedJWT(
					new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build(),
					new JWTClaimsSet.Builder()
							.issuer(credentials.clientEmail())
							.audience(credentials.tokenUri())
							.claim("scope", SCOPE)
							.issueTime(Date.from(now))
							.expirationTime(Date.from(now.plusSeconds(3600)))
							.build());
			assertion.sign(new RSASSASigner(credentials.key()));
			MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
			form.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
			form.add("assertion", assertion.serialize());
			@SuppressWarnings("unchecked")
			Map<String, Object> response = http.post()
					.uri(credentials.tokenUri())
					.contentType(MediaType.APPLICATION_FORM_URLENCODED)
					.body(form)
					.retrieve()
					.body(Map.class);
			accessToken = (String) response.get("access_token");
			accessTokenExpires = now.plusSeconds(((Number) response.get("expires_in")).longValue());
			return accessToken;
		}
		catch (com.nimbusds.jose.JOSEException ex) {
			throw new IllegalStateException("Zugriffstoken für Firebase nicht erstellt: " + ex.getMessage(), ex);
		}
	}

	/** Liest den JSON-Schlüssel eines Google-Dienstkontos. */
	static Credentials parse(String json) {
		try {
			Map<String, Object> values = JSONObjectUtils.parse(json);
			String pem = ((String) values.get("private_key"))
					.replace("-----BEGIN PRIVATE KEY-----", "")
					.replace("-----END PRIVATE KEY-----", "")
					.replaceAll("\\s", "");
			RSAPrivateKey key = (RSAPrivateKey) KeyFactory.getInstance("RSA")
					.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
			String tokenUri = (String) values.getOrDefault("token_uri", "https://oauth2.googleapis.com/token");
			return new Credentials((String) values.get("project_id"), (String) values.get("client_email"), key,
					tokenUri);
		}
		catch (Exception ex) {
			throw new IllegalStateException("FIREBASE_CREDENTIALS_JSON ist kein gültiger Dienstkonto-Schlüssel", ex);
		}
	}
}
