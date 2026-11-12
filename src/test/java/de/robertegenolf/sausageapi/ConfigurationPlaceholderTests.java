package de.robertegenolf.sausageapi;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Eigene Umgebungsvariablen in application.properties dürfen nicht mit {@code SPRING_} beginnen: Spring Boot bindet
 * jede solche Variable zusätzlich per Relaxed Binding an die gleichnamige Eigenschaft. {@code SPRING_MAIL_SSL=true}
 * landete so in {@code spring.mail.ssl} (ein Objekt) und verhinderte in Produktion jeden Start – die Tests merken
 * davon nichts, weil dort die Variable fehlt.
 */
class ConfigurationPlaceholderTests {

	private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{(SPRING_[A-Z0-9_]+)");

	@Test
	void eigenePlatzhalterBeginnenNichtMitSpring() throws IOException {
		String properties;
		try (InputStream in = getClass().getResourceAsStream("/application.properties")) {
			properties = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		List<String> found = new ArrayList<>();
		Matcher matcher = PLACEHOLDER.matcher(properties);
		while (matcher.find()) {
			found.add(matcher.group(1));
		}
		assertTrue(found.isEmpty(), "Platzhalter mit SPRING_-Präfix kollidieren mit Spring Boots Relaxed Binding: "
				+ found + " – ohne SPRING_ benennen (z. B. MAIL_SMTP_SSL)");
	}
}
