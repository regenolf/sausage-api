package de.robertegenolf.sausageapi.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Begrenzt Schreibzugriffe (POST/PUT/DELETE unter /api) pro angemeldetem User. Sitzt in der Security-Kette nach
 * der Authentifizierung. Anmeldung, Registrierung und Meldungen haben eigene Limits und zählen hier nicht.
 */
class WriteThrottleFilter extends OncePerRequestFilter {

	private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

	private static final Set<String> EXCLUDED = Set.of("/api/auth/login", "/api/auth/register", "/api/auth/token",
			"/api/users", "/api/reports");

	private final LoginThrottle throttle;

	WriteThrottleFilter(LoginThrottle throttle) {
		this.throttle = throttle;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		String path = request.getServletPath() + (request.getPathInfo() == null ? "" : request.getPathInfo());
		if (WRITE_METHODS.contains(request.getMethod()) && path.startsWith("/api/") && !EXCLUDED.contains(path)
				&& auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
			try {
				throttle.checkWrite(auth.getName());
			}
			catch (LoginThrottle.TooManyRequestsException ex) {
				response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
				ex.getHeaders().forEach((name, values) -> values.forEach(v -> response.addHeader(name, v)));
				response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
				response.setCharacterEncoding("UTF-8");
				response.getWriter().write("""
						{"type":"about:blank","title":"Too Many Requests","status":429,"detail":"%s"}""".formatted(ex.getReason()));
				return;
			}
		}
		chain.doFilter(request, response);
	}
}
