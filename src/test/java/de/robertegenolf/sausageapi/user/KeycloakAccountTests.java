package de.robertegenolf.sausageapi.user;

import de.robertegenolf.sausageapi.TestSecurityConfiguration;
import de.robertegenolf.sausageapi.TestTokens;
import de.robertegenolf.sausageapi.TestcontainersConfiguration;
import de.robertegenolf.sausageapi.security.KeycloakAdminClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static de.robertegenolf.sausageapi.TestTokens.login;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Kontolöschung und „überall abmelden“ gehen an die Admin-API von Keycloak. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestSecurityConfiguration.class})
class KeycloakAccountTests {

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private KeycloakAdminClient keycloak;

	@Test
	void kontoLoeschenLoeschtAuchInKeycloak() throws Exception {
		String name = newName();
		mvc.perform(get("/api/users/me").with(login(name))).andExpect(status().isOk());

		mvc.perform(delete("/api/users/me").with(login(name))).andExpect(status().isNoContent());

		verify(keycloak).deleteUser(TestTokens.subject(name));
	}

	@Test
	void schlaegtKeycloakFehlBleibtDasKontoErhalten() throws Exception {
		String name = newName();
		mvc.perform(get("/api/users/me").with(login(name))).andExpect(status().isOk());
		doThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Keycloak nicht erreichbar"))
				.when(keycloak).deleteUser(anyString());

		mvc.perform(delete("/api/users/me").with(login(name))).andExpect(status().isBadGateway());
		mvc.perform(get("/api/users/me").with(login(name))).andExpect(status().isOk());
	}

	@Test
	void ueberallAbmeldenBeendetKeycloakSitzungen() throws Exception {
		String name = newName();
		mvc.perform(post("/api/users/me/logout-all")).andExpect(status().isUnauthorized());
		verify(keycloak, never()).logoutUser(anyString());

		mvc.perform(post("/api/users/me/logout-all").with(login(name))).andExpect(status().isNoContent());

		verify(keycloak).logoutUser(TestTokens.subject(name));
	}

	private static String newName() {
		return "k" + UUID.randomUUID().toString().substring(0, 8);
	}
}
