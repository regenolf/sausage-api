package de.robertegenolf.sausageapi.spot;

import com.jayway.jsonpath.JsonPath;
import de.robertegenolf.sausageapi.TestSecurityConfiguration;
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

import static de.robertegenolf.sausageapi.TestTokens.login;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Funktionen für die Beta: Hinweise, Teilnoten und Preis, Wurst-Tagebuch, Listen, Push-Geräte, DSGVO-Export. */
@SpringBootTest(properties = "app.rate-limit.writes-per-hour=10000")
@Import({TestcontainersConfiguration.class, TestSecurityConfiguration.class})
@AutoConfigureMockMvc
class BetaFeatureTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void stimmtWasNichtHinweiseUndModeration() throws Exception {
		String user = newName();
		String admin = newName();
		register(user);
		register(admin);
		jdbc.sql("UPDATE app_user SET role = 'ADMIN' WHERE username = :u").param("u", admin).update();
		long spotId = createSpot(user, "Hinweis " + user);

		mvc.perform(post("/api/spots/" + spotId + "/issues").contentType(MediaType.APPLICATION_JSON)
						.content("{\"type\":\"CLOSED\"}"))
				.andExpect(status().isUnauthorized());
		mvc.perform(post("/api/spots/" + spotId + "/issues").with(login(user))
						.contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"GIBTS_NICHT\"}"))
				.andExpect(status().isBadRequest());
		for (int i = 0; i < 3; i++) {
			mvc.perform(post("/api/spots/" + spotId + "/issues").with(login(user))
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"type\":\"WRONG_HOURS\",\"message\":\"Mittwochs zu\"}"))
					.andExpect(status().isCreated());
		}
		mvc.perform(post("/api/spots/" + spotId + "/issues").with(login(user))
						.contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"OTHER\"}"))
				.andExpect(status().isTooManyRequests());

		mvc.perform(get("/api/spots/" + spotId))
				.andExpect(jsonPath("$.openIssueCount").value(3));
		mvc.perform(get("/api/admin/issues").with(login(user)))
				.andExpect(status().isForbidden());
		String issues = mvc.perform(get("/api/admin/issues").with(login(admin)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		java.util.List<Number> issueIds = JsonPath.read(issues, "$[?(@.spotId == " + spotId + ")].id");
		Number issueId = issueIds.get(0);
		mvc.perform(get("/api/admin/issues").with(login(admin)))
				.andExpect(jsonPath("$[?(@.spotId == " + spotId + ")].openForSpot").value(org.hamcrest.Matchers.everyItem(
						org.hamcrest.Matchers.is(3))));

		mvc.perform(put("/api/admin/issues/" + issueId).with(login(admin)).contentType(MediaType.APPLICATION_JSON)
						.content("{\"status\":\"RESOLVED\",\"allForSpot\":true}"))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/spots/" + spotId))
				.andExpect(jsonPath("$.openIssueCount").value(0));
		mvc.perform(put("/api/admin/issues/" + issueId).with(login(admin)).contentType(MediaType.APPLICATION_JSON)
						.content("{\"status\":\"REJECTED\"}"))
				.andExpect(status().isConflict());
	}

	@Test
	void teilnotenUndPreisInBewertungUndBude() throws Exception {
		String a = newName();
		String b = newName();
		register(a);
		register(b);
		long spotId = createSpot(a, "Preis " + a);

		mvc.perform(post("/api/spots/" + spotId + "/ratings").with(login(a)).contentType(MediaType.APPLICATION_JSON)
						.content("{\"score\":5,\"priceCents\":20}"))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/api/spots/" + spotId + "/ratings").with(login(a)).contentType(MediaType.APPLICATION_JSON)
						.content("{\"score\":5,\"sausageScore\":6}"))
				.andExpect(status().isBadRequest());

		mvc.perform(post("/api/spots/" + spotId + "/ratings").with(login(a)).contentType(MediaType.APPLICATION_JSON)
						.content("{\"score\":5,\"sausageScore\":5,\"bunScore\":3,\"priceCents\":350}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.subScores.sausage").value(5.0))
				.andExpect(jsonPath("$.subScores.mustard").doesNotExist())
				.andExpect(jsonPath("$.priceCents").value(350));
		mvc.perform(post("/api/spots/" + spotId + "/ratings").with(login(b)).contentType(MediaType.APPLICATION_JSON)
						.content("{\"score\":3,\"sausageScore\":3,\"mustardScore\":4,\"priceCents\":450}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.subScores.sausage").value(4.0))
				.andExpect(jsonPath("$.subScores.mustard").value(4.0))
				.andExpect(jsonPath("$.subScores.count").value(2))
				.andExpect(jsonPath("$.priceCents").value(400))
				.andExpect(jsonPath("$.priceCount").value(2));

		mvc.perform(get("/api/spots/" + spotId + "/ratings/me").with(login(b)))
				.andExpect(jsonPath("$.mustardScore").value(4))
				.andExpect(jsonPath("$.bunScore").doesNotExist())
				.andExpect(jsonPath("$.priceCents").value(450));

		// Ohne Teilnoten bleibt alles wie bisher (ältere App-Versionen)
		mvc.perform(post("/api/spots/" + spotId + "/ratings").with(login(b)).contentType(MediaType.APPLICATION_JSON)
						.content("{\"score\":4}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.priceCents").value(350))
				.andExpect(jsonPath("$.subScores.count").value(1));

		mvc.perform(get("/api/spots").param("q", "Preis " + a))
				.andExpect(jsonPath("$[0].openingHours").value("Mo-Fr 10:00-18:00"));
	}

	@Test
	void wurstTagebuchUndAbzeichen() throws Exception {
		String user = newName();
		register(user);
		long first = createSpot(user, "Tagebuch A " + user);
		long second = createSpot(user, "Tagebuch B " + user);

		mvc.perform(post("/api/spots/" + first + "/checkins")).andExpect(status().isUnauthorized());
		String created = mvc.perform(post("/api/spots/" + first + "/checkins").with(login(user)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.spot.id").value(first))
				.andReturn().getResponse().getContentAsString();
		mvc.perform(post("/api/spots/" + first + "/checkins").with(login(user)))
				.andExpect(status().isConflict());
		mvc.perform(post("/api/spots/" + second + "/checkins").with(login(user)))
				.andExpect(status().isCreated());
		mvc.perform(post("/api/spots/999999999/checkins").with(login(user)))
				.andExpect(status().isNotFound());

		mvc.perform(get("/api/spots/" + first + "/checkins/me").with(login(user)))
				.andExpect(jsonPath("$.count").value(1))
				.andExpect(jsonPath("$.lastEatenOn").exists());
		mvc.perform(get("/api/users/me/checkins").with(login(user)))
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].spot.id").value(second));
		mvc.perform(get("/api/users/me/stats").with(login(user)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.checkIns").value(2))
				.andExpect(jsonPath("$.spots").value(2))
				.andExpect(jsonPath("$.cities").value(1))
				.andExpect(jsonPath("$.spotsCreated").value(2))
				.andExpect(jsonPath("$.badges[?(@.code == 'FIRST_BITE')].earned").value(true))
				.andExpect(jsonPath("$.badges[?(@.code == 'PIONEER')].earned").value(true))
				.andExpect(jsonPath("$.badges[?(@.code == 'CONNOISSEUR')].progress").value(2))
				.andExpect(jsonPath("$.badges[?(@.code == 'CONNOISSEUR')].earned").value(false));

		Number id = JsonPath.read(created, "$.id");
		String other = newName();
		register(other);
		mvc.perform(delete("/api/users/me/checkins/" + id).with(login(other)))
				.andExpect(status().isNotFound());
		mvc.perform(delete("/api/users/me/checkins/" + id).with(login(user)))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/users/me/stats").with(login(user)))
				.andExpect(jsonPath("$.checkIns").value(1));
	}

	@Test
	void eigeneListenPrivatUndOeffentlich() throws Exception {
		String owner = newName();
		String other = newName();
		register(owner);
		register(other);
		long spotId = createSpot(owner, "Liste " + owner);

		String created = mvc.perform(post("/api/users/me/lists").with(login(owner))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"Meine Top 5\",\"description\":\"Erfurt\",\"isPublic\":false}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.spotCount").value(0))
				.andReturn().getResponse().getContentAsString();
		long listId = ((Number) JsonPath.read(created, "$.id")).longValue();

		mvc.perform(put("/api/lists/" + listId + "/spots/" + spotId).with(login(owner)))
				.andExpect(status().isNoContent());
		mvc.perform(put("/api/lists/" + listId + "/spots/" + spotId).with(login(owner)))
				.andExpect(status().isNoContent());
		mvc.perform(put("/api/lists/" + listId + "/spots/" + spotId).with(login(other)))
				.andExpect(status().isNotFound());

		mvc.perform(get("/api/users/me/lists").param("spotId", String.valueOf(spotId)).with(login(owner)))
				.andExpect(jsonPath("$[0].containsSpot").value(true))
				.andExpect(jsonPath("$[0].spotCount").value(1));

		// Privat: nur der Besitzer
		mvc.perform(get("/api/lists/" + listId)).andExpect(status().isNotFound());
		mvc.perform(get("/api/lists/" + listId).with(login(other))).andExpect(status().isNotFound());
		mvc.perform(get("/api/lists/" + listId).with(login(owner)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mine").value(true))
				.andExpect(jsonPath("$.spots[0].id").value(spotId));

		// Öffentlich: jeder mit Link, und meldbar
		mvc.perform(put("/api/lists/" + listId).with(login(owner)).contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"Top 5\",\"isPublic\":true}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.description").doesNotExist());
		mvc.perform(get("/api/lists/" + listId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.owner").value(owner))
				.andExpect(jsonPath("$.mine").value(false));
		mvc.perform(post("/api/reports").with(login(other)).contentType(MediaType.APPLICATION_JSON)
						.content("{\"targetType\":\"LIST\",\"targetId\":" + listId + ",\"reason\":\"SPAM\"}"))
				.andExpect(status().isCreated());

		mvc.perform(delete("/api/lists/" + listId + "/spots/" + spotId).with(login(owner)))
				.andExpect(status().isNoContent());
		mvc.perform(delete("/api/lists/" + listId).with(login(other)))
				.andExpect(status().isNotFound());
		mvc.perform(delete("/api/lists/" + listId).with(login(owner)))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/lists/" + listId).with(login(owner))).andExpect(status().isNotFound());
	}

	@Test
	void pushGeraeteExportUndLoeschung() throws Exception {
		String user = newName();
		register(user);
		long spotId = createSpot(user, "Export " + user);
		String token = "fcm-" + UUID.randomUUID();

		mvc.perform(put("/api/users/me/devices").with(login(user)).contentType(MediaType.APPLICATION_JSON)
						.content("{\"token\":\"" + token + "\",\"platform\":\"WINDOWS\"}"))
				.andExpect(status().isBadRequest());
		mvc.perform(put("/api/users/me/devices").with(login(user)).contentType(MediaType.APPLICATION_JSON)
						.content("{\"token\":\"" + token + "\",\"platform\":\"ANDROID\"}"))
				.andExpect(status().isNoContent());
		mvc.perform(put("/api/users/me/devices").with(login(user)).contentType(MediaType.APPLICATION_JSON)
						.content("{\"token\":\"" + token + "\",\"platform\":\"ANDROID\"}"))
				.andExpect(status().isNoContent());
		assertThat(jdbc.sql("SELECT COUNT(*) FROM push_device WHERE token = :t").param("t", token)
				.query(Long.class).single()).isEqualTo(1);

		// Eine fremde Bewertung löst eine (hier nur protokollierte) Benachrichtigung aus
		String rater = newName();
		register(rater);
		mvc.perform(post("/api/spots/" + spotId + "/ratings").with(login(rater))
						.contentType(MediaType.APPLICATION_JSON).content("{\"score\":4}"))
				.andExpect(status().isOk());

		mvc.perform(post("/api/spots/" + spotId + "/checkins").with(login(user)))
				.andExpect(status().isCreated());
		mvc.perform(post("/api/users/me/lists").with(login(user)).contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"Export-Liste\"}"))
				.andExpect(status().isCreated());
		mvc.perform(get("/api/users/me/export").with(login(user)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wurst_tagebuch.length()").value(1))
				.andExpect(jsonPath("$.listen[0].titel").value("Export-Liste"))
				.andExpect(jsonPath("$.geraete_fuer_benachrichtigungen[0].plattform").value("ANDROID"));

		mvc.perform(post("/api/users/me/devices/remove").with(login(user)).contentType(MediaType.APPLICATION_JSON)
						.content("{\"token\":\"" + token + "\"}"))
				.andExpect(status().isNoContent());
		assertThat(jdbc.sql("SELECT COUNT(*) FROM push_device WHERE token = :t").param("t", token)
				.query(Long.class).single()).isZero();

		mvc.perform(delete("/api/users/me").with(login(user)))
				.andExpect(status().isNoContent());
		assertThat(jdbc.sql("SELECT COUNT(*) FROM check_in ci JOIN spot s ON s.id = ci.spot_id WHERE s.id = :id")
				.param("id", spotId).query(Long.class).single()).isZero();
	}

	private long createSpot(String username, String name) throws Exception {
		String location = mvc.perform(post("/api/spots").with(login(username))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"categoryCode":"BRATWURST","name":"%s","city":"Erfurt","latitude":50.978,"longitude":11.029,
								 "openingHours":"Mo-Fr 10:00-18:00"}
								""".formatted(name)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getHeader("Location");
		return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
	}

	private void register(String username) throws Exception {
		mvc.perform(post("/api/users/me/terms").with(login(username)))
				.andExpect(status().isOk());
	}

	private static String newName() {
		return "t" + UUID.randomUUID().toString().substring(0, 8);
	}
}
