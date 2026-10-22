package de.robertegenolf.sausageapi.user;

import de.robertegenolf.sausageapi.TestcontainersConfiguration;
import de.robertegenolf.sausageapi.mail.Mailer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"app.rate-limit.registrations-per-hour=10000",
		"app.rate-limit.password-resets-per-ip-per-hour=10000", "app.frontend-url=https://wurst.example.de"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PasswordResetTests {

	private static final String PASSWORD = "geheimesPasswort";
	private static final Pattern LINK = Pattern.compile("https://wurst\\.example\\.de/reset-password\\?token=([A-Za-z0-9_-]+)");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private Mailer mailer;

	@Test
	void passwortPerLinkZuruecksetzen() throws Exception {
		String name = register();
		requestReset(name + "@TEST.de");
		ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
		verify(mailer).send(eq(name + "@TEST.de"), eq("Sausage: Passwort zurücksetzen"), text.capture());
		Matcher link = LINK.matcher(text.getValue());
		assertThat(link.find()).isTrue();
		String token = link.group(1);
		// Gespeichert ist nur der Hash
		assertThat(jdbc.sql("SELECT COUNT(*) FROM password_reset_token WHERE token_hash = :t").param("t", token)
				.query(Long.class).single()).isZero();

		confirm(token, "neuesPasswort1").andExpect(status().isNoContent());
		mvc.perform(get("/api/users/me").with(httpBasic(name, PASSWORD))).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/users/me").with(httpBasic(name, "neuesPasswort1"))).andExpect(status().isOk());
		// Nur einmal verwendbar
		confirm(token, "nochEinPasswort").andExpect(status().isBadRequest());
	}

	@Test
	void unbekannteAdresseAntwortetGleichOhneMail() throws Exception {
		requestReset("niemand-" + UUID.randomUUID() + "@test.de");
		verify(mailer, never()).send(anyString(), anyString(), anyString());
	}

	@Test
	void abgelaufeneOderFalscheLinksWerdenAbgelehnt() throws Exception {
		String name = register();
		requestReset(name + "@test.de");
		ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
		verify(mailer).send(anyString(), anyString(), text.capture());
		Matcher link = LINK.matcher(text.getValue());
		assertThat(link.find()).isTrue();
		jdbc.sql("UPDATE password_reset_token SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute'").update();

		confirm(link.group(1), "neuesPasswort1").andExpect(status().isBadRequest());
		confirm("ausgedacht", "neuesPasswort1").andExpect(status().isBadRequest());
		mvc.perform(get("/api/users/me").with(httpBasic(name, PASSWORD))).andExpect(status().isOk());
	}

	private void requestReset(String email) throws Exception {
		mvc.perform(post("/api/auth/password-reset").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\"}"))
				.andExpect(status().isNoContent());
	}

	private ResultActions confirm(String token, String password) throws Exception {
		return mvc.perform(post("/api/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"" + token + "\",\"newPassword\":\"" + password + "\"}"));
	}

	private String register() throws Exception {
		String name = "p" + UUID.randomUUID().toString().substring(0, 8);
		mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"%s\",\"email\":\"%s@test.de\",\"acceptTerms\":true,\"password\":\"%s\"}"
								.formatted(name, name, PASSWORD)))
				.andExpect(status().isCreated());
		return name;
	}
}
