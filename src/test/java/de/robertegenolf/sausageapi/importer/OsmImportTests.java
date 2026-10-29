package de.robertegenolf.sausageapi.importer;

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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** OSM-Import über /api/admin/imports/osm und die Prüfliste der Moderation. */
@SpringBootTest(properties = {"app.rate-limit.registrations-per-hour=10000",
		"app.rate-limit.failed-logins-per-15-minutes=10000",
		"app.rate-limit.failed-logins-per-ip-per-15-minutes=10000", "app.rate-limit.writes-per-hour=10000"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OsmImportTests {

	private static final String PASSWORD = "geheimesPasswort";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void nurAdminsDuerfenImportieren() throws Exception {
		String user = register();
		String body = collection(node(randomId(), "Wurststand", null));

		mvc.perform(post("/api/admin/imports/osm").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isUnauthorized());
		mvc.perform(post("/api/admin/imports/osm").with(httpBasic(user, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isForbidden());
		mvc.perform(get("/api/admin/spots/unverified").with(httpBasic(user, PASSWORD)))
				.andExpect(status().isForbidden());
	}

	@Test
	void importiertNurBudenUndZaehltDenRest() throws Exception {
		String admin = admin();
		String category = category(admin);
		long node = randomId();
		long way = randomId();
		String body = collection(
				node(node, "Rostbratwurst am Anger", "\"addr:city\":\"Erfurt\",\"opening_hours\":\"Mo-Sa 10:00-18:00\""),
				area(way * 2, "Imbiss Krause", "\"cuisine\":\"sausage;burger\""),
				node(randomId(), "Döner Palast", "\"cuisine\":\"kebab\""),
				"{\"type\":\"Feature\",\"id\":\"n%d\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[11.03,50.97]},\"properties\":{\"amenity\":\"fast_food\",\"cuisine\":\"sausage\"}}"
						.formatted(randomId()),
				"{\"type\":\"Feature\",\"id\":\"quatsch\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[11.03,50.97]},\"properties\":{\"amenity\":\"fast_food\",\"name\":\"Wurst\"}}",
				node(node, "Rostbratwurst am Anger", null));

		// Probelauf zählt nur
		mvc.perform(post("/api/admin/imports/osm").param("category", category).param("dryRun", "true")
						.with(httpBasic(admin, PASSWORD)).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.dryRun").value(true))
				.andExpect(jsonPath("$.received").value(6))
				.andExpect(jsonPath("$.matched").value(2))
				.andExpect(jsonPath("$.created").value(2))
				.andExpect(jsonPath("$.skippedNoMatch").value(1))
				.andExpect(jsonPath("$.skippedNoName").value(1))
				.andExpect(jsonPath("$.skippedInvalid").value(1))
				.andExpect(jsonPath("$.skippedDuplicate").value(1))
				.andExpect(jsonPath("$.newSpots[0]").value("Rostbratwurst am Anger (Erfurt)"));
		assertThat(countImported(category)).isZero();

		mvc.perform(post("/api/admin/imports/osm").param("category", category)
						.with(httpBasic(admin, PASSWORD)).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.dryRun").value(false))
				.andExpect(jsonPath("$.created").value(2));
		assertThat(countImported(category)).isEqualTo(2);

		long spotId = spotId("node/" + node);
		mvc.perform(get("/api/spots/" + spotId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Rostbratwurst am Anger"))
				.andExpect(jsonPath("$.city").value("Erfurt"))
				.andExpect(jsonPath("$.openingHours").value("Mo-Sa 10:00-18:00"))
				.andExpect(jsonPath("$.source").value("OSM"))
				.andExpect(jsonPath("$.verified").value(false))
				.andExpect(jsonPath("$.sourceUrl").value("https://www.openstreetmap.org/node/" + node))
				.andExpect(jsonPath("$.createdBy").doesNotExist());
		mvc.perform(get("/api/spots/" + spotId("way/" + way)))
				.andExpect(jsonPath("$.latitude").value(50.975))
				.andExpect(jsonPath("$.longitude").value(11.035));
		mvc.perform(get("/api/spots").param("category", category))
				.andExpect(jsonPath("$[0].source").value("OSM"))
				.andExpect(jsonPath("$[0].verified").value(false));

		// zweiter Lauf mit gleichen Daten ändert nichts
		mvc.perform(post("/api/admin/imports/osm").param("category", category)
						.with(httpBasic(admin, PASSWORD)).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(jsonPath("$.created").value(0))
				.andExpect(jsonPath("$.unchanged").value(2));
		assertThat(countImported(category)).isEqualTo(2);
	}

	@Test
	void uebernimmtAenderungenNurBisZurPruefung() throws Exception {
		String admin = admin();
		String category = category(admin);
		long node = randomId();
		import_(admin, category, collection(node(node, "Wurstbude", null)));
		long spotId = spotId("node/" + node);

		// ungeprüft: Umbenennung in OSM wird übernommen
		mvc.perform(post("/api/admin/imports/osm").param("category", category).with(httpBasic(admin, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content(collection(node(node, "Wurstbude Krause", null))))
				.andExpect(jsonPath("$.updated").value(1));
		mvc.perform(get("/api/spots/" + spotId)).andExpect(jsonPath("$.name").value("Wurstbude Krause"));

		// Prüfliste enthält den Spot mit Link zu OSM
		mvc.perform(get("/api/admin/spots/unverified").param("source", "OSM").param("limit", "1000")
						.with(httpBasic(admin, PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(header().exists("X-Total-Count"))
				.andExpect(jsonPath("$[?(@.id == " + spotId + ")].sourceUrl")
						.value("https://www.openstreetmap.org/node/" + node));

		mvc.perform(post("/api/admin/spots/" + spotId + "/verify").with(httpBasic(admin, PASSWORD)))
				.andExpect(status().isNoContent());
		mvc.perform(post("/api/admin/spots/999999999/verify").with(httpBasic(admin, PASSWORD)))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/spots/" + spotId)).andExpect(jsonPath("$.verified").value(true));
		mvc.perform(get("/api/admin/spots/unverified").param("source", "OSM").param("limit", "1000")
						.with(httpBasic(admin, PASSWORD)))
				.andExpect(jsonPath("$[?(@.id == " + spotId + ")]").isEmpty());

		// geprüft: OSM-Änderungen werden nicht mehr übernommen
		mvc.perform(post("/api/admin/imports/osm").param("category", category).with(httpBasic(admin, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content(collection(node(node, "Wurstbude ganz anders", null))))
				.andExpect(jsonPath("$.updated").value(0))
				.andExpect(jsonPath("$.keptVerified").value(1));
		mvc.perform(get("/api/spots/" + spotId)).andExpect(jsonPath("$.name").value("Wurstbude Krause"));
	}

	@Test
	void bearbeitenDurchAdminGiltAlsPruefung() throws Exception {
		String admin = admin();
		String category = category(admin);
		long node = randomId();
		import_(admin, category, collection(node(node, "Wurststand", null)));
		long spotId = spotId("node/" + node);

		mvc.perform(put("/api/spots/" + spotId).with(httpBasic(admin, PASSWORD)).contentType(MediaType.APPLICATION_JSON)
						.content("{\"categoryCode\":\"%s\",\"name\":\"Wurststand (korrigiert)\",\"latitude\":50.97,\"longitude\":11.03}"
								.formatted(category)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.verified").value(true))
				.andExpect(jsonPath("$.source").value("OSM"));
	}

	@Test
	void geloeschteBudenKommenNichtWieder() throws Exception {
		String admin = admin();
		String user = register();
		String category = category(admin);
		long keep = randomId();
		long gone = randomId();
		String body = collection(node(keep, "Wurststand Nord", null), node(gone, "Wurststand Süd", null));
		import_(admin, category, body);

		// importierte Spots haben keinen Ersteller: nur Admins dürfen sie ändern oder löschen
		mvc.perform(delete("/api/spots/" + spotId("node/" + gone)).with(httpBasic(user, PASSWORD)))
				.andExpect(status().isForbidden());
		mvc.perform(delete("/api/spots/" + spotId("node/" + gone)).with(httpBasic(admin, PASSWORD)))
				.andExpect(status().isNoContent());

		mvc.perform(post("/api/admin/imports/osm").param("category", category).with(httpBasic(admin, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(jsonPath("$.created").value(0))
				.andExpect(jsonPath("$.unchanged").value(1))
				.andExpect(jsonPath("$.skippedRejected").value(1));
		assertThat(countImported(category)).isEqualTo(1);

		// in OSM verschwunden: wird gezählt, aber nicht gelöscht
		mvc.perform(post("/api/admin/imports/osm").param("category", category).with(httpBasic(admin, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content(collection()))
				.andExpect(jsonPath("$.stale").value(1));
		assertThat(countImported(category)).isEqualTo(1);
	}

	@Test
	void pruefFehlerhafteAnfragen() throws Exception {
		String admin = admin();
		mvc.perform(post("/api/admin/imports/osm").param("category", "GIBTS_NICHT").with(httpBasic(admin, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content(collection()))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/api/admin/imports/osm").with(httpBasic(admin, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"FeatureCollection\"}"))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/admin/spots/unverified").param("limit", "0").with(httpBasic(admin, PASSWORD)))
				.andExpect(status().isBadRequest());
	}

	private void import_(String admin, String category, String body) throws Exception {
		mvc.perform(post("/api/admin/imports/osm").param("category", category).with(httpBasic(admin, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk());
	}

	private static long randomId() {
		return ThreadLocalRandom.current().nextLong(1, 1L << 50);
	}

	private static String node(long id, String name, String extraTags) {
		return "{\"type\":\"Feature\",\"id\":\"n%d\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[11.03,50.97]},\"properties\":{\"amenity\":\"fast_food\",\"name\":\"%s\"%s}}"
				.formatted(id, name, extraTags == null ? "" : "," + extraTags);
	}

	private static String area(long areaId, String name, String extraTags) {
		return "{\"type\":\"Feature\",\"id\":\"a%d\",\"geometry\":{\"type\":\"MultiPolygon\",\"coordinates\":[[[[11.03,50.97],[11.04,50.97],[11.04,50.98],[11.03,50.98],[11.03,50.97]]]]},\"properties\":{\"amenity\":\"fast_food\",\"name\":\"%s\"%s}}"
				.formatted(areaId, name, extraTags == null ? "" : "," + extraTags);
	}

	private static String collection(String... features) {
		return "{\"type\":\"FeatureCollection\",\"features\":[" + String.join(",", List.of(features)) + "]}";
	}

	private long spotId(String externalId) {
		return jdbc.sql("SELECT id FROM spot WHERE source = 'OSM' AND external_id = :id")
				.param("id", externalId)
				.query(Long.class)
				.single();
	}

	private long countImported(String category) {
		return jdbc.sql("SELECT COUNT(*) FROM spot s JOIN category c ON c.id = s.category_id WHERE c.code = :code AND s.source = 'OSM'")
				.param("code", category)
				.query(Long.class)
				.single();
	}

	/** Eigene Kategorie je Test, damit sich die Zählungen (z. B. stale) nicht gegenseitig beeinflussen. */
	private String category(String admin) throws Exception {
		String code = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
		String response = mvc.perform(post("/api/categories").with(httpBasic(admin, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"code\":\"%s\",\"name\":\"Test %s\"}".formatted(code, code)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(response, "$.code");
	}

	private String admin() throws Exception {
		String admin = register();
		jdbc.sql("UPDATE app_user SET role = 'ADMIN' WHERE username = :u").param("u", admin).update();
		return admin;
	}

	private String register() throws Exception {
		String name = "i" + UUID.randomUUID().toString().substring(0, 8);
		mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"%s\",\"email\":\"%s@test.de\",\"acceptTerms\":true,\"password\":\"%s\"}".formatted(name, name, PASSWORD)))
				.andExpect(status().isCreated());
		return name;
	}
}
