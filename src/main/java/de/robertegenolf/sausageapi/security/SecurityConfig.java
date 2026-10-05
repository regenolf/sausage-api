package de.robertegenolf.sausageapi.security;

import de.robertegenolf.sausageapi.user.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(HttpMethod.POST, "/api/users").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/users/me", "/api/spots/*/ratings/me").authenticated()
						.requestMatchers(HttpMethod.GET, "/api/**").permitAll()
						.requestMatchers("/actuator/health/**", "/error").permitAll()
						.anyRequest().authenticated())
				.httpBasic(basic -> {
				});
		return http.build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	UserDetailsService userDetailsService(UserRepository users) {
		return username -> users.findByUsername(username)
				.map(u -> User.withUsername(u.username())
						.password(u.passwordHash())
						.roles("USER")
						.build())
				.orElseThrow(() -> new UsernameNotFoundException("Unbekannter Benutzer"));
	}
}
