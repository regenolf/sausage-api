package de.robertegenolf.sausageapi.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Lesende Endpunkte sind öffentlich, alles andere braucht ein Keycloak-Token ({@code Authorization: Bearer …}).
 * Aktive Accounts haben {@code ROLE_USER} bzw. {@code ROLE_ADMIN}; gesperrte dürfen nur Daten exportieren und das
 * Konto löschen.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

	private static final String[] ACTIVE = {"USER", "ADMIN"};

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, KeycloakJwtAuthenticationConverter jwtConverter,
			RequestThrottle throttle) throws Exception {
		http
				.csrf(csrf -> csrf.disable())
				.cors(Customizer.withDefaults())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/api/admin/**").hasRole("ADMIN")
						// DSGVO: auch gesperrte Accounts
						.requestMatchers(HttpMethod.GET, "/api/users/me/export").authenticated()
						.requestMatchers(HttpMethod.DELETE, "/api/users/me").authenticated()
						.requestMatchers(HttpMethod.POST, "/api/reports").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/categories").hasRole("ADMIN")
						.requestMatchers(HttpMethod.GET, "/api/users/me", "/api/users/me/**", "/api/spots/*/ratings/me")
								.hasAnyRole(ACTIVE)
						.requestMatchers(HttpMethod.GET, "/api/**").permitAll()
						.requestMatchers("/actuator/health/**", "/error").permitAll()
						.requestMatchers(HttpMethod.GET, "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
						.anyRequest().hasAnyRole(ACTIVE))
				.oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)))
				.addFilterBefore(new WriteThrottleFilter(throttle), AuthorizationFilter.class);
		return http.build();
	}

	/**
	 * Erlaubt Browser-Frontends von den in {@code app.cors.allowed-origins} konfigurierten Origins.
	 */
	@Bean
	CorsConfigurationSource corsConfigurationSource(
			@Value("${app.cors.allowed-origins:http://localhost:8100,http://localhost:4200,capacitor://localhost,https://localhost,http://localhost:3000,http://localhost:5173}") List<String> origins) {
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOrigins(origins);
		config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
		config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
		config.setExposedHeaders(List.of("Location", "X-Total-Count", "Retry-After"));
		config.setMaxAge(3600L);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", config);
		return source;
	}
}
