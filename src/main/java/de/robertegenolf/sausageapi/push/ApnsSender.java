package de.robertegenolf.sausageapi.push;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jose.util.JSONObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Versendet Push-Nachrichten an iPhones direkt über den Apple Push Notification service (HTTP/2, Token-Anmeldung
 * mit einem .p8-Schlüssel). Die App liefert auf iOS ein APNs-Token (Capacitor Push Notifications), daher braucht
 * iOS kein Firebase. Konfiguration: {@code APNS_KEY_P8} (Inhalt der .p8-Datei aus developer.apple.com → Keys, mit
 * „Apple Push Notifications service“), {@code APNS_KEY_ID}, {@code APNS_TEAM_ID}. TestFlight und App Store nutzen
 * die Produktionsumgebung; Builds aus Xcode die Sandbox ({@code APNS_SANDBOX=true}).
 */
@Component
class ApnsSender {

	private static final Logger log = LoggerFactory.getLogger(ApnsSender.class);

	private final ECPrivateKey key;
	private final String keyId;
	private final String teamId;
	private final String topic;
	private final String host;
	private final HttpClient http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_2)
			.connectTimeout(Duration.ofSeconds(10))
			.build();
	private String jwt;
	private Instant jwtIssued = Instant.EPOCH;

	ApnsSender(@Value("${app.push.apns.key:}") String keyP8, @Value("${app.push.apns.key-id:}") String keyId,
			@Value("${app.push.apns.team-id:}") String teamId,
			@Value("${app.push.apns.topic:de.wurstweiser.app}") String topic,
			@Value("${app.push.apns.sandbox:false}") boolean sandbox) {
		boolean configured = !keyP8.isBlank() && !keyId.isBlank() && !teamId.isBlank();
		this.key = configured ? parse(keyP8) : null;
		this.keyId = keyId;
		this.teamId = teamId;
		this.topic = topic;
		this.host = sandbox ? "https://api.sandbox.push.apple.com" : "https://api.push.apple.com";
		if (!configured) {
			log.info("Push an iOS deaktiviert (APNS_KEY_P8, APNS_KEY_ID oder APNS_TEAM_ID nicht gesetzt)");
		}
	}

	PushSender.Result send(String token, String title, String body, Map<String, String> data) {
		if (key == null) {
			log.info("APNs (deaktiviert) an …{}: {} – {}", token.substring(Math.max(0, token.length() - 6)), title, body);
			return PushSender.Result.DISABLED;
		}
		Map<String, Object> payload = new LinkedHashMap<>(data);
		payload.put("aps", Map.of("alert", Map.of("title", title, "body", body), "sound", "default"));
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(host + "/3/device/" + token))
					.timeout(Duration.ofSeconds(15))
					.header("authorization", "bearer " + jwt())
					.header("apns-topic", topic)
					.header("apns-push-type", "alert")
					.header("apns-priority", "10")
					.POST(HttpRequest.BodyPublishers.ofString(JSONObjectUtils.toJSONString(payload)))
					.build();
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() == 200) {
				return PushSender.Result.SENT;
			}
			// 410 Unregistered, 400 BadDeviceToken: Token gehört zu keiner Installation (mehr)
			if (response.statusCode() == 410 || response.body().contains("BadDeviceToken")
					|| response.body().contains("Unregistered")) {
				return PushSender.Result.INVALID_TOKEN;
			}
			log.warn("APNs fehlgeschlagen ({}): {}", response.statusCode(), response.body());
			return PushSender.Result.FAILED;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return PushSender.Result.FAILED;
		}
		catch (Exception ex) {
			log.warn("APNs fehlgeschlagen: {}", ex.getMessage());
			return PushSender.Result.FAILED;
		}
	}

	/** Apple erlaubt ein Token bis zu einer Stunde und nicht öfter als alle 20 Minuten ein neues. */
	private synchronized String jwt() throws com.nimbusds.jose.JOSEException {
		if (jwt != null && Instant.now().isBefore(jwtIssued.plus(Duration.ofMinutes(50)))) {
			return jwt;
		}
		Instant now = Instant.now();
		SignedJWT token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(keyId).build(),
				new JWTClaimsSet.Builder().issuer(teamId).issueTime(Date.from(now)).build());
		token.sign(new ECDSASigner(key));
		jwt = token.serialize();
		jwtIssued = now;
		return jwt;
	}

	static ECPrivateKey parse(String p8) {
		try {
			String pem = p8.replace("-----BEGIN PRIVATE KEY-----", "")
					.replace("-----END PRIVATE KEY-----", "")
					.replace("\\n", "")
					.replaceAll("\\s", "");
			return (ECPrivateKey) KeyFactory.getInstance("EC")
					.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
		}
		catch (Exception ex) {
			throw new IllegalStateException("APNS_KEY_P8 ist kein gültiger .p8-Schlüssel", ex);
		}
	}
}
