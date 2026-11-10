package de.robertegenolf.sausageapi.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Admin-API von Keycloak mit dem Dienstkonto {@code app.keycloak.client-id} (Client-Credentials, Rolle manage-users):
 * Konto löschen und auf allen Geräten abmelden. Ohne {@code app.keycloak.client-secret} (lokale Entwicklung, Tests)
 * passiert nichts – dann ändert die API nur ihre eigene Datenbank.
 */
@Component
public class KeycloakAdminClient {

	private static final Logger log = LoggerFactory.getLogger(KeycloakAdminClient.class);

	private final RestClient rest;
	private final String realm;
	private final String clientId;
	private final String clientSecret;
	private final boolean enabled;

	KeycloakAdminClient(@Value("${app.keycloak.admin-url:}") String adminUrl,
			@Value("${app.keycloak.realm:sausage}") String realm,
			@Value("${app.keycloak.client-id:sausage-api-admin}") String clientId,
			@Value("${app.keycloak.client-secret:}") String clientSecret) {
		this.rest = RestClient.builder().baseUrl(adminUrl.replaceAll("/+$", "")).build();
		this.realm = realm;
		this.clientId = clientId;
		this.clientSecret = clientSecret;
		this.enabled = !adminUrl.isBlank() && !clientSecret.isBlank();
		if (!enabled) {
			log.warn("Keycloak-Admin-API nicht konfiguriert (KEYCLOAK_ADMIN_CLIENT_SECRET) - Konten werden nur in der App gelöscht");
		}
	}

	/** Löscht das Keycloak-Konto; ein bereits gelöschtes (404) gilt als erledigt. */
	public void deleteUser(String userId) {
		call("Konto konnte bei der Anmeldung nicht gelöscht werden", userId, () -> rest.delete()
				.uri("/admin/realms/{realm}/users/{id}", realm, userId)
				.headers(h -> h.setBearerAuth(token()))
				.retrieve()
				.onStatus(status -> status.value() == 404, (req, res) -> {
				})
				.toBodilessEntity());
	}

	/** Beendet alle Sitzungen (alle Geräte); die Apps müssen sich danach neu anmelden. */
	public void logoutUser(String userId) {
		call("Abmelden auf allen Geräten ist fehlgeschlagen", userId, () -> rest.post()
				.uri("/admin/realms/{realm}/users/{id}/logout", realm, userId)
				.headers(h -> h.setBearerAuth(token()))
				.retrieve()
				.onStatus(status -> status.value() == 404, (req, res) -> {
				})
				.toBodilessEntity());
	}

	private void call(String message, String userId, Runnable request) {
		if (!enabled || userId == null) {
			return;
		}
		try {
			request.run();
		}
		catch (RestClientException ex) {
			log.error("Keycloak-Admin-API: {} (Konto {})", message, userId, ex);
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, message + ". Bitte später erneut versuchen.");
		}
	}

	private String token() {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("grant_type", "client_credentials");
		form.add("client_id", clientId);
		form.add("client_secret", clientSecret);
		Map<?, ?> response = rest.post()
				.uri("/realms/{realm}/protocol/openid-connect/token", realm)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(form)
				.retrieve()
				.onStatus(HttpStatusCode::isError, (req, res) -> {
					throw new RestClientException("Token für " + clientId + ": HTTP " + res.getStatusCode().value());
				})
				.body(Map.class);
		Object token = response == null ? null : response.get("access_token");
		if (!(token instanceof String value)) {
			throw new RestClientException("Keine access_token-Antwort für " + clientId);
		}
		return value;
	}
}
