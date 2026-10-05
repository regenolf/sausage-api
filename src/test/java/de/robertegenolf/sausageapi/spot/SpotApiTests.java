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
import java.util.List;
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
		"app.rate-limit.failed-logins-per-15-minutes=10000",
		"app.rate-limit.failed-logins-per-ip-per-15-minutes=10000", "app.rate-limit.writes-per-hour=10000"})
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
				{"username":"%s","email":"%s@test.de","acceptTerms":true,"password":"geheimesPasswort"}
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
				.andExpect(status().isOk());
		mvc.perform(post("/api/spots/" + spotId + "/ratings").with(httpBasic(username, "geheimesPasswort"))
						.contentType(MediaType.APPLICATION_JSON).content("{\"score\":2}"))
				.andExpect(status().isOk());

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
				.andExpect(status().isOk());
		mvc.perform(post("/api/spots/" + spotId + "/comments").with(httpBasic(other, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Super\"}"))
				.andExpect(status().isCreated());

		mvc.perform(delete("/api/spots/" + spotId).with(httpBasic(other, PASSWORD)))
				.andExpect(status().isForbidden());
		// Beiträge anderer darf der Ersteller nicht mitlöschen
		mvc.perform(delete("/api/spots/" + spotId).with(httpBasic(owner, PASSWORD)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Moderation")));
		mvc.perform(delete("/api/spots/" + spotId + "/ratings/me").with(httpBasic(other, PASSWORD)))
				.andExpect(status().isNoContent());
		String commentJson = mvc.perform(get("/api/spots/" + spotId + "/comments")).andReturn().getResponse().getContentAsString();
		Integer commentId = JsonPath.<List<Integer>>read(commentJson, "$[*].id").get(0);
		mvc.perform(delete("/api/spots/" + spotId + "/comments/" + commentId).with(httpBasic(other, PASSWORD)))
				.andExpect(status().isNoContent());
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
				.andExpect(status().isOk());
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

		mvc.perform(get("/api/spots").param("q", username))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(3))
				.andExpect(header().string("X-Total-Count", "3"));
		mvc.perform(get("/api/spots").param("q", username).param("size", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].name").value("Suche " + username + " 1"))
				.andExpect(header().string("X-Total-Count", "3"));
		mvc.perform(get("/api/spots").param("q", username.toUpperCase()).param("size", "2").param("page", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].name").value("Suche " + username + " 3"));
		mvc.perform(get("/api/spots").param("q", "%" + username))
				.andExpect(jsonPath("$").isEmpty())
				.andExpect(header().string("X-Total-Count", "0"));
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
		// Ionic-Entwicklungsserver und Capacitor-App auf iOS
		for (String origin : new String[] {"http://localhost:8100", "capacitor://localhost"}) {
			mvc.perform(options("/api/spots").header("Origin", origin)
							.header("Access-Control-Request-Method", "GET"))
					.andExpect(status().isOk())
					.andExpect(header().string("Access-Control-Allow-Origin", origin));
		}
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
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.token").exists());

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

		// Admin darf fremde Spots korrigieren sowie Kommentare und Spots löschen
		mvc.perform(put("/api/spots/" + spotId).with(httpBasic(admin, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content(spotJson("Korrigiert", 50.9, 6.9)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Korrigiert"))
				.andExpect(jsonPath("$.createdBy").value(user));
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
		mvc.perform(get("/api/spots/" + spotId))
				.andExpect(jsonPath("$.photos[0].url").value(location));
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

		// Der Spot-Ersteller darf auch fremde Fotos an seinem Spot löschen
		String fremd = mvc.perform(multipart("/api/spots/" + spotId + "/photos").with(httpBasic(other, PASSWORD))
						.file(new MockMultipartFile("file", "bild.png", "image/png", png)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getHeader("Location");
		mvc.perform(delete(fremd).with(httpBasic(owner, PASSWORD)))
				.andExpect(status().isNoContent());

		// Fotos verschwinden mit dem Spot
		mvc.perform(multipart("/api/spots/" + spotId + "/photos").with(httpBasic(owner, PASSWORD))
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
						.contentType(MediaType.APPLICATION_JSON).content("{\"acceptTerms\":true,\"password\":\"falsch\"}"))
				.andExpect(status().isBadRequest());
		mvc.perform(delete("/api/users/me").with(httpBasic(leaver, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content("{\"acceptTerms\":true,\"password\":\"" + PASSWORD + "\"}"))
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

	@Test
	void loginUndRegistrierungWieImFrontend() throws Exception {
		String name = newName();
		String email = name + "@frontend.de";

		String json = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"%s\",\"acceptTerms\":true,\"password\":\"%s\",\"displayName\":\"%s\"}"
								.formatted(email, PASSWORD, name)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.token").exists())
				.andReturn().getResponse().getContentAsString();
		String registered = JsonPath.read(json, "$.token");
		mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + registered))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value(name))
				.andExpect(jsonPath("$.email").value(email));

		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"%s\",\"acceptTerms\":true,\"password\":\"%s\",\"displayName\":\"anders%s\"}"
								.formatted(email, PASSWORD, name)))
				.andExpect(status().isConflict());

		// Anmeldung mit E-Mail (Groß-/Kleinschreibung egal) oder Benutzername
		for (String login : new String[] {email.toUpperCase(), name}) {
			String loginJson = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
							.content("{\"email\":\"%s\",\"acceptTerms\":true,\"password\":\"%s\"}".formatted(login, PASSWORD)))
					.andExpect(status().isOk())
					.andReturn().getResponse().getContentAsString();
			String token = JsonPath.read(loginJson, "$.token");
			String payload = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]));
			org.assertj.core.api.Assertions.assertThat(JsonPath.<String>read(payload, "$.sub")).isEqualTo(name);
			org.assertj.core.api.Assertions.assertThat(JsonPath.<String>read(payload, "$.name")).isEqualTo(name);
		}
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"%s\",\"acceptTerms\":true,\"password\":\"falsch\"}".formatted(email)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.detail").value("E-Mail oder Passwort ist falsch"));
	}

	@Test
	void fremderAnzeigenameKannEmailLoginNichtBlockieren() throws Exception {
		String victim = newName();
		String email = victim + "@opfer.de";
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"%s\",\"acceptTerms\":true,\"password\":\"%s\",\"displayName\":\"%s\"}".formatted(email, PASSWORD, victim)))
				.andExpect(status().isCreated());

		// Angreifer versucht, die E-Mail des Opfers als Anzeigenamen zu belegen
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"%s\",\"acceptTerms\":true,\"password\":\"%s\",\"displayName\":\"%s\"}".formatted("x" + email, PASSWORD, email)))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"%s\",\"email\":\"%s\",\"acceptTerms\":true,\"password\":\"%s\"}".formatted(email, "y" + email, PASSWORD)))
				.andExpect(status().isBadRequest());
		// gleiche E-Mail in anderer Schreibweise ist vergeben
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"%s\",\"acceptTerms\":true,\"password\":\"%s\",\"displayName\":\"anders%s\"}".formatted(email.toUpperCase(), PASSWORD, victim)))
				.andExpect(status().isConflict());

		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"%s\",\"acceptTerms\":true,\"password\":\"%s\"}".formatted(email, PASSWORD)))
				.andExpect(status().isOk());
	}

	@Test
	void bewertungenMitKommentarWieImFrontend() throws Exception {
		String owner = newName();
		String other = newName();
		register(owner);
		register(other);
		long spotId = createSpot(owner, "Bewertet " + owner);

		mvc.perform(post("/api/spots/" + spotId + "/ratings").with(httpBasic(other, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content("{\"score\":5,\"comment\":\"Top Wurst\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(spotId))
				.andExpect(jsonPath("$.averageRating").value(5.0))
				.andExpect(jsonPath("$.photos").isArray());
		mvc.perform(post("/api/spots/" + spotId + "/ratings").with(httpBasic(owner, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content("{\"score\":3,\"comment\":\"  \"}"))
				.andExpect(jsonPath("$.averageRating").value(4.0));

		String json = mvc.perform(get("/api/spots/" + spotId + "/ratings"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[?(@.author == '" + other + "')].comment").value("Top Wurst"))
				.andExpect(jsonPath("$[?(@.author == '" + other + "')].authorName").value(other))
				.andExpect(jsonPath("$[?(@.author == '" + owner + "')].comment").value((Object) null))
				.andReturn().getResponse().getContentAsString();
		Integer otherRating = JsonPath.<List<Integer>>read(json, "$[?(@.author == '" + other + "')].id").get(0);

		mvc.perform(put("/api/spots/" + spotId + "/ratings/" + otherRating).with(httpBasic(owner, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content("{\"score\":1}"))
				.andExpect(status().isForbidden());
		mvc.perform(put("/api/spots/" + spotId + "/ratings/" + otherRating).with(httpBasic(other, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content("{\"score\":1,\"comment\":\"Doch nicht\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.averageRating").value(2.0));
		mvc.perform(get("/api/spots/" + spotId + "/ratings/me").with(httpBasic(other, PASSWORD)))
				.andExpect(jsonPath("$.comment").value("Doch nicht"));

		mvc.perform(delete("/api/spots/" + spotId + "/ratings/" + otherRating).with(httpBasic(owner, PASSWORD)))
				.andExpect(status().isForbidden());
		mvc.perform(delete("/api/spots/" + spotId + "/ratings/" + otherRating).with(httpBasic(other, PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ratingCount").value(1));
		mvc.perform(delete("/api/spots/" + spotId + "/ratings/" + otherRating).with(httpBasic(other, PASSWORD)))
				.andExpect(status().isNotFound());
	}

	@Test
	void tokensWerdenBeiPasswortaenderungUndAbmeldungUngueltig() throws Exception {
		String username = newName();
		register(username);
		String alt = token(username);
		String zweitesGeraet = token(username);

		String pwJson = mvc.perform(put("/api/users/me/password").header("Authorization", "Bearer " + alt)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"neuesPasswort1\"}"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		// das aktuelle Gerät bekommt ein neues, gültiges Token
		String nachAenderung = JsonPath.read(pwJson, "$.token");
		mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + nachAenderung))
				.andExpect(status().isOk());
		mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + alt))
				.andExpect(status().isUnauthorized());
		mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + zweitesGeraet))
				.andExpect(status().isUnauthorized());

		String json = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + username + "\",\"acceptTerms\":true,\"password\":\"neuesPasswort1\"}"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		String neu = JsonPath.read(json, "$.token");
		mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + neu))
				.andExpect(status().isOk());

		// Überall abmelden
		mvc.perform(post("/api/auth/logout-all").header("Authorization", "Bearer " + neu))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + neu))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void rollenaenderungWirktSofortAuchMitBestehendemToken() throws Exception {
		String username = newName();
		register(username);
		String jwt = token(username);
		String code = "ROLLE_" + username.toUpperCase();
		String category = "{\"code\":\"" + code + "\",\"name\":\"Rollentest\"}";

		mvc.perform(post("/api/categories").header("Authorization", "Bearer " + jwt)
						.contentType(MediaType.APPLICATION_JSON).content(category))
				.andExpect(status().isForbidden());
		jdbc.sql("UPDATE app_user SET role = 'ADMIN' WHERE username = :u").param("u", username).update();
		mvc.perform(post("/api/categories").header("Authorization", "Bearer " + jwt)
						.contentType(MediaType.APPLICATION_JSON).content(category))
				.andExpect(status().isCreated());
		jdbc.sql("UPDATE app_user SET role = 'USER' WHERE username = :u").param("u", username).update();
		mvc.perform(post("/api/categories").header("Authorization", "Bearer " + jwt)
						.contentType(MediaType.APPLICATION_JSON).content(category.replace(code, code + "_2")))
				.andExpect(status().isForbidden());

		// gesperrter Account: Token sofort ungültig
		jdbc.sql("UPDATE app_user SET enabled = false WHERE username = :u").param("u", username).update();
		mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + jwt))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void jpegUndWebpUploadEntferntMetadatenUndLimitGreift() throws Exception {
		String owner = newName();
		String admin = newName();
		register(owner);
		register(admin);
		jdbc.sql("UPDATE app_user SET role = 'ADMIN' WHERE username = :u").param("u", admin).update();
		long spotId = createSpot(owner, "Fotolimit " + owner);

		// JPEG mit eingeschleustem EXIF-Segment (Platzhalter für GPS-Daten)
		BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream jpegOut = new ByteArrayOutputStream();
		ImageIO.write(image, "jpg", jpegOut);
		byte[] jpeg = jpegOut.toByteArray();
		byte[] secret = "Exif\0\0GPS-GEHEIM-50.93".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
		byte[] withExif = new byte[jpeg.length + 4 + secret.length];
		System.arraycopy(jpeg, 0, withExif, 0, 2);
		withExif[2] = (byte) 0xFF;
		withExif[3] = (byte) 0xE1;
		withExif[4] = (byte) ((secret.length + 2) >> 8);
		withExif[5] = (byte) (secret.length + 2);
		System.arraycopy(secret, 0, withExif, 6, secret.length);
		System.arraycopy(jpeg, 2, withExif, 6 + secret.length, jpeg.length - 2);

		String jpegUrl = JsonPath.read(mvc.perform(multipart("/api/spots/" + spotId + "/photos").with(httpBasic(owner, PASSWORD))
						.file(new MockMultipartFile("file", "handy.jpg", "image/jpeg", withExif)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.contentType").value("image/jpeg"))
				.andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.startsWith("http://localhost/api/spots/")))
				.andReturn().getResponse().getContentAsString(), "$.url");
		byte[] served = mvc.perform(get(jpegUrl)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
		org.assertj.core.api.Assertions.assertThat(new String(served, java.nio.charset.StandardCharsets.ISO_8859_1))
				.doesNotContain("GPS-GEHEIM");
		org.assertj.core.api.Assertions.assertThat(ImageIO.read(new java.io.ByteArrayInputStream(served))).isNotNull();

		// WebP mit EXIF-Chunk
		byte[] exifChunk = "EXIF\u0008\0\0\0GPS-WEBP".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
		byte[] vp8l = {'V', 'P', '8', 'L', 4, 0, 0, 0, 0x2F, 0, 0, 0};
		byte[] webp = new byte[12 + vp8l.length + exifChunk.length];
		System.arraycopy("RIFF".getBytes(), 0, webp, 0, 4);
		int riffSize = webp.length - 8;
		webp[4] = (byte) riffSize;
		webp[5] = (byte) (riffSize >> 8);
		System.arraycopy("WEBP".getBytes(), 0, webp, 8, 4);
		System.arraycopy(vp8l, 0, webp, 12, vp8l.length);
		System.arraycopy(exifChunk, 0, webp, 12 + vp8l.length, exifChunk.length);
		String webpUrl = JsonPath.read(mvc.perform(multipart("/api/spots/" + spotId + "/photos").with(httpBasic(owner, PASSWORD))
						.file(new MockMultipartFile("file", "bild.webp", "image/webp", webp)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.contentType").value("image/webp"))
				.andReturn().getResponse().getContentAsString(), "$.url");
		byte[] servedWebp = mvc.perform(get(webpUrl)).andReturn().getResponse().getContentAsByteArray();
		org.assertj.core.api.Assertions.assertThat(new String(servedWebp, java.nio.charset.StandardCharsets.ISO_8859_1))
				.doesNotContain("GPS-WEBP").startsWith("RIFF");

		// Admin darf fremde Fotos löschen
		mvc.perform(delete(webpUrl).with(httpBasic(admin, PASSWORD))).andExpect(status().isNoContent());

		// Limit: höchstens 20 Fotos je Spot
		BufferedImage tiny = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
		ImageIO.write(tiny, "png", pngOut);
		for (int i = 1; i < 20; i++) {
			mvc.perform(multipart("/api/spots/" + spotId + "/photos").with(httpBasic(owner, PASSWORD))
							.file(new MockMultipartFile("file", "p.png", "image/png", pngOut.toByteArray())))
					.andExpect(status().isCreated());
		}
		mvc.perform(multipart("/api/spots/" + spotId + "/photos").with(httpBasic(owner, PASSWORD))
						.file(new MockMultipartFile("file", "p.png", "image/png", pngOut.toByteArray())))
				.andExpect(status().isConflict());
		mvc.perform(get("/api/spots/" + spotId)).andExpect(jsonPath("$.photos.length()").value(20));
	}

	@Test
	void datenexportEnthaeltAlleEigenenDaten() throws Exception {
		String username = newName();
		String other = newName();
		register(username);
		register(other);
		long own = createSpot(username, "Export " + username);
		long foreign = createSpot(other, "Fremd " + other);
		mvc.perform(post("/api/spots/" + foreign + "/ratings").with(httpBasic(username, PASSWORD))
				.contentType(MediaType.APPLICATION_JSON).content("{\"score\":4,\"comment\":\"Mein Kommentar\"}"));
		mvc.perform(post("/api/spots/" + foreign + "/comments").with(httpBasic(username, PASSWORD))
				.contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Mein Text\"}"));
		mvc.perform(post("/api/spots/" + own + "/ratings").with(httpBasic(other, PASSWORD))
				.contentType(MediaType.APPLICATION_JSON).content("{\"score\":1,\"comment\":\"Nicht von mir\"}"));

		mvc.perform(get("/api/users/me/export")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/users/me/export").with(httpBasic(username, PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
				.andExpect(jsonPath("$.profil.username").value(username))
				.andExpect(jsonPath("$.profil.email").value(username + "@test.de"))
				.andExpect(jsonPath("$.profil.password_hash").doesNotExist())
				.andExpect(jsonPath("$.spots.length()").value(1))
				.andExpect(jsonPath("$.spots[0].name").value("Export " + username))
				.andExpect(jsonPath("$.bewertungen.length()").value(1))
				.andExpect(jsonPath("$.bewertungen[0].kommentar").value("Mein Kommentar"))
				.andExpect(jsonPath("$.kommentare[0].text").value("Mein Text"))
				.andExpect(jsonPath("$.fotos").isEmpty())
				.andExpect(jsonPath("$.meldungen").isEmpty());
	}

	@Test
	void benutzernamenSindEingeschraenkt() throws Exception {
		for (String bad : new String[] {"Wurst/Hans", "a%b", "semi;colon", "back\\\\slash", " -abc", "zwei\\nzeilen"}) {
			mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
							.content("{\"email\":\"%s@test.de\",\"acceptTerms\":true,\"password\":\"%s\",\"displayName\":\"%s\"}"
									.formatted(newName(), PASSWORD, bad)))
					.andExpect(status().isBadRequest());
		}
		for (String reserved : new String[] {"Admin", "Sausage-Team", "moderator"}) {
			mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
							.content("{\"email\":\"%s@test.de\",\"acceptTerms\":true,\"password\":\"%s\",\"displayName\":\"%s\"}"
									.formatted(newName(), PASSWORD, reserved)))
					.andExpect(status().isConflict());
		}
		// Erlaubt: Umlaute, Leerzeichen, Punkt, Unterstrich, Bindestrich; Vollbreite-Zeichen werden vereinheitlicht
		String suffix = newName().substring(1);
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"%s@test.de\",\"acceptTerms\":true,\"password\":\"%s\",\"displayName\":\"Jörg Würst_%s.x-y\"}"
								.formatted(newName(), PASSWORD, suffix)))
				.andExpect(status().isCreated());
		String json = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"%s@test.de\",\"acceptTerms\":true,\"password\":\"%s\",\"displayName\":\"ＭＡＸ%s\"}"
								.formatted(newName(), PASSWORD, suffix)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String token = JsonPath.read(json, "$.token");
		mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
				.andExpect(jsonPath("$.username").value("MAX" + suffix));
	}

	@Test
	void registrierungVerlangtZustimmungZuNutzungsbedingungen() throws Exception {
		for (String terms : new String[] {"", ",\"acceptTerms\":false"}) {
			String name = newName();
			mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
							.content("{\"email\":\"%s@test.de\",\"displayName\":\"%s\",\"password\":\"%s\"%s}"
									.formatted(name, name, PASSWORD, terms)))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errors.acceptTerms").value(org.hamcrest.Matchers.containsString("16 Jahre")));
			mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
							.content("{\"username\":\"%s\",\"email\":\"%s@test.de\",\"password\":\"%s\"%s}"
									.formatted(name, name, PASSWORD, terms)))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errors.acceptTerms").exists());
		}

		String username = newName();
		register(username);
		mvc.perform(get("/api/users/me").with(httpBasic(username, PASSWORD)))
				.andExpect(jsonPath("$.termsVersion").value("2026-10-05"))
				.andExpect(jsonPath("$.termsAcceptedAt").exists())
				.andExpect(jsonPath("$.currentTermsVersion").value("2026-10-05"));
		jdbc.sql("UPDATE app_user SET terms_version = 'alt' WHERE username = :u").param("u", username).update();
		mvc.perform(post("/api/users/me/terms").with(httpBasic(username, PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.termsVersion").value("2026-10-05"));
		mvc.perform(get("/api/users/me/export").with(httpBasic(username, PASSWORD)))
				.andExpect(jsonPath("$.profil.nutzungsbedingungen_version").value("2026-10-05"));
	}

	@Test
	void pixelbombeWirdAbgelehnt() throws Exception {
		String owner = newName();
		register(owner);
		long spotId = createSpot(owner, "Pixel " + owner);
		BufferedImage tiny = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(tiny, "png", out);
		byte[] png = out.toByteArray();
		// Kopfdaten auf 30.000 × 30.000 Pixel setzen (900 Megapixel)
		java.nio.ByteBuffer.wrap(png).putInt(16, 30_000).putInt(20, 30_000);
		mvc.perform(multipart("/api/spots/" + spotId + "/photos").with(httpBasic(owner, PASSWORD))
						.file(new MockMultipartFile("file", "bombe.png", "image/png", png)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("zu groß")));
	}

	private String token(String username) throws Exception {
		String json = mvc.perform(post("/api/auth/token").with(httpBasic(username, PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.expiresIn").value(30 * 24 * 3600))
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
						{"username":"%s","email":"%s@test.de","acceptTerms":true,"password":"geheimesPasswort"}
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
