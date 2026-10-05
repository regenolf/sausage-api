package de.robertegenolf.sausageapi.user;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public class UserRepository {

	public record StoredUser(long id, String username, String passwordHash, String role, int tokenVersion) {
	}

	public record UserProfile(long id, String username, String email, String role, Instant createdAt) {
	}

	/**
	 * Erlaubt sind Buchstaben, Ziffern, Leerzeichen, Punkt, Unterstrich und Bindestrich; Anfang und Ende müssen
	 * Buchstabe oder Ziffer sein. Damit ist der Name kein E-Mail-Login ("@"), bricht Basic Auth nicht (":") und ist
	 * als Pfadsegment (z. B. Admin-Sperre) nutzbar.
	 */
	public static final String USERNAME_PATTERN = "[\\p{L}\\p{N}](?:[\\p{L}\\p{N} ._-]*[\\p{L}\\p{N}])?";

	public static final String USERNAME_MESSAGE = "nur Buchstaben, Ziffern, Leerzeichen, Punkt, Unterstrich und Bindestrich";

	private static final java.util.Set<String> RESERVED_USERNAMES = java.util.Set.of("admin", "administrator",
			"moderator", "moderation", "support", "sausage", "sausageteam", "team", "system", "root");

	/** Vereinheitlicht einen Benutzernamen (Unicode NFKC, Leerzeichen am Rand entfernt). */
	public static String normalizeUsername(String username) {
		return java.text.Normalizer.normalize(username.trim(), java.text.Normalizer.Form.NFKC);
	}

	/** Namen, die nach Betreiber oder Moderation aussehen (Groß-/Kleinschreibung und Trennzeichen egal). */
	public static boolean isReservedUsername(String username) {
		return RESERVED_USERNAMES.contains(username.toLowerCase(java.util.Locale.ROOT).replaceAll("[ ._-]", ""));
	}

	private final JdbcClient jdbc;

	UserRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<StoredUser> findByUsername(String username) {
		return jdbc.sql("SELECT id, username, password_hash, role, token_version FROM app_user WHERE username = :username AND enabled")
				.param("username", username)
				.query((rs, n) -> new StoredUser(rs.getLong("id"), rs.getString("username"),
						rs.getString("password_hash"), rs.getString("role"), rs.getInt("token_version")))
				.optional();
	}

	/**
	 * Enthält {@code login} ein "@", wird per E-Mail gesucht (Groß-/Kleinschreibung egal), sonst per Benutzername.
	 * Da Benutzernamen kein "@" enthalten dürfen, kann ein fremder Account den E-Mail-Login nicht überdecken.
	 */
	public Optional<StoredUser> findByLogin(String login) {
		String where = login.contains("@") ? "lower(email) = lower(:login)" : "username = :login";
		return jdbc.sql("""
				SELECT id, username, password_hash, role, token_version FROM app_user
				WHERE %s AND enabled
				""".formatted(where))
				.param("login", login)
				.query((rs, n) -> new StoredUser(rs.getLong("id"), rs.getString("username"),
						rs.getString("password_hash"), rs.getString("role"), rs.getInt("token_version")))
				.optional();
	}

	Optional<UserProfile> findProfile(String username) {
		return jdbc.sql("SELECT id, username, email, role, created_at FROM app_user WHERE username = :username AND enabled")
				.param("username", username)
				.query((rs, n) -> new UserProfile(rs.getLong("id"), rs.getString("username"),
						rs.getString("email"), rs.getString("role"), rs.getTimestamp("created_at").toInstant()))
				.optional();
	}

	/** Setzt ein neues Passwort und macht damit alle bisher ausgestellten Tokens ungültig. */
	void updatePasswordHash(long id, String passwordHash) {
		jdbc.sql("UPDATE app_user SET password_hash = :passwordHash, token_version = token_version + 1 WHERE id = :id")
				.param("passwordHash", passwordHash)
				.param("id", id)
				.update();
	}

	/** Macht alle bisher ausgestellten Tokens des Users ungültig ("überall abmelden"). */
	public void incrementTokenVersion(long id) {
		jdbc.sql("UPDATE app_user SET token_version = token_version + 1 WHERE id = :id")
				.param("id", id)
				.update();
	}

	/** Sperrt/entsperrt einen Account; alte Tokens werden dabei ungültig. Liefert false, wenn es ihn nicht gibt. */
	public boolean setEnabled(String username, boolean enabled) {
		return jdbc.sql("UPDATE app_user SET enabled = :enabled, token_version = token_version + 1 WHERE username = :username")
				.param("enabled", enabled)
				.param("username", username)
				.update() > 0;
	}

	void delete(long id) {
		jdbc.sql("DELETE FROM app_user WHERE id = :id")
				.param("id", id)
				.update();
	}

	public long create(String username, String email, String passwordHash) {
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
