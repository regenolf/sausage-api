package de.robertegenolf.sausageapi.security;

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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"app.rate-limit.registrations-per-hour=2",
		"app.rate-limit.failed-logins-per-15-minutes=3"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RateLimitTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void registrierungenProIpBegrenzt() throws Exception {
		RequestPostProcessor ip = ip("10.0.0.1");
		mvc.perform(register(ip)).andExpect(status().isCreated());
		mvc.perform(register(ip)).andExpect(status().isCreated());
		mvc.perform(register(ip))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"))
				.andExpect(jsonPath("$.status").value(429));

		// andere IP ist nicht betroffen
		mvc.perform(register(ip("10.0.0.2"))).andExpect(status().isCreated());
	}

	@Test
	void nachZuVielenFehlversuchenGesperrt() throws Exception {
		String username = "rl" + UUID.randomUUID().toString().substring(0, 8);
		mvc.perform(post("/api/users").with(ip("10.0.1.1")).contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"%s\",\"email\":\"%s@test.de\",\"password\":\"geheimesPasswort\"}"
								.formatted(username, username)))
				.andExpect(status().isCreated());

		RequestPostProcessor attacker = ip("10.0.1.2");
		for (int i = 0; i < 3; i++) {
			mvc.perform(get("/api/users/me").with(attacker).with(httpBasic(username, "rate" + i)))
					.andExpect(status().isUnauthorized());
		}
		// selbst das richtige Passwort wird jetzt von dieser IP abgewiesen
		mvc.perform(get("/api/users/me").with(attacker).with(httpBasic(username, "geheimesPasswort")))
				.andExpect(status().isTooManyRequests());
		// öffentliche Endpunkte ohne Anmeldung gehen weiter
		mvc.perform(get("/api/categories").with(attacker)).andExpect(status().isOk());
		// auch die JSON-Anmeldung ist gesperrt …
		mvc.perform(post("/api/auth/login").with(attacker).contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"%s\",\"password\":\"geheimesPasswort\"}".formatted(username)))
				.andExpect(status().isTooManyRequests());
		// der echte User von seiner IP auch
		mvc.perform(get("/api/users/me").with(ip("10.0.1.3")).with(httpBasic(username, "geheimesPasswort")))
				.andExpect(status().isOk());
	}

	@Test
	void fehlgeschlageneJsonLoginsZaehlen() throws Exception {
		RequestPostProcessor attacker = ip("10.0.2.1");
		for (int i = 0; i < 3; i++) {
			mvc.perform(post("/api/auth/login").with(attacker).contentType(MediaType.APPLICATION_JSON)
							.content("{\"email\":\"niemand@test.de\",\"password\":\"x\"}"))
					.andExpect(status().isUnauthorized());
		}
		mvc.perform(post("/api/auth/login").with(attacker).contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"niemand@test.de\",\"password\":\"x\"}"))
				.andExpect(status().isTooManyRequests());
	}

	@Test
	void geteilteIpSperrtAngemeldeteNutzerNicht() throws Exception {
		RequestPostProcessor ip = ip("10.0.3.1");
		String user = "rl" + UUID.randomUUID().toString().substring(0, 8);
		String json = mvc.perform(post("/api/auth/register").with(ip("10.0.3.9")).contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"%s@test.de\",\"password\":\"geheimesPasswort\",\"displayName\":\"%s\"}"
								.formatted(user, user)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String token = com.jayway.jsonpath.JsonPath.read(json, "$.token");

		// jemand anderes hinter derselben IP rät Passwörter für einen anderen Account
		for (int i = 0; i < 5; i++) {
			mvc.perform(get("/api/users/me").with(ip).with(httpBasic("opfer" + user, "falsch" + i)));
		}
		// der angemeldete Nutzer (Bearer-Token) arbeitet ungestört weiter
		mvc.perform(get("/api/users/me").with(ip).header("Authorization", "Bearer " + token))
				.andExpect(status().isOk());
		// und kann sich mit seinem eigenen Passwort anmelden
		mvc.perform(post("/api/auth/login").with(ip).contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"%s@test.de\",\"password\":\"geheimesPasswort\"}".formatted(user)))
				.andExpect(status().isOk());
	}

	@Test
	void kodierterPfadUmgehtDieSperreNicht() throws Exception {
		RequestPostProcessor attacker = ip("10.0.4.1");
		String body = "{\"email\":\"ziel@test.de\",\"password\":\"x\"}";
		for (int i = 0; i < 6; i++) {
			mvc.perform(post(java.net.URI.create("/api/auth/%6Cogin")).with(attacker)
					.contentType(MediaType.APPLICATION_JSON).content(body));
		}
		mvc.perform(post("/api/auth/login").with(attacker).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"));
	}

	@Test
	void antwort429HatCorsHeader() throws Exception {
		RequestPostProcessor attacker = ip("10.0.5.1");
		String body = "{\"email\":\"cors@test.de\",\"password\":\"x\"}";
		for (int i = 0; i < 4; i++) {
			mvc.perform(post("/api/auth/login").with(attacker).header("Origin", "capacitor://localhost")
					.contentType(MediaType.APPLICATION_JSON).content(body));
		}
		mvc.perform(post("/api/auth/login").with(attacker).header("Origin", "capacitor://localhost")
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string("Access-Control-Allow-Origin", "capacitor://localhost"));
	}

	private static org.springframework.test.web.servlet.RequestBuilder register(RequestPostProcessor ip) {
		String name = "rl" + UUID.randomUUID().toString().substring(0, 8);
		return post("/api/users").with(ip).contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"%s\",\"email\":\"%s@test.de\",\"password\":\"geheimesPasswort\"}"
						.formatted(name, name));
	}

	private static RequestPostProcessor ip(String address) {
		return request -> {
			request.setRemoteAddr(address);
			return request;
		};
	}
}
