package de.robertegenolf.sausageapi.spot;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class BoundingBoxTests {

	@Test
	void rechteckEnthaeltPunkteAmKreisrand() {
		// Köln, 10 km: Punkte genau 10 km nördlich/östlich müssen drin liegen
		SpotRepository.BoundingBox box = SpotRepository.BoundingBox.around(50.9375, 6.9603, 10);
		assertThat(box.maxLat()).isGreaterThan(BigDecimal.valueOf(50.9375 + 10 / 111.2));
		assertThat(box.minLat()).isLessThan(BigDecimal.valueOf(50.9375 - 10 / 111.2));
		double kmPerLonDegree = 111.2 * Math.cos(Math.toRadians(50.9375));
		assertThat(box.maxLon()).isGreaterThan(BigDecimal.valueOf(6.9603 + 10 / kmPerLonDegree));
		assertThat(box.minLon()).isLessThan(BigDecimal.valueOf(6.9603 - 10 / kmPerLonDegree));
	}

	@Test
	void anDerDatumsgrenzeUndAmPolKeinLaengenfilter() {
		SpotRepository.BoundingBox dateLine = SpotRepository.BoundingBox.around(0, 179.95, 20);
		assertThat(dateLine.minLon()).isEqualByComparingTo("-180");
		assertThat(dateLine.maxLon()).isEqualByComparingTo("180");

		SpotRepository.BoundingBox pole = SpotRepository.BoundingBox.around(89.95, 10, 20);
		assertThat(pole.maxLat()).isEqualByComparingTo("90");
		assertThat(pole.minLon()).isEqualByComparingTo("-180");
	}
}
