package de.robertegenolf.sausageapi.spot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;

/**
 * Läuft gegen die lokale Postgres-DB (docker compose up). Jeder Lauf legt eigene
 * Test-User mit zufälligem Namen an, damit Wiederholungen sich nicht stören.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SpotApiTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void registrierungLiefertCreatedUndDoppelteNamenKonflikt() throws Exception {
		String username = newName();
		String body = """
				{"username":"%s","email":"%s@test.de","password":"geheimesPasswort"}
				""".formatted(username, username);

		mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated());
		mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isConflict());
	}

	@Test
	void lesenIstOeffentlichSchreibenBrauchtLogin() throws Exception {
		mvc.perform(get("/api/categories"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].code").exists());

		mvc.perform(post("/api/spots").contentType(MediaType.APPLICATION_JSON)
						.content(spotJson("Anonym", 50.9, 6.9)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void spotAnlegenBewertenKommentierenUndUmkreissuche() throws Exception {
		String username = newName();
		register(username);
		String name = "Bude " + username;

		String location = mvc.perform(post("/api/spots").with(httpBasic(username, "geheimesPasswort"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(spotJson(name, 50.9375, 6.9603)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value(name))
				.andReturn().getResponse().getHeader("Location");

		long spotId = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));

		mvc.perform(post("/api/spots/" + spotId + "/ratings").with(httpBasic(username, "geheimesPasswort"))
						.contentType(MediaType.APPLICATION_JSON).content("{\"score\":4}"))
				.andExpect(status().isNoContent());
		mvc.perform(post("/api/spots/" + spotId + "/ratings").with(httpBasic(username, "geheimesPasswort"))
						.contentType(MediaType.APPLICATION_JSON).content("{\"score\":2}"))
				.andExpect(status().isNoContent());

		mvc.perform(get("/api/spots/" + spotId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.averageRating").value(2.0))
				.andExpect(jsonPath("$.ratingCount").value(1));

		mvc.perform(post("/api/spots/" + spotId + "/comments").with(httpBasic(username, "geheimesPasswort"))
						.contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Lecker\"}"))
				.andExpect(status().isCreated());
		mvc.perform(get("/api/spots/" + spotId + "/comments"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].text").value("Lecker"));

		mvc.perform(get("/api/spots/nearby").param("latitude", "50.9375").param("longitude", "6.9603")
						.param("radiusKm", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.id == " + spotId + ")]").exists());

		mvc.perform(get("/api/spots/nearby").param("latitude", "52.52").param("longitude", "13.405")
						.param("radiusKm", "5"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.id == " + spotId + ")]").doesNotExist());
	}

	@Test
	void unbekannterSpotLiefertNotFound() throws Exception {
		mvc.perform(get("/api/spots/999999999"))
				.andExpect(status().isNotFound());
	}

	@Test
	void unbekannterPfadLiefertNotFoundStattLogin() throws Exception {
		mvc.perform(get("/api/gibts-nicht"))
				.andExpect(status().isNotFound());
	}

	private void register(String username) throws Exception {
		mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content("""
						{"username":"%s","email":"%s@test.de","password":"geheimesPasswort"}
						""".formatted(username, username)))
				.andExpect(status().isCreated());
	}

	private static String spotJson(String name, double lat, double lon) {
		return """
				{"categoryCode":"BRATWURST","name":"%s","city":"Köln","latitude":%s,"longitude":%s}
				""".formatted(name, lat, lon);
	}

	private static String newName() {
		return "t" + UUID.randomUUID().toString().substring(0, 8);
	}
}
