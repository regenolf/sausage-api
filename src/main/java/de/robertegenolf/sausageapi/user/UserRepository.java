package de.robertegenolf.sausageapi.user;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public class UserRepository {

	public record StoredUser(long id, String username, String passwordHash) {
	}

	public record UserProfile(long id, String username, String email, Instant createdAt) {
	}

	private final JdbcClient jdbc;

	UserRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<StoredUser> findByUsername(String username) {
		return jdbc.sql("SELECT id, username, password_hash FROM app_user WHERE username = :username AND enabled")
				.param("username", username)
				.query((rs, n) -> new StoredUser(rs.getLong("id"), rs.getString("username"),
						rs.getString("password_hash")))
				.optional();
	}

	Optional<UserProfile> findProfile(String username) {
		return jdbc.sql("SELECT id, username, email, created_at FROM app_user WHERE username = :username AND enabled")
				.param("username", username)
				.query((rs, n) -> new UserProfile(rs.getLong("id"), rs.getString("username"),
						rs.getString("email"), rs.getTimestamp("created_at").toInstant()))
				.optional();
	}

	long create(String username, String email, String passwordHash) {
		return jdbc.sql("""
				INSERT INTO app_user (username, email, password_hash)
				VALUES (:username, :email, :passwordHash)
				RETURNING id
				""")
				.param("username", username)
				.param("email", email)
				.param("passwordHash", passwordHash)
				.query(Long.class)
				.single();
	}
}
