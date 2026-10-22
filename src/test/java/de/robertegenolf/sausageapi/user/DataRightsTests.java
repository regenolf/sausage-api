package de.robertegenolf.sausageapi.user;

import de.robertegenolf.sausageapi.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DSGVO-Export und -Löschung auch für gesperrte Accounts. */
@SpringBootTest(properties = {"app.rate-limit.registrations-per-hour=10000",
		"app.rate-limit.failed-logins-per-15-minutes=10000", "app.rate-limit.failed-logins-per-ip-per-15-minutes=10000"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DataRightsTests {

	private static final String PASSWORD = "geheimesPasswort";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void gesperrteKontenKoennenExportierenUndLoeschen() throws Exception {
		String name = "d" + UUID.randomUUID().toString().substring(0, 8);
		mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"%s\",\"email\":\"%s@test.de\",\"acceptTerms\":true,\"password\":\"%s\"}"
								.formatted(name, name, PASSWORD)))
				.andExpect(status().isCreated());
		jdbc.sql("UPDATE app_user SET enabled = false WHERE username = :u").param("u", name).update();
		// Anmelden geht nicht mehr …
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + name + "\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isUnauthorized());

		// … Export und Löschung schon, aber nur mit dem richtigen Passwort
		mvc.perform(post("/api/auth/data/export").contentType(MediaType.APPLICATION_JSON)
						.content("{\"login\":\"" + name + "\",\"password\":\"falsch\"}"))
				.andExpect(status().isUnauthorized());
		mvc.perform(post("/api/auth/data/export").contentType(MediaType.APPLICATION_JSON)
						.content("{\"login\":\"" + name + "@TEST.de\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("sausage-daten-" + name)))
				.andExpect(jsonPath("$.profil.username").value(name));

		mvc.perform(post("/api/auth/data/delete").contentType(MediaType.APPLICATION_JSON)
						.content("{\"login\":\"" + name + "\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isNoContent());
		assertThat(jdbc.sql("SELECT COUNT(*) FROM app_user WHERE username = :u").param("u", name)
				.query(Long.class).single()).isZero();
	}
}
