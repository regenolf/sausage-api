package de.robertegenolf.sausageapi.user;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
class UserBlockRepository {

	record BlockedUser(String username, Instant blockedAt) {
	}

	private final JdbcClient jdbc;

	UserBlockRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** ID eines (auch gesperrten) Accounts per Benutzername. */
	Optional<Long> findUserId(String username) {
		return jdbc.sql("SELECT id FROM app_user WHERE username = :username")
				.param("username", username)
				.query(Long.class)
				.optional();
	}

	void block(long blockerId, long blockedId) {
		jdbc.sql("""
				INSERT INTO user_block (blocker_id, blocked_id) VALUES (:blocker, :blocked)
				ON CONFLICT DO NOTHING
				""")
				.param("blocker", blockerId)
				.param("blocked", blockedId)
				.update();
	}

	void unblock(long blockerId, long blockedId) {
		jdbc.sql("DELETE FROM user_block WHERE blocker_id = :blocker AND blocked_id = :blocked")
				.param("blocker", blockerId)
				.param("blocked", blockedId)
				.update();
	}

	List<BlockedUser> findBlocked(long blockerId) {
		return jdbc.sql("""
				SELECT u.username, b.created_at
				FROM user_block b JOIN app_user u ON u.id = b.blocked_id
				WHERE b.blocker_id = :blocker
				ORDER BY lower(u.username)
				""")
				.param("blocker", blockerId)
				.query((rs, n) -> new BlockedUser(rs.getString("username"), rs.getTimestamp("created_at").toInstant()))
				.list();
	}
}
