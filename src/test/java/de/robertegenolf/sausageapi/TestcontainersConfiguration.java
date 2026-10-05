package de.robertegenolf.sausageapi;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Startet für Tests eine eigene Postgres-Instanz in Docker; die Datenbank-URL wird
 * per {@link ServiceConnection} automatisch gesetzt. Der Container wird von allen
 * Testklassen gemeinsam genutzt (gleicher Spring-Kontext).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer("postgres:17");
	}
}
