package de.robertegenolf.sausageapi.user;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Accounts der App. Angemeldet wird über Keycloak; {@code external_id} ist die Keycloak-User-ID ({@code sub}).
 * Benutzername, Rolle und Sperre verwaltet weiterhin die App (siehe {@link AccountLinker}).
 */
@Repository
public class UserRepository {

	/** {@code externalId}: Keycloak-User-ID, {@code null} bei alten Accounts aus der Zeit vor Keycloak. */
	public record StoredUser(long id, String username, String email, String role, boolean enabled, String externalId,
			Instant createdAt) {
	}

	/**
	 * Profil des angemeldeten Users. {@code termsVersion}/{@code termsAcceptedAt}: zuletzt akzeptierte Nutzungsbedingungen;
	 * weicht {@code currentTermsVersion} ab, sollte der Client erneut um Zustimmung bitten.
	 */
	public record UserProfile(long id, String username, String email, String role, Instant createdAt,
			String termsVersion, Instant termsAcceptedAt, String currentTermsVersion) {
	}

	/** Account aus Sicht der Moderation. */
	public record AdminUser(String username, String email, String role, boolean enabled, Instant createdAt) {
	}

	/** Höchstzahl der Treffer in der Admin-Suche – für mehr den Suchbegriff verfeinern. */
	public static final int ADMIN_SEARCH_LIMIT = 100;

	/**
	 * Erlaubt sind Buchstaben, Ziffern, Leerzeichen, Punkt, Unterstrich und Bindestrich; Anfang und Ende müssen
	 * Buchstabe oder Ziffer sein. Damit ist der Name als Pfadsegment (z. B. Admin-Sperre) nutzbar. Keycloak prüft
	 * bei der Registrierung dasselbe Muster (Realm-Datei in kvm-infra).
	 */
	public static final String USERNAME_PATTERN = "[\\p{L}\\p{N}](?:[\\p{L}\\p{N} ._-]*[\\p{L}\\p{N}])?";

	public static final String USERNAME_MESSAGE = "nur Buchstaben, Ziffern, Leerzeichen, Punkt, Unterstrich und Bindestrich";

	private static final java.util.Set<String> RESERVED_USERNAMES = java.util.Set.of("admin", "administrator",
			"moderator", "moderation", "support", "sausage", "sausageteam", "team", "system", "root");

	/** Vereinheitlicht einen Benutzernamen (Unicode NFKC, Leerzeichen am Rand entfernt). */
	public static String normalizeUsername(String username) {
		return java.text.Normalizer.normalize(username.trim(), java.text.Normalizer.Form.NFKC);
	}

	private static final java.util.regex.Pattern USERNAME_REGEX = java.util.regex.Pattern.compile(USERNAME_PATTERN);

	/**
	 * Prüft den bereits normalisierten Namen: NFKC kann Zeichen vervielfachen (z. B. U+FDFA → 18 Zeichen) oder in
	 * nicht erlaubte umwandeln (½ → 1⁄2).
	 */
	public static boolean isValidNormalizedUsername(String username) {
		return username.length() >= 3 && username.length() <= 50 && USERNAME_REGEX.matcher(username).matches();
	}

	/** Namen, die nach Betreiber oder Moderation aussehen (Groß-/Kleinschreibung und Trennzeichen egal). */
	public static boolean isReservedUsername(String username) {
		return RESERVED_USERNAMES.contains(username.toLowerCase(java.util.Locale.ROOT).replaceAll("[ ._-]", ""));
	}

	private static final String USER_COLUMNS = "id, username, email, role, enabled, external_id, created_at";

	private static final RowMapper<StoredUser> USER_MAPPER = (rs, n) -> new StoredUser(rs.getLong("id"),
			rs.getString("username"), rs.getString("email"), rs.getString("role"), rs.getBoolean("enabled"),
			rs.getString("external_id"), rs.getTimestamp("created_at").toInstant());

	private final JdbcClient jdbc;

	UserRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Aktiver Account mit diesem Benutzernamen. */
	public Optional<StoredUser> findByUsername(String username) {
		return jdbc.sql("SELECT " + USER_COLUMNS + " FROM app_user WHERE username = :username AND enabled")
				.param("username", username)
				.query(USER_MAPPER)
				.optional();
	}

