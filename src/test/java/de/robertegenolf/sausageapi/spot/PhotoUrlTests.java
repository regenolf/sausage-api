package de.robertegenolf.sausageapi.spot;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PhotoUrlTests {

	@Test
	void konfigurierteOeffentlicheUrlHatVorrang() {
		assertThat(new PhotoRepository(null, "https://wurst.example.de/").url(3, 7))
				.isEqualTo("https://wurst.example.de/api/spots/3/photos/7");
	}

	@Test
	void ohneKonfigurationUndOhneAnfrageRelativ() {
		assertThat(new PhotoRepository(null, "").url(3, 7)).isEqualTo("/api/spots/3/photos/7");
	}
}
