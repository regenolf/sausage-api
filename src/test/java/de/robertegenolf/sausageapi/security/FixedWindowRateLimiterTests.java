package de.robertegenolf.sausageapi.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class FixedWindowRateLimiterTests {

	private static final class MutableClock extends Clock {

		Instant now = Instant.parse("2026-01-01T12:00:00Z");

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}
	}

	@Test
	void limitGiltProFensterUndSchluessel() {
		MutableClock clock = new MutableClock();
		FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(2, Duration.ofMinutes(1), clock);

		assertThat(limiter.tryAcquire("a")).isTrue();
		assertThat(limiter.tryAcquire("a")).isTrue();
		assertThat(limiter.tryAcquire("a")).isFalse();
		assertThat(limiter.tryAcquire("b")).isTrue();
		assertThat(limiter.secondsUntilReset("a")).isEqualTo(60);

		clock.now = clock.now.plusSeconds(60);
		assertThat(limiter.tryAcquire("a")).isTrue();
	}

	@Test
	void sperreNachAufgezeichnetenFehlversuchen() {
		MutableClock clock = new MutableClock();
		FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(2, Duration.ofMinutes(15), clock);

		limiter.record("ip");
		assertThat(limiter.isBlocked("ip")).isFalse();
		limiter.record("ip");
		assertThat(limiter.isBlocked("ip")).isTrue();

		clock.now = clock.now.plus(Duration.ofMinutes(15));
		assertThat(limiter.isBlocked("ip")).isFalse();
	}

	@Test
	void speicherBleibtBegrenzt() {
		MutableClock clock = new MutableClock();
		FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(5, Duration.ofMinutes(15), clock, 1000);
		for (int i = 0; i < 5000; i++) {
			limiter.record("ip-" + i);
		}
		assertThat(limiter.size()).isLessThanOrEqualTo(1000);
	}

	@Test
	void ipv6WirdAufPraefixReduziert() {
		assertThat(LoginThrottle.clientKey("2001:db8:1:2:aaaa::1")).isEqualTo(LoginThrottle.clientKey("2001:db8:1:2:ffff:1:2:3"));
		assertThat(LoginThrottle.clientKey("2001:db8:1:2::1")).isNotEqualTo(LoginThrottle.clientKey("2001:db8:1:3::1"));
		assertThat(LoginThrottle.clientKey("203.0.113.7")).isEqualTo("203.0.113.7");
	}
}
