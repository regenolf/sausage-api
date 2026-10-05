package de.robertegenolf.sausageapi.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;

/**
 * Bremst Passwort-Raten und Spam-Registrierungen. Fehlgeschlagene Anmeldungen werden pro IP und Account gezählt
 * (damit Nutzer hinter einer gemeinsamen IP, z. B. Mobilfunk-NAT, nicht mitgesperrt werden) und zusätzlich mit
 * einem höheren Limit pro IP (gegen das Durchprobieren vieler Accounts). Angemeldete Anfragen mit Bearer-Token
 * sind nie betroffen. Zähler liegen im Speicher, also pro Instanz.
 */
@Component
public class LoginThrottle {

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
	private final FixedWindowRateLimiter failedPerAccount;
	private final FixedWindowRateLimiter failedPerIp;
	private final FixedWindowRateLimiter registrations;

	LoginThrottle(@Value("${app.rate-limit.enabled:true}") boolean enabled,
			@Value("${app.rate-limit.failed-logins-per-15-minutes:10}") int failedPerAccount,
			@Value("${app.rate-limit.failed-logins-per-ip-per-15-minutes:100}") int failedPerIp,
			@Value("${app.rate-limit.registrations-per-hour:10}") int registrationsPerHour) {
		this.enabled = enabled;
		Clock clock = Clock.systemUTC();
		this.failedPerAccount = new FixedWindowRateLimiter(failedPerAccount, Duration.ofMinutes(15), clock);
		this.failedPerIp = new FixedWindowRateLimiter(failedPerIp, Duration.ofMinutes(15), clock);
		this.registrations = new FixedWindowRateLimiter(registrationsPerHour, Duration.ofHours(1), clock);
	}

	/** Wirft 429, wenn für diese IP und diesen Account zu viele Fehlversuche vorliegen. */
	public void checkLogin(String ip, String login) {
		if (!enabled) {
			return;
		}
		String key = accountKey(ip, login);
		if (failedPerAccount.isBlocked(key)) {
			throw new TooManyRequestsException(MESSAGE, failedPerAccount.secondsUntilReset(key));
		}
		if (failedPerIp.isBlocked(ip)) {
			throw new TooManyRequestsException(MESSAGE, failedPerIp.secondsUntilReset(ip));
		}
	}

	public void loginFailed(String ip, String login) {
		if (enabled) {
			failedPerAccount.record(accountKey(ip, login));
			failedPerIp.record(ip);
		}
	}

	/** Zählt eine Registrierung und wirft 429, wenn diese IP zu viele angelegt hat. */
	public void checkRegistration(String ip) {
		if (enabled && !registrations.tryAcquire(ip)) {
			throw new TooManyRequestsException("Zu viele Registrierungen. Bitte später erneut versuchen.",
					registrations.secondsUntilReset(ip));
		}
	}

	private static final String MESSAGE = "Zu viele fehlgeschlagene Anmeldeversuche. Bitte später erneut versuchen.";

	private static String accountKey(String ip, String login) {
		return ip + "|" + (login == null ? "" : login.trim().toLowerCase(Locale.ROOT));
	}
}
