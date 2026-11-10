package de.robertegenolf.sausageapi.security;

import de.robertegenolf.sausageapi.TestSecurityConfiguration;
import de.robertegenolf.sausageapi.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static de.robertegenolf.sausageapi.TestTokens.login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Eigene Limits der API. Fehlversuche beim Login und Spam-Registrierungen begrenzt Keycloak (Brute-Force-Schutz des
 * Realms), das ist hier nicht mehr Thema.
 */
@SpringBootTest(properties = {"app.rate-limit.writes-per-hour=3", "app.rate-limit.reports-per-hour=2"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestSecurityConfiguration.class})
class RateLimitTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void schreibzugriffeProUserBegrenzt() throws Exception {
		String user = "rl" + UUID.randomUUID().toString().substring(0, 8);
		String spot = "{\"categoryCode\":\"BRATWURST\",\"name\":\"Spam\",\"latitude\":1,\"longitude\":1}";
		// der erste Request legt den Account an und zählt bereits als Schreibzugriff
		for (int i = 0; i < 3; i++) {
			mvc.perform(post("/api/spots").with(login(user)).header("Origin", "capacitor://localhost")
							.contentType(MediaType.APPLICATION_JSON).content(spot))
					.andExpect(status().isCreated());
		}
		mvc.perform(post("/api/spots").with(login(user)).header("Origin", "capacitor://localhost")
						.contentType(MediaType.APPLICATION_JSON).content(spot))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"))
				.andExpect(header().string("Access-Control-Allow-Origin", "capacitor://localhost"))
				.andExpect(jsonPath("$.status").value(429));
		// Lesen geht weiter, andere Nutzer sind nicht betroffen
		mvc.perform(get("/api/users/me").with(login(user))).andExpect(status().isOk());
		String other = "rl" + UUID.randomUUID().toString().substring(0, 8);
		mvc.perform(post("/api/spots").with(login(other)).contentType(MediaType.APPLICATION_JSON).content(spot))
				.andExpect(status().isCreated());
	}

	@Test
	void meldungenProIpBegrenzt() throws Exception {
		RequestPostProcessor ip = ip("10.0.7.1");
		String report = "{\"targetType\":\"SPOT\",\"targetId\":999999999,\"reason\":\"SPAM\",\"message\":\"Test\",\"email\":\"melder@test.de\"}";
		for (int i = 0; i < 2; i++) {
			mvc.perform(post("/api/reports").with(ip).contentType(MediaType.APPLICATION_JSON).content(report))
					.andExpect(status().is(org.hamcrest.Matchers.not(429)));
		}
		mvc.perform(post("/api/reports").with(ip).contentType(MediaType.APPLICATION_JSON).content(report))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"));
		// andere IP ist nicht betroffen
		mvc.perform(post("/api/reports").with(ip("10.0.7.2")).contentType(MediaType.APPLICATION_JSON).content(report))
				.andExpect(status().is(org.hamcrest.Matchers.not(429)));
	}

	private static RequestPostProcessor ip(String address) {
		return request -> {
			request.setRemoteAddr(address);
			return request;
		};
	}
}
