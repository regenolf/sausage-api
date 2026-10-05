package de.robertegenolf.sausageapi.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Wendet {@link LoginThrottle} auf HTTP-Basic-Anmeldungen an. Sitzt in der Security-Kette direkt nach dem
 * CORS-Filter (damit auch 429-Antworten CORS-Header haben) und vor der Passwortprüfung.
 */
class BasicAuthThrottleFilter extends OncePerRequestFilter {

	private final LoginThrottle throttle;

	BasicAuthThrottleFilter(LoginThrottle throttle) {
		this.throttle = throttle;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String username = basicUsername(request.getHeader(HttpHeaders.AUTHORIZATION));
		if (username == null) {
			chain.doFilter(request, response);
			return;
		}
		String ip = request.getRemoteAddr();
		try {
			throttle.checkLogin(ip, username);
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
		chain.doFilter(request, response);
		if (response.getStatus() == HttpStatus.UNAUTHORIZED.value()) {
			throttle.loginFailed(ip, username);
		}
	}

	/** Benutzername aus "Authorization: Basic base64(name:passwort)", sonst null. */
	static String basicUsername(String header) {
		if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
			return null;
		}
		try {
			String decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
			int colon = decoded.indexOf(':');
			return colon < 0 ? decoded : decoded.substring(0, colon);
		}
		catch (IllegalArgumentException ex) {
			return "";
		}
	}
}
