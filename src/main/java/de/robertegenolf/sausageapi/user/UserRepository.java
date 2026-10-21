package de.robertegenolf.sausageapi.user;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public class UserRepository {

	/** {@code createdAt}: Zeitpunkt der Registrierung – ältere Tokens können nicht zu diesem Account gehören. */
	public record StoredUser(long id, String username, String passwordHash, String role, int tokenVersion,
			Instant createdAt) {
	}

	/**
	 * Profil des angemeldeten Users. {@code termsVersion}/{@code termsAcceptedAt}: zuletzt akzeptierte Nutzungsbedingungen;
	 * weicht {@code currentTermsVersion} ab, sollte der Client erneut um Zustimmung bitten.
	 */
	public record UserProfile(long id, String username, String email, String role, Instant createdAt,
			String termsVersion, Instant termsAcceptedAt, String currentTermsVersion) {
	}

	/** Account aus Sicht der Moderation (ohne Passwort-Hash). */
	public record AdminUser(String username, String email, String role, boolean enabled, Instant createdAt) {
	}

	/** Höchstzahl der Treffer in der Admin-Suche – für mehr den Suchbegriff verfeinern. */
	public static final int ADMIN_SEARCH_LIMIT = 100;

	/**
	 * Erlaubt sind Buchstaben, Ziffern, Leerzeichen, Punkt, Unterstrich und Bindestrich; Anfang und Ende müssen
	 * Buchstabe oder Ziffer sein. Damit ist der Name kein E-Mail-Login ("@"), bricht Basic Auth nicht (":") und ist
	 * als Pfadsegment (z. B. Admin-Sperre) nutzbar.
	 */
	public static final String USERNAME_PATTERN = "[\\p{L}\\p{N}](?:[\\p{L}\\p{N} ._-]*[\\p{L}\\p{N}])?";

	public static final String TERMS_MESSAGE = "Bitte die Nutzungsbedingungen akzeptieren und bestätigen, dass du mindestens 16 Jahre alt bist";

	public static final String USERNAME_MESSAGE = "nur Buchstaben, Ziffern, Leerzeichen, Punkt, Unterstrich und Bindestrich";

	private static final java.util.Set<String> RESERVED_USERNAMES = java.util.Set.of("admin", "administrator",
			"moderator", "moderation", "support", "sausage", "sausageteam", "team", "system", "root");

	/** Vereinheitlicht einen Benutzernamen (Unicode NFKC, Leerzeichen am Rand entfernt). */
	public static String normalizeUsername(String username) {
		return java.text.Normalizer.normalize(username.trim(), java.text.Normalizer.Form.NFKC);
	}

	private static final java.util.regex.Pattern USERNAME_REGEX = java.util.regex.Pattern.compile(USERNAME_PATTERN);

	/**
	 * Prüft den bereits normalisierten Namen erneut: NFKC kann Zeichen vervielfachen (z. B. U+FDFA → 18 Zeichen)
	 * oder in nicht erlaubte umwandeln (½ → 1⁄2), die Bean-Validation sieht aber nur die Eingabe.
	 */
	public static boolean isValidNormalizedUsername(String username) {
		return username.length() >= 3 && username.length() <= 50 && USERNAME_REGEX.matcher(username).matches();
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
		return jdbc.sql("SELECT id, username, password_hash, role, token_version, created_at FROM app_user WHERE username = :username AND enabled")
				.param("username", username)
				.query((rs, n) -> new StoredUser(rs.getLong("id"), rs.getString("username"),
						rs.getString("password_hash"), rs.getString("role"), rs.getInt("token_version"),
						rs.getTimestamp("created_at").toInstant()))
				.optional();
	}

	/**
	 * Enthält {@code login} ein "@", wird per E-Mail gesucht (Groß-/Kleinschreibung egal), sonst per Benutzername.
	 * Da Benutzernamen kein "@" enthalten dürfen, kann ein fremder Account den E-Mail-Login nicht überdecken.
	 */
	public Optional<StoredUser> findByLogin(String login) {
		String where = login.contains("@") ? "lower(email) = lower(:login)" : "username = :login";
		return jdbc.sql("""
				SELECT id, username, password_hash, role, token_version, created_at FROM app_user
				WHERE %s AND enabled
				""".formatted(where))
				.param("login", login)
				.query((rs, n) -> new StoredUser(rs.getLong("id"), rs.getString("username"),
						rs.getString("password_hash"), rs.getString("role"), rs.getInt("token_version"),
						rs.getTimestamp("created_at").toInstant()))
				.optional();
	}

	Optional<UserProfile> findProfile(String username, String currentTermsVersion) {
		return jdbc.sql("""
				SELECT id, username, email, role, created_at, terms_version, terms_accepted_at
				FROM app_user WHERE username = :username AND enabled
				""")
				.param("username", username)
				.query((rs, n) -> {
					java.sql.Timestamp accepted = rs.getTimestamp("terms_accepted_at");
					return new UserProfile(rs.getLong("id"), rs.getString("username"), rs.getString("email"),
							rs.getString("role"), rs.getTimestamp("created_at").toInstant(), rs.getString("terms_version"),
							accepted == null ? null : accepted.toInstant(), currentTermsVersion);
				})
				.optional();
	}

	/** Speichert die Zustimmung zur angegebenen Version der Nutzungsbedingungen (jetzt). */
	void acceptTerms(long id, String termsVersion) {
		jdbc.sql("UPDATE app_user SET terms_version = :version, terms_accepted_at = CURRENT_TIMESTAMP WHERE id = :id")
				.param("version", termsVersion)
				.param("id", id)
				.update();
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

	/**
	 * Accounts für die Moderation, alphabetisch; {@code enabled} null = alle, {@code query} sucht (ohne
	 * Groß-/Kleinschreibung) in Benutzername und E-Mail.
	 */
	public java.util.List<AdminUser> findForAdmin(Boolean enabled, String query) {
		String pattern = query == null || query.isBlank() ? null
				: "%" + query.trim().toLowerCase(java.util.Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%")
						.replace("_", "\\_") + "%";
		return jdbc.sql("""
				SELECT username, email, role, enabled, created_at FROM app_user
				WHERE (CAST(:enabled AS boolean) IS NULL OR enabled = CAST(:enabled AS boolean))
				  AND (CAST(:pattern AS varchar) IS NULL OR LOWER(username) LIKE CAST(:pattern AS varchar)
				       OR LOWER(email) LIKE CAST(:pattern AS varchar))
				ORDER BY LOWER(username)
				LIMIT :limit
				""")
				.param("enabled", enabled)
				.param("pattern", pattern)
				.param("limit", ADMIN_SEARCH_LIMIT)
				.query((rs, n) -> new AdminUser(rs.getString("username"), rs.getString("email"), rs.getString("role"),
						rs.getBoolean("enabled"), rs.getTimestamp("created_at").toInstant()))
				.list();
	}

	/** E-Mail eines Kontos, z. B. für Benachrichtigungen der Moderation (auch gesperrte Konten). */
	public Optional<String> findEmailById(long id) {
		return jdbc.sql("SELECT email FROM app_user WHERE id = :id").param("id", id).query(String.class).optional();
	}

	void delete(long id) {
		jdbc.sql("DELETE FROM app_user WHERE id = :id")
				.param("id", id)
				.update();
	}

	/** Legt einen Account an; {@code termsVersion} ist die akzeptierte Version der Nutzungsbedingungen (null = nicht akzeptiert). */
	public long create(String username, String email, String passwordHash, String termsVersion) {
		return jdbc.sql("""
				INSERT INTO app_user (username, email, password_hash, terms_version, terms_accepted_at)
				VALUES (:username, :email, :passwordHash, CAST(:termsVersion AS varchar),
				        CASE WHEN CAST(:termsVersion AS varchar) IS NULL THEN NULL ELSE CURRENT_TIMESTAMP END)
				RETURNING id
				""")
				.param("username", username)
				.param("email", email)
				.param("passwordHash", passwordHash)
				.param("termsVersion", termsVersion)
				.query(Long.class)
				.single();
	}
}
