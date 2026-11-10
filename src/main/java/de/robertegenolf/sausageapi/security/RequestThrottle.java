package de.robertegenolf.sausageapi.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;

/**
 * Bremst Spam: Meldungen pro IP und Schreibzugriffe pro angemeldetem User. Passwort-Raten und Spam-Registrierungen
 * bremst Keycloak (Brute-Force-Schutz des Realms). Zähler liegen im Speicher, also pro Instanz.
 */
@Component
public class RequestThrottle {

	/** 429 mit Retry-After-Header. */
	public static final class TooManyRequestsException extends ResponseStatusException {

		private final long retryAfterSeconds;

		TooManyRequestsException(String reason, long retryAfterSeconds) {
			super(HttpStatus.TOO_MANY_REQUESTS, reason);
			this.retryAfterSeconds = retryAfterSeconds;
		}

		@Override
		public HttpHeaders getHeaders() {
			HttpHeaders headers = new HttpHeaders();
			headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
			return headers;
		}
	}

	private final boolean enabled;
	private final FixedWindowRateLimiter reports;
	private final FixedWindowRateLimiter writes;

	RequestThrottle(@Value("${app.rate-limit.enabled:true}") boolean enabled,
			@Value("${app.rate-limit.reports-per-hour:30}") int reportsPerHour,
			@Value("${app.rate-limit.writes-per-hour:200}") int writesPerHour) {
		this.enabled = enabled;
		Clock clock = Clock.systemUTC();
		this.reports = new FixedWindowRateLimiter(reportsPerHour, Duration.ofHours(1), clock);
		this.writes = new FixedWindowRateLimiter(writesPerHour, Duration.ofHours(1), clock);
	}

	/** Zählt eine Meldung und wirft 429, wenn diese IP zu viele abgegeben hat. */
	public void checkReport(String ip) {
		ip = clientKey(ip);
		if (enabled && !reports.tryAcquire(ip)) {
			throw new TooManyRequestsException("Zu viele Meldungen. Bitte später erneut versuchen.",
					reports.secondsUntilReset(ip));
		}
	}

	/** Zählt einen Schreibzugriff (Anlegen/Ändern/Löschen) eines angemeldeten Users; gegen Spam und DB-Aufblähung. */
	public void checkWrite(String username) {
		if (enabled && !writes.tryAcquire(username)) {
			throw new TooManyRequestsException("Zu viele Änderungen in kurzer Zeit. Bitte später erneut versuchen.",
					writes.secondsUntilReset(username));
		}
	}

	/**
	 * IPv6-Adressen werden auf ihr /64-Präfix reduziert: Ein Anschluss bekommt meist ein ganzes /64 und könnte sonst
	 * durch Adresswechsel jede Begrenzung umgehen. IPv4 bleibt unverändert.
	 */
	static String clientKey(String ip) {
		if (ip == null || ip.indexOf(':') < 0) {
			return ip;
		}
		try {
			byte[] bytes = InetAddress.ofLiteral(ip).getAddress();
			if (bytes.length != 16) {
				return ip;
			}
			StringBuilder prefix = new StringBuilder();
			for (int i = 0; i < 8; i += 2) {
				prefix.append(Integer.toHexString(((bytes[i] & 0xFF) << 8) | (bytes[i + 1] & 0xFF))).append(':');
			}
			return prefix.append(":/64").toString();
		}
		catch (IllegalArgumentException ex) {
			return ip;
		}
	}
}
