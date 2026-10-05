package de.robertegenolf.sausageapi.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Einfacher Zähler pro Schlüssel (z. B. IP-Adresse) in festen Zeitfenstern, nur im Speicher.
 * Reicht für eine einzelne Instanz; bei mehreren Instanzen zählt jede für sich.
 */
final class FixedWindowRateLimiter {

	private record Window(Instant start, int count) {
	}

	private final int limit;
	private final Duration window;
	private final Clock clock;
	private final Map<String, Window> windows = new ConcurrentHashMap<>();
	private final int maxKeys;
	private volatile Instant lastCleanup = Instant.EPOCH;

	FixedWindowRateLimiter(int limit, Duration window, Clock clock) {
		this(limit, window, clock, 100_000);
	}

	/** {@code maxKeys} begrenzt den Speicher auch dann, wenn ein Angreifer sehr viele Schlüssel erzeugt. */
	FixedWindowRateLimiter(int limit, Duration window, Clock clock, int maxKeys) {
		this.limit = limit;
		this.window = window;
		this.clock = clock;
		this.maxKeys = maxKeys;
	}

	/** Zählt einen Versuch und liefert true, solange das Limit nicht überschritten ist. */
	boolean tryAcquire(String key) {
		return increment(key).count() <= limit;
	}

	/** Zählt einen Versuch, ohne zu prüfen (z. B. fehlgeschlagener Login). */
	void record(String key) {
		increment(key);
	}

	/** True, wenn das Limit im aktuellen Fenster bereits erreicht ist. */
	int size() {
		return windows.size();
	}

	boolean isBlocked(String key) {
		Window w = windows.get(key);
		return w != null && !expired(w, clock.instant()) && w.count() >= limit;
	}

	/** Sekunden bis zum Ende des aktuellen Fensters (für den Retry-After-Header). */
	long secondsUntilReset(String key) {
		Window w = windows.get(key);
		if (w == null) {
			return 0;
		}
		return Math.max(1, Duration.between(clock.instant(), w.start().plus(window)).toSeconds());
	}

	private Window increment(String key) {
		Instant now = clock.instant();
		// Abgelaufene Fenster höchstens einmal pro Sekunde entfernen (kein voller Scan bei jedem Aufruf)
		if (windows.size() > maxKeys / 10 && now.isAfter(lastCleanup.plusSeconds(1))) {
			lastCleanup = now;
			windows.values().removeIf(w -> expired(w, now));
		}
		// Notbremse gegen Speicherüberlauf: lieber kurz alle Zähler vergessen als den Server lahmlegen
		if (windows.size() >= maxKeys) {
			windows.clear();
		}
		return windows.compute(key, (k, w) -> w == null || expired(w, now)
				? new Window(now, 1)
				: new Window(w.start(), w.count() + 1));
	}

	private boolean expired(Window w, Instant now) {
		return !now.isBefore(w.start().plus(window));
	}
}
