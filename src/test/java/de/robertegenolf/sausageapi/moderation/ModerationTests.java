package de.robertegenolf.sausageapi.moderation;

import com.jayway.jsonpath.JsonPath;
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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Melde- und Abhilfeverfahren (DSA Art. 16) und Admin-Moderation. */
@SpringBootTest(properties = {"app.rate-limit.registrations-per-hour=10000",
		"app.rate-limit.failed-logins-per-15-minutes=10000",
		"app.rate-limit.failed-logins-per-ip-per-15-minutes=10000", "app.rate-limit.writes-per-hour=10000"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ModerationTests {

	private static final String PASSWORD = "geheimesPasswort";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void meldenMitUndOhneAnmeldung() throws Exception {
		String owner = register();
		long spotId = createSpot(owner);

		mvc.perform(post("/api/reports").contentType(MediaType.APPLICATION_JSON)
						.content(report("SPOT", spotId, "SPAM", null)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").exists());
		mvc.perform(post("/api/reports").contentType(MediaType.APPLICATION_JSON)
						.content(report("SPOT", spotId, "SPAM", "melder@test.de")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("OPEN"));
		String reporter = register();
		mvc.perform(post("/api/reports").with(httpBasic(reporter, PASSWORD)).contentType(MediaType.APPLICATION_JSON)
						.content(report("SPOT", spotId, "WRONG_INFO", null)))
				.andExpect(status().isCreated());

		mvc.perform(post("/api/reports").contentType(MediaType.APPLICATION_JSON)
						.content(report("SPOT", 999999999, "SPAM", "melder@test.de")))
				.andExpect(status().isNotFound());
		mvc.perform(post("/api/reports").contentType(MediaType.APPLICATION_JSON)
						.content(report("SPOT", spotId, "GIBTS_NICHT", "melder@test.de")))
				.andExpect(status().isBadRequest());
	}

	@Test
	void nurAdminsSehenUndEntscheidenMeldungen() throws Exception {
		String author = register();
		String reporter = register();
		String other = register();
		String admin = register();
		jdbc.sql("UPDATE app_user SET role = 'ADMIN' WHERE username = :u").param("u", admin).update();
		long spotId = createSpot(author);
		String location = mvc.perform(post("/api/spots/" + spotId + "/comments").with(httpBasic(author, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Beleidigung " + author + "\"}"))
				.andReturn().getResponse().getHeader("Location");
		long commentId = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));

		long first = reportId(mvc.perform(post("/api/reports").with(httpBasic(reporter, PASSWORD))
				.contentType(MediaType.APPLICATION_JSON).content(report("COMMENT", commentId, "INSULT", null)))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
		long second = reportId(mvc.perform(post("/api/reports").contentType(MediaType.APPLICATION_JSON)
				.content(report("COMMENT", commentId, "ILLEGAL", "zweiter@test.de")))
				.andReturn().getResponse().getContentAsString());
		long spotReport = reportId(mvc.perform(post("/api/reports").with(httpBasic(reporter, PASSWORD))
				.contentType(MediaType.APPLICATION_JSON).content(report("SPOT", spotId, "WRONG_INFO", null)))
				.andReturn().getResponse().getContentAsString());

		mvc.perform(get("/api/admin/reports")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/admin/reports").with(httpBasic(other, PASSWORD))).andExpect(status().isForbidden());
		mvc.perform(put("/api/admin/reports/" + first).with(httpBasic(other, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"REMOVED\"}"))
				.andExpect(status().isForbidden());

		mvc.perform(get("/api/admin/reports").with(httpBasic(admin, PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.id == " + first + ")].author").value(author))
				.andExpect(jsonPath("$[?(@.id == " + first + ")].contentPreview").value("Beleidigung " + author))
				.andExpect(jsonPath("$[?(@.id == " + first + ")].reporter").value(reporter))
				.andExpect(jsonPath("$[?(@.id == " + second + ")].reporterEmail").value("zweiter@test.de"));

		// Entfernen löscht den Kommentar und erledigt beide Meldungen dazu
		mvc.perform(put("/api/admin/reports/" + first).with(httpBasic(admin, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"decision\":\"REMOVED\",\"note\":\"Beleidigung entfernt\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("REMOVED"))
				.andExpect(jsonPath("$.decidedBy").value(admin))
				.andExpect(jsonPath("$.contentPreview").doesNotExist());
		mvc.perform(get("/api/spots/" + spotId + "/comments")).andExpect(jsonPath("$").isEmpty());
		mvc.perform(get("/api/admin/reports").param("status", "ALL").with(httpBasic(admin, PASSWORD)))
				.andExpect(jsonPath("$[?(@.id == " + second + ")].status").value("REMOVED"));
		mvc.perform(put("/api/admin/reports/" + second).with(httpBasic(admin, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"REJECTED\"}"))
				.andExpect(status().isConflict());

		// Ablehnen lässt den Inhalt stehen; der Melder sieht die Entscheidung
		mvc.perform(put("/api/admin/reports/" + spotReport).with(httpBasic(admin, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"decision\":\"REJECTED\",\"note\":\"Angaben sind korrekt\"}"))
				.andExpect(status().isOk());
		mvc.perform(get("/api/spots/" + spotId)).andExpect(status().isOk());
		mvc.perform(get("/api/users/me/reports").with(httpBasic(reporter, PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[?(@.id == " + spotReport + ")].decisionNote").value("Angaben sind korrekt"))
				.andExpect(jsonPath("$[?(@.id == " + first + ")].status").value("REMOVED"));
	}

	@Test
	void adminSperrtUndEntsperrtAccounts() throws Exception {
		String user = register();
		String admin = register();
		jdbc.sql("UPDATE app_user SET role = 'ADMIN' WHERE username = :u").param("u", admin).update();
		String json = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + user + "\",\"password\":\"" + PASSWORD + "\"}"))
				.andReturn().getResponse().getContentAsString();
		String token = JsonPath.read(json, "$.token");

		mvc.perform(put("/api/admin/users/" + user + "/status").with(httpBasic(user, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
				.andExpect(status().isForbidden());
		mvc.perform(put("/api/admin/users/" + user + "/status").with(httpBasic(admin, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/users/me").with(httpBasic(user, PASSWORD))).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + user + "\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isUnauthorized());

		mvc.perform(put("/api/admin/users/" + user + "/status").with(httpBasic(admin, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/users/me").with(httpBasic(user, PASSWORD))).andExpect(status().isOk());

		mvc.perform(put("/api/admin/users/" + admin + "/status").with(httpBasic(admin, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
				.andExpect(status().isBadRequest());
		mvc.perform(put("/api/admin/users/niemand" + user + "/status").with(httpBasic(admin, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
				.andExpect(status().isNotFound());
	}

	private String register() throws Exception {
		String name = "m" + UUID.randomUUID().toString().substring(0, 8);
		mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"%s\",\"email\":\"%s@test.de\",\"password\":\"%s\"}".formatted(name, name, PASSWORD)))
				.andExpect(status().isCreated());
		return name;
	}

	private long createSpot(String username) throws Exception {
		String location = mvc.perform(post("/api/spots").with(httpBasic(username, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"categoryCode\":\"BRATWURST\",\"name\":\"Gemeldet " + username + "\",\"latitude\":50.9,\"longitude\":6.9}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getHeader("Location");
		return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
	}

	private static String report(String type, long id, String reason, String email) {
		return "{\"targetType\":\"%s\",\"targetId\":%d,\"reason\":\"%s\",\"message\":\"Bitte prüfen\"%s}"
				.formatted(type, id, reason, email == null ? "" : ",\"email\":\"" + email + "\"");
	}

	private static long reportId(String json) {
		return ((Number) JsonPath.read(json, "$.id")).longValue();
	}
}
