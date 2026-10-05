package de.robertegenolf.sausageapi.spot;

import com.jayway.jsonpath.JsonPath;
import de.robertegenolf.sausageapi.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;

/**
 * Läuft gegen eine Postgres-Instanz aus Testcontainers (Docker nötig). Jeder Test legt eigene
 * Test-User mit zufälligem Namen an, damit sich die Tests nicht gegenseitig stören.
 */
@SpringBootTest(properties = {"app.rate-limit.registrations-per-hour=10000",
		"app.rate-limit.failed-logins-per-15-minutes=10000"})
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class SpotApiTests {

	private static final String PASSWORD = "geheimesPasswort";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

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

		// Spot 2,2 km entfernt: bei 2 km Radius nicht dabei, bei 2,5 km schon
		mvc.perform(get("/api/spots/nearby").param("latitude", "10.0").param("longitude", "20.0")
						.param("radiusKm", "2"))
				.andExpect(jsonPath("$[?(@.id == " + fern + ")]").doesNotExist());
		mvc.perform(get("/api/spots/nearby").param("latitude", "10.0").param("longitude", "20.0")
						.param("radiusKm", "2.5"))
				.andExpect(jsonPath("$[?(@.id == " + fern + ")]").exists());

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

	@Test
	void meineSpotsZeigtNurEigene() throws Exception {
		String owner = newName();
		String other = newName();
		register(owner);
		register(other);
		long spotId = createSpot(owner, "Meine " + owner);
		createSpot(other, "Fremde " + other);

		mvc.perform(get("/api/users/me/spots"))
				.andExpect(status().isUnauthorized());
		mvc.perform(get("/api/users/me/spots").with(httpBasic(owner, PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(spotId));
	}

	@Test
	void corsErlaubtKonfiguriertesFrontend() throws Exception {
		mvc.perform(options("/api/spots").header("Origin", "http://localhost:5173")
						.header("Access-Control-Request-Method", "POST"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
		mvc.perform(options("/api/spots").header("Origin", "https://boese.example")
						.header("Access-Control-Request-Method", "POST"))
				.andExpect(status().isForbidden());
	}

	@Test
	void apiDokumentationIstOeffentlich() throws Exception {
		mvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.info.title").value("Sausage API"))
				.andExpect(jsonPath("$.paths['/api/spots/{id}']").exists());
	}

	@Test
	void passwortAendern() throws Exception {
		String username = newName();
		register(username);

		mvc.perform(put("/api/users/me/password").with(httpBasic(username, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"currentPassword\":\"falsch\",\"newPassword\":\"neuesPasswort1\"}"))
				.andExpect(status().isBadRequest());
		mvc.perform(put("/api/users/me/password").with(httpBasic(username, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"neuesPasswort1\"}"))
				.andExpect(status().isNoContent());

		mvc.perform(get("/api/users/me").with(httpBasic(username, PASSWORD)))
				.andExpect(status().isUnauthorized());
		mvc.perform(get("/api/users/me").with(httpBasic(username, "neuesPasswort1")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("USER"));
	}

	@Test
	void adminModeriertUndVerwaltetKategorien() throws Exception {
		String user = newName();
		String admin = newName();
		register(user);
		register(admin);
		jdbc.sql("UPDATE app_user SET role = 'ADMIN' WHERE username = :u").param("u", admin).update();

		long spotId = createSpot(user, "Bude " + user);
		String comment = mvc.perform(post("/api/spots/" + spotId + "/comments").with(httpBasic(user, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Spam\"}"))
				.andReturn().getResponse().getHeader("Location");

		// Admin darf fremde Spots nicht bearbeiten, aber Kommentare und Spots löschen
		mvc.perform(put("/api/spots/" + spotId).with(httpBasic(admin, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content(spotJson("Admin war hier", 50.9, 6.9)))
				.andExpect(status().isForbidden());
		mvc.perform(delete(comment).with(httpBasic(admin, PASSWORD)))
				.andExpect(status().isNoContent());
		mvc.perform(delete("/api/spots/" + spotId).with(httpBasic(admin, PASSWORD)))
				.andExpect(status().isNoContent());
		mvc.perform(delete("/api/spots/" + spotId).with(httpBasic(admin, PASSWORD)))
				.andExpect(status().isNotFound());

		String code = "TEST_" + user.toUpperCase();
		String category = "{\"code\":\"" + code + "\",\"name\":\"Testkategorie\"}";
		mvc.perform(post("/api/categories").contentType(MediaType.APPLICATION_JSON).content(category))
				.andExpect(status().isUnauthorized());
		mvc.perform(post("/api/categories").with(httpBasic(user, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content(category))
				.andExpect(status().isForbidden());
		mvc.perform(post("/api/categories").with(httpBasic(admin, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content(category))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.code").value(code));
		mvc.perform(post("/api/categories").with(httpBasic(admin, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content(category))
				.andExpect(status().isConflict());
		mvc.perform(post("/api/categories").with(httpBasic(admin, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"klein\",\"name\":\"x\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.code").exists());
	}

	@Test
	void loginPerJwt() throws Exception {
		String username = newName();
		register(username);

		mvc.perform(post("/api/auth/token").with(httpBasic(username, "falsch")))
				.andExpect(status().isUnauthorized());
		String token = token(username);

		mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value(username));
		mvc.perform(post("/api/spots").header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON).content(spotJson("JWT " + username, 50.9, 6.9)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.createdBy").value(username));

		mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token + "x"))
				.andExpect(status().isUnauthorized());
		mvc.perform(get("/api/users/me").header("Authorization", "Bearer kein.gueltiges.token"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void jwtEnthaeltAdminRolle() throws Exception {
		String admin = newName();
		register(admin);
		jdbc.sql("UPDATE app_user SET role = 'ADMIN' WHERE username = :u").param("u", admin).update();

		String code = "JWT_" + admin.toUpperCase();
		mvc.perform(post("/api/categories").header("Authorization", "Bearer " + token(admin))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"code\":\"" + code + "\",\"name\":\"Per JWT\"}"))
				.andExpect(status().isCreated());
	}

	@Test
	void fotosHochladenAnzeigenUndLoeschen() throws Exception {
		String owner = newName();
		String other = newName();
		register(owner);
		register(other);
		long spotId = createSpot(owner, "Foto " + owner);
		BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		ImageIO.write(image, "png", buffer);
		byte[] png = buffer.toByteArray();

		mvc.perform(multipart("/api/spots/" + spotId + "/photos")
						.file(new MockMultipartFile("file", "bild.png", "image/png", png)))
				.andExpect(status().isUnauthorized());
		mvc.perform(multipart("/api/spots/" + spotId + "/photos").with(httpBasic(owner, PASSWORD))
						.file(new MockMultipartFile("file", "boese.png", "image/png", "<script>".getBytes())))
				.andExpect(status().isUnsupportedMediaType());
		mvc.perform(multipart("/api/spots/" + spotId + "/photos").with(httpBasic(owner, PASSWORD))
						.file(new MockMultipartFile("file", "kaputt.png", "image/png", Arrays.copyOf(png, 12))))
				.andExpect(status().isBadRequest());

		String location = mvc.perform(multipart("/api/spots/" + spotId + "/photos").with(httpBasic(owner, PASSWORD))
						.file(new MockMultipartFile("file", "bild.bin", "application/octet-stream", png)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.contentType").value("image/png"))
				.andExpect(jsonPath("$.uploadedBy").value(owner))
				.andReturn().getResponse().getHeader("Location");

		mvc.perform(get("/api/spots/" + spotId + "/photos"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].url").value(location))
				.andExpect(jsonPath("$[0].sizeBytes").value(png.length));
		mvc.perform(get(location))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Type", "image/png"))
				.andExpect(content().bytes(png));

		mvc.perform(delete(location).with(httpBasic(other, PASSWORD)))
				.andExpect(status().isForbidden());
		mvc.perform(delete(location).with(httpBasic(owner, PASSWORD)))
				.andExpect(status().isNoContent());
		mvc.perform(get(location))
				.andExpect(status().isNotFound());

		// Fotos verschwinden mit dem Spot
		mvc.perform(multipart("/api/spots/" + spotId + "/photos").with(httpBasic(other, PASSWORD))
						.file(new MockMultipartFile("file", "bild.png", "image/png", png)))
				.andExpect(status().isCreated());
		mvc.perform(delete("/api/spots/" + spotId).with(httpBasic(owner, PASSWORD)))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/spots/" + spotId + "/photos"))
				.andExpect(status().isNotFound());
	}

	@Test
	void accountLoeschenEntferntPersoenlicheDatenUndAnonymisiertSpots() throws Exception {
		String leaver = newName();
		String other = newName();
		register(leaver);
		register(other);
		long ownSpot = createSpot(leaver, "Bleibt " + leaver);
		long otherSpot = createSpot(other, "Fremd " + other);
		mvc.perform(post("/api/spots/" + otherSpot + "/ratings").with(httpBasic(leaver, PASSWORD))
				.contentType(MediaType.APPLICATION_JSON).content("{\"score\":1}"));
		mvc.perform(post("/api/spots/" + otherSpot + "/comments").with(httpBasic(leaver, PASSWORD))
				.contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Persönlich\"}"));
		String jwt = token(leaver);

		mvc.perform(delete("/api/users/me").with(httpBasic(leaver, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"falsch\"}"))
				.andExpect(status().isBadRequest());
		mvc.perform(delete("/api/users/me").with(httpBasic(leaver, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isNoContent());

		mvc.perform(get("/api/users/me").with(httpBasic(leaver, PASSWORD)))
				.andExpect(status().isUnauthorized());
		mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + jwt))
				.andExpect(status().isUnauthorized());
		mvc.perform(get("/api/spots/" + otherSpot))
				.andExpect(jsonPath("$.ratingCount").value(0));
		mvc.perform(get("/api/spots/" + otherSpot + "/comments"))
				.andExpect(jsonPath("$").isEmpty());
		mvc.perform(get("/api/spots/" + ownSpot))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.createdBy").doesNotExist());

		// Der Name ist wieder frei
		register(leaver);
		mvc.perform(put("/api/spots/" + ownSpot).with(httpBasic(leaver, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content(spotJson("Übernahme", 50.9, 6.9)))
				.andExpect(status().isForbidden());
	}

	private String token(String username) throws Exception {
		String json = mvc.perform(post("/api/auth/token").with(httpBasic(username, PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.expiresIn").value(3600))
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(json, "$.accessToken");
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
