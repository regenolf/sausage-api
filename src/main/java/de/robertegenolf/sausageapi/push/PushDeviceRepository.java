package de.robertegenolf.sausageapi.push;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Push-Token der Geräte. Ein Token gehört immer dem Konto, das sich zuletzt auf dem Gerät angemeldet hat. */
@Repository
class PushDeviceRepository {

	enum Platform { ANDROID, IOS }

	/** Höchstzahl Geräte pro Konto; ältere fallen beim Registrieren heraus. */
	static final int MAX_DEVICES_PER_USER = 10;

	private final JdbcClient jdbc;

	PushDeviceRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	void register(long userId, String token, Platform platform) {
		jdbc.sql("""
				INSERT INTO push_device (user_id, token, platform) VALUES (:userId, :token, :platform)
				ON CONFLICT (token) DO UPDATE SET user_id = EXCLUDED.user_id, platform = EXCLUDED.platform,
				                                  last_seen_at = CURRENT_TIMESTAMP
				""")
				.param("userId", userId)
				.param("token", token)
				.param("platform", platform.name())
				.update();
		jdbc.sql("""
				DELETE FROM push_device WHERE user_id = :userId AND id NOT IN (
				    SELECT id FROM push_device WHERE user_id = :userId ORDER BY last_seen_at DESC, id DESC LIMIT :max)
				""")
				.param("userId", userId)
				.param("max", MAX_DEVICES_PER_USER)
				.update();
	}

	void unregister(long userId, String token) {
		jdbc.sql("DELETE FROM push_device WHERE user_id = :userId AND token = :token")
				.param("userId", userId)
				.param("token", token)
				.update();
	}

	/** Vom Push-Dienst als ungültig gemeldete Token (App deinstalliert, Token erneuert). */
	void removeToken(String token) {
		jdbc.sql("DELETE FROM push_device WHERE token = :token").param("token", token).update();
	}

	record Device(String token, Platform platform) {
	}

	List<Device> devices(long userId) {
		return jdbc.sql("SELECT token, platform FROM push_device WHERE user_id = :userId")
				.param("userId", userId)
				.query((rs, n) -> new Device(rs.getString("token"), Platform.valueOf(rs.getString("platform"))))
				.list();
	}
}