	/** Auch gesperrte Accounts – nur für DSGVO-Export und Kontolöschung. */
	Optional<StoredUser> findByUsernameIncludingDisabled(String username) {
		return jdbc.sql("SELECT " + USER_COLUMNS + " FROM app_user WHERE username = :username")
				.param("username", username)
				.query(USER_MAPPER)
				.optional();
	}

	/** Account zur Keycloak-User-ID, auch gesperrt (die Sperre wertet die Anmeldung aus). */
	public Optional<StoredUser> findByExternalId(String externalId) {
		return jdbc.sql("SELECT " + USER_COLUMNS + " FROM app_user WHERE external_id = :externalId")
				.param("externalId", externalId)
				.query(USER_MAPPER)
				.optional();
	}

	/** Alter Account (vor Keycloak) mit dieser E-Mail, Groß-/Kleinschreibung egal. */
	Optional<StoredUser> findUnlinkedByEmail(String email) {
		return jdbc.sql("SELECT " + USER_COLUMNS + " FROM app_user WHERE lower(email) = lower(:email) AND external_id IS NULL")
				.param("email", email)
				.query(USER_MAPPER)
				.optional();
	}

	boolean emailTaken(String email) {
		return jdbc.sql("SELECT EXISTS (SELECT 1 FROM app_user WHERE lower(email) = lower(:email))")
				.param("email", email)
				.query(Boolean.class)
				.single();
	}

	boolean usernameTaken(String username) {
		return jdbc.sql("SELECT EXISTS (SELECT 1 FROM app_user WHERE lower(username) = lower(:username))")
				.param("username", username)
				.query(Boolean.class)
				.single();
	}

	void linkExternalId(long id, String externalId) {
		jdbc.sql("UPDATE app_user SET external_id = :externalId WHERE id = :id AND external_id IS NULL")
				.param("externalId", externalId)
				.param("id", id)
				.update();
	}

	/** Legt den Account zu einem Keycloak-Konto an (Nutzungsbedingungen noch nicht akzeptiert). */
	long createExternal(String externalId, String username, String email) {
		return jdbc.sql("""
				INSERT INTO app_user (external_id, username, email)
				VALUES (:externalId, :username, :email)
				RETURNING id
				""")
				.param("externalId", externalId)
				.param("username", username)
				.param("email", email)
				.query(Long.class)
				.single();
	}

	void updateEmail(long id, String email) {
		jdbc.sql("UPDATE app_user SET email = :email WHERE id = :id")
				.param("email", email)
				.param("id", id)
				.update();
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

	/**
	 * Sperrt/entsperrt einen Account. Wirkt sofort: Die Anmeldung prüft die Sperre bei jeder Anfrage. Liefert false,
	 * wenn es ihn nicht gibt.
	 */
	public boolean setEnabled(String username, boolean enabled) {
		return jdbc.sql("UPDATE app_user SET enabled = :enabled WHERE username = :username")
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

	/**
	 * Löscht den Account. Die Keycloak-ID bleibt eine Stunde gesperrt: Access Tokens gelten bis zu 5 Minuten weiter
	 * und würden sonst sofort einen neuen, leeren Account anlegen.
	 */
	@Transactional
	void delete(StoredUser user) {
		jdbc.sql("DELETE FROM app_user WHERE id = :id")
				.param("id", user.id())
				.update();
		if (user.externalId() != null) {
			jdbc.sql("DELETE FROM deleted_account WHERE deleted_at < CURRENT_TIMESTAMP - INTERVAL '1 hour'").update();
			jdbc.sql("""
					INSERT INTO deleted_account (external_id) VALUES (:externalId)
					ON CONFLICT (external_id) DO UPDATE SET deleted_at = CURRENT_TIMESTAMP
					""")
					.param("externalId", user.externalId())
					.update();
		}
	}

	/** Wurde das Keycloak-Konto in der letzten Stunde hier gelöscht? */
	boolean recentlyDeleted(String externalId) {
		return jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM deleted_account
				               WHERE external_id = :externalId AND deleted_at > CURRENT_TIMESTAMP - INTERVAL '1 hour')
				""")
				.param("externalId", externalId)
				.query(Boolean.class)
				.single();
	}
}
