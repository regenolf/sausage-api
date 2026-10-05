package de.robertegenolf.sausageapi;

import org.springframework.boot.SpringApplication;

/**
 * Startet die App lokal mit einer Wegwerf-Postgres aus Docker: {@code ./mvnw spring-boot:test-run}
 */
public class TestSausageApiApplication {

	public static void main(String[] args) {
		SpringApplication.from(SausageApiApplication::main)
				.with(TestcontainersConfiguration.class)
				.run(args);
	}
}
