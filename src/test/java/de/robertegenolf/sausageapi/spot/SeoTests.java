package de.robertegenolf.sausageapi.spot;

import de.robertegenolf.sausageapi.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTML für Link-Vorschauen/Suchmaschinen und Sitemap. */
@SpringBootTest(properties = {"app.rate-limit.registrations-per-hour=10000", "app.rate-limit.writes-per-hour=10000",
		"app.public-url=https://wurst.example.de"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SeoTests {

	private static final String PASSWORD = "geheimesPasswort";

	@Autowired
	private MockMvc mvc;

	@Test
	void vorschauSeiteEnthaeltMetaDatenUndIstMaskiert() throws Exception {
		String user = "s" + UUID.randomUUID().toString().substring(0, 8);
		mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(
						"{\"username\":\"%s\",\"email\":\"%s@test.de\",\"acceptTerms\":true,\"password\":\"%s\"}"
								.formatted(user, user, PASSWORD)))
				.andExpect(status().isCreated());
		String location = mvc.perform(post("/api/spots").with(httpBasic(user, PASSWORD)).contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"categoryCode":"BRATWURST","name":"Wurst <script>alert(1)</script>","city":"Köln",
								 "street":"Domplatz","houseNumber":"1","zipCode":"50667","latitude":50.94,"longitude":6.96,
								 "description":"Scharf & lecker </script><b>fett</b>","openingHours":"Mo–Fr 11–20 Uhr"}
								"""))
				.andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location");
		long id = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
		mvc.perform(post("/api/spots/" + id + "/ratings").with(httpBasic(user, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content("{\"score\":4}"))
				.andExpect(status().isOk());

		String html = mvc.perform(get("/api/seo/spots/" + id))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

		assertThat(html).contains("<title>Wurst &lt;script&gt;alert(1)&lt;/script&gt; – Sausage</title>")
				.contains("<link rel=\"canonical\" href=\"https://wurst.example.de/spots/" + id + "\">")
				.contains("<meta property=\"og:description\" content=\"Bratwurstbude in Köln – 4,0 von 5 Sternen (1 Bewertung).")
				.contains("<meta property=\"og:image\" content=\"https://wurst.example.de/assets/icon/icon-512.png\">")
				.contains("Adresse: Domplatz 1, 50667 Köln")
				.contains("\"@type\":\"FoodEstablishment\"")
				.contains("\"ratingCount\":1")
				// Nutzertexte können weder HTML noch das JSON-LD-Script aufbrechen
				.doesNotContain("<script>alert")
				.doesNotContain("</script><b>")
				.contains("\\u003c/script>\\u003cb>");
		// genau ein schließendes Script-Tag (das eigene des JSON-LD)
		assertThat(html.split("</script>", -1)).hasSize(2);

		String sitemap = mvc.perform(get("/api/seo/sitemap.xml")).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat(sitemap).contains("<loc>https://wurst.example.de/home</loc>")
				.contains("<loc>https://wurst.example.de/spots/" + id + "</loc><lastmod>");

		String missing = mvc.perform(get("/api/seo/spots/999999999")).andExpect(status().isNotFound())
				.andReturn().getResponse().getContentAsString();
		assertThat(missing).contains("<meta name=\"robots\" content=\"noindex\">");
	}
}
