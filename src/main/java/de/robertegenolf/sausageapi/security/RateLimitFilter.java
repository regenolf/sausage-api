package de.robertegenolf.sausageapi.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;

/**
 * Bremst Brute-Force und Spam pro Client-IP: begrenzt Registrierungen und sperrt Anmeldeversuche
 * nach zu vielen fehlgeschlagenen Logins. Läuft vor Spring Security, damit gesperrte Clients gar
 * nicht erst Passwörter prüfen lassen können.
 * <p>
 * Hinter einem Reverse-Proxy {@code server.forward-headers-strategy=native} setzen, sonst haben
 * alle Anfragen die IP des Proxys.
 */
@Component
@Order(-110) // vor dem Spring-Security-Filter (-100)
class RateLimitFilter extends OncePerRequestFilter {

	private final boolean enabled;
	private final FixedWindowRateLimiter registrations;
	private final FixedWindowRateLimiter failedLogins;

	RateLimitFilter(@Value("${app.rate-limit.enabled:true}") boolean enabled,
			@Value("${app.rate-limit.registrations-per-hour:10}") int registrationsPerHour,
			@Value("${app.rate-limit.failed-logins-per-15-minutes:10}") int failedLoginsPer15Minutes) {
		this.enabled = enabled;
		this.registrations = new FixedWindowRateLimiter(registrationsPerHour, Duration.ofHours(1), Clock.systemUTC());
		this.failedLogins = new FixedWindowRateLimiter(failedLoginsPer15Minutes, Duration.ofMinutes(15),
				Clock.systemUTC());
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !enabled;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String ip = request.getRemoteAddr();
		boolean login = request.getHeader(HttpHeaders.AUTHORIZATION) != null;

		if (login && failedLogins.isBlocked(ip)) {
			reject(response, failedLogins.secondsUntilReset(ip), "Zu viele fehlgeschlagene Anmeldeversuche");
			return;
		}
		if ("POST".equals(request.getMethod()) && "/api/users".equals(request.getRequestURI())
				&& !registrations.tryAcquire(ip)) {
			reject(response, registrations.secondsUntilReset(ip), "Zu viele Registrierungen");
			return;
		}

		chain.doFilter(request, response);

		if (login && response.getStatus() == HttpStatus.UNAUTHORIZED.value()) {
			failedLogins.record(ip);
		}
	}

	private static void reject(HttpServletResponse response, long retryAfterSeconds, String detail)
			throws IOException {
		response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
		response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write("""
				{"type":"about:blank","title":"Too Many Requests","status":429,"detail":"%s. Bitte später erneut versuchen."}"""
				.formatted(detail));
	}
}
