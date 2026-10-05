package de.robertegenolf.sausageapi.spot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

	private static final String PASSWORD = "geheimesPasswort";

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
				.andExpect(jsonPath("$[0].text").value("Lecker"))
				.andExpect(jsonPath("$[0].username").value(username));

		mvc.perform(get("/api/spots/nearby").param("latitude", "50.9375").param("longitude", "6.9603")
						.param("radiusKm", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.id == " + spotId + ")]").exists())
				.andExpect(jsonPath("$[?(@.id == " + spotId + ")].distanceKm").value(0.0));

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

	@Test
	void eigenesProfilBrauchtLogin() throws Exception {
		String username = newName();
		register(username);

		mvc.perform(get("/api/users/me"))
				.andExpect(status().isUnauthorized());
		mvc.perform(get("/api/users/me").with(httpBasic(username, PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value(username))
				.andExpect(jsonPath("$.email").value(username + "@test.de"))
				.andExpect(jsonPath("$.passwordHash").doesNotExist());
	}

	@Test
	void nurErstellerDarfSpotBearbeitenUndLoeschen() throws Exception {
		String owner = newName();
		String other = newName();
		register(owner);
		register(other);
		long spotId = createSpot(owner, "Bude " + owner);

		mvc.perform(get("/api/spots/" + spotId))
				.andExpect(jsonPath("$.createdBy").value(owner));

		String updated = spotJson("Umbenannt " + owner, 50.94, 6.95);
		mvc.perform(put("/api/spots/" + spotId).with(httpBasic(other, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content(updated))
				.andExpect(status().isForbidden());
		mvc.perform(put("/api/spots/" + spotId)
						.contentType(MediaType.APPLICATION_JSON).content(updated))
				.andExpect(status().isUnauthorized());
		mvc.perform(put("/api/spots/" + spotId).with(httpBasic(owner, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content(updated))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Umbenannt " + owner))
				.andExpect(jsonPath("$.latitude").value(50.94));

		// Bewertungen und Kommentare anderer User dürfen das Löschen nicht blockieren
		mvc.perform(post("/api/spots/" + spotId + "/ratings").with(httpBasic(other, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content("{\"score\":5}"))
				.andExpect(status().isNoContent());
		mvc.perform(post("/api/spots/" + spotId + "/comments").with(httpBasic(other, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Super\"}"))
				.andExpect(status().isCreated());

		mvc.perform(delete("/api/spots/" + spotId).with(httpBasic(other, PASSWORD)))
				.andExpect(status().isForbidden());
		mvc.perform(delete("/api/spots/" + spotId).with(httpBasic(owner, PASSWORD)))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/spots/" + spotId))
				.andExpect(status().isNotFound());
		mvc.perform(put("/api/spots/" + spotId).with(httpBasic(owner, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content(updated))
				.andExpect(status().isNotFound());
	}

	@Test
	void eigeneBewertungLesenUndZuruecknehmen() throws Exception {
		String username = newName();
		register(username);
		long spotId = createSpot(username, "Bude " + username);

		mvc.perform(get("/api/spots/" + spotId + "/ratings/me"))
				.andExpect(status().isUnauthorized());
		mvc.perform(get("/api/spots/" + spotId + "/ratings/me").with(httpBasic(username, PASSWORD)))
				.andExpect(status().isNotFound());

		mvc.perform(post("/api/spots/" + spotId + "/ratings").with(httpBasic(username, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content("{\"score\":3}"))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/spots/" + spotId + "/ratings/me").with(httpBasic(username, PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.score").value(3));

		mvc.perform(delete("/api/spots/" + spotId + "/ratings/me").with(httpBasic(username, PASSWORD)))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/spots/" + spotId))
				.andExpect(jsonPath("$.ratingCount").value(0))
				.andExpect(jsonPath("$.averageRating").doesNotExist());
	}

	@Test
	void nurAutorDarfKommentarLoeschen() throws Exception {
		String author = newName();
		String other = newName();
		register(author);
		register(other);
		long spotId = createSpot(author, "Bude " + author);

		String location = mvc.perform(post("/api/spots/" + spotId + "/comments").with(httpBasic(author, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Weg damit\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getHeader("Location");

		mvc.perform(delete(location).with(httpBasic(other, PASSWORD)))
				.andExpect(status().isForbidden());
		mvc.perform(delete(location).with(httpBasic(author, PASSWORD)))
				.andExpect(status().isNoContent());
		mvc.perform(delete(location).with(httpBasic(author, PASSWORD)))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/spots/" + spotId + "/comments"))
				.andExpect(jsonPath("$").isEmpty());
	}

	@Test
	void umkreissucheSortiertNachEntfernung() throws Exception {
		String username = newName();
		register(username);
		// Abgelegene Koordinaten, damit andere Test-Spots nicht dazwischenfunken
		long fern = createSpot(username, "Fern " + username, 10.02, 20.0);
		long nah = createSpot(username, "Nah " + username, 10.001, 20.0);

		mvc.perform(get("/api/spots/nearby").param("latitude", "10.0").param("longitude", "20.0")
						.param("radiusKm", "5").param("category", "BRATWURST"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.id == " + nah + ")]").exists())
				.andExpect(jsonPath("$[?(@.id == " + fern + ")]").exists())
				.andExpect(result -> {
					String json = result.getResponse().getContentAsString();
					if (json.indexOf("\"id\":" + nah + ",") > json.indexOf("\"id\":" + fern + ",")) {
						throw new AssertionError("Näherer Spot muss zuerst kommen: " + json);
					}
				});

		mvc.perform(get("/api/spots/nearby").param("latitude", "10.0").param("longitude", "20.0")
						.param("radiusKm", "5").param("category", "GIBTS_NICHT"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isEmpty());
	}

	@Test
	void spotListeIstPaginiertUndDurchsuchbar() throws Exception {
		String username = newName();
		register(username);
		for (int i = 1; i <= 3; i++) {
			createSpot(username, "Suche " + username + " " + i);
		}

		mvc.perform(get("/api/spots").param("q", username).param("size", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(2))
				.andExpect(jsonPath("$.content[0].name").value("Suche " + username + " 1"))
				.andExpect(jsonPath("$.totalElements").value(3))
				.andExpect(jsonPath("$.totalPages").value(2));
		mvc.perform(get("/api/spots").param("q", username.toUpperCase()).param("size", "2").param("page", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].name").value("Suche " + username + " 3"));
		mvc.perform(get("/api/spots").param("q", "%" + username))
				.andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void fehlerLiefernProblemDetailsMitMeldung() throws Exception {
		mvc.perform(get("/api/spots/999999999"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.detail").value("Spot 999999999 nicht gefunden"));

		String username = newName();
		register(username);
		mvc.perform(post("/api/spots").with(httpBasic(username, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"categoryCode\":\"BRATWURST\",\"name\":\"\",\"latitude\":91,\"longitude\":6.9}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.name").exists())
				.andExpect(jsonPath("$.errors.latitude").exists());

		mvc.perform(get("/api/spots").param("size", "500"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.size").exists());
		mvc.perform(get("/api/spots/nearby").param("latitude", "95").param("longitude", "6.9"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.latitude").exists());
	}

	private long createSpot(String username, String name) throws Exception {
		return createSpot(username, name, 50.9375, 6.9603);
	}

	private long createSpot(String username, String name, double lat, double lon) throws Exception {
		String location = mvc.perform(post("/api/spots").with(httpBasic(username, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON)
						.content(spotJson(name, lat, lon)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getHeader("Location");
		return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
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
