package de.robertegenolf.sausageapi.spot;

import de.robertegenolf.sausageapi.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Tageskontingent für Fotos (hier winzig, damit schon das zweite Bild es überschreitet). */
@SpringBootTest(properties = {"app.rate-limit.registrations-per-hour=10000", "app.rate-limit.writes-per-hour=10000",
		"app.photos.daily-quota-bytes=" + PhotoQuotaTests.QUOTA})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PhotoQuotaTests {

	static final int QUOTA = 400;
	private static final String PASSWORD = "geheimesPasswort";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void tageskontingentBegrenztUploadsAusserFuerAdmins() throws Exception {
		BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
		for (int i = 0; i < 64; i++) {
			image.setRGB(i % 8, i / 8, i * 0x030507);
		}
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		ImageIO.write(image, "png", buffer);
		byte[] png = buffer.toByteArray();
		// Zwei Bilder sollen nicht in das Kontingent passen, eines schon
		assertThat(png.length).isBetween(QUOTA / 2 + 1, QUOTA);

		String user = register();
		long spotId = createSpot(user);
		upload(spotId, user, png).andExpect(status().isCreated());
		upload(spotId, user, png).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("Tageslimit für Fotos erreicht")));

		String admin = register();
		jdbc.sql("UPDATE app_user SET role = 'ADMIN' WHERE username = :u").param("u", admin).update();
		upload(spotId, admin, png).andExpect(status().isCreated());
		upload(spotId, admin, png).andExpect(status().isCreated());
	}

	private org.springframework.test.web.servlet.ResultActions upload(long spotId, String user, byte[] png) throws Exception {
		return mvc.perform(multipart("/api/spots/" + spotId + "/photos").with(httpBasic(user, PASSWORD))
				.file(new MockMultipartFile("file", "bild.png", "image/png", png)));
	}

	private String register() throws Exception {
		String name = "q" + UUID.randomUUID().toString().substring(0, 8);
		mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"%s\",\"email\":\"%s@test.de\",\"acceptTerms\":true,\"password\":\"%s\"}"
								.formatted(name, name, PASSWORD)))
				.andExpect(status().isCreated());
		return name;
	}

	private long createSpot(String user) throws Exception {
		String location = mvc.perform(post("/api/spots").with(httpBasic(user, PASSWORD)).contentType(MediaType.APPLICATION_JSON)
						.content("{\"categoryCode\":\"BRATWURST\",\"name\":\"Quote " + user + "\",\"latitude\":50.9,\"longitude\":6.9}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location");
		return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
	}
}
