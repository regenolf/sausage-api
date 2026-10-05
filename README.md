# sausage-api
Springboot Applikation Backend

## Lokal starten

```bash
docker compose up -d   # Postgres auf localhost:5432
./mvnw spring-boot:run # Liquibase legt das Schema beim Start an
```

Ohne docker compose, mit einer Wegwerf-Datenbank aus Docker (Testcontainers):

```bash
./mvnw spring-boot:test-run
```

Die Tests (`./mvnw test`) starten ihre eigene Postgres über Testcontainers – es muss nur Docker laufen.

API und Datenbank komplett im Container:

```bash
docker compose --profile app up --build   # API auf http://localhost:8080
```

### Konfiguration (Umgebungsvariablen)

| Variable | Standard |
|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/sausage` |
| `DB_USERNAME` / `DB_PASSWORD` | `sausage` / `sausage` |
| `JWT_SECRET` | Entwicklungs-Schlüssel – **in Produktion setzen** (mind. 32 Zeichen) |
| `JWT_VALIDITY` | `PT1H` |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://localhost:5173` |

API-Doku (Swagger UI): http://localhost:8080/swagger-ui.html

Health-Checks: `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`.
Die GitHub-Actions-CI (`.github/workflows/ci.yml`) führt `./mvnw verify` aus.

## API

Lesende `GET`-Endpunkte sind öffentlich, alles andere braucht Anmeldung – entweder per HTTP Basic Auth
oder per JWT:

```bash
curl -u name:passwort -X POST http://localhost:8080/api/auth/token
# {"accessToken":"eyJ...","tokenType":"Bearer","expiresIn":3600}
curl -H "Authorization: Bearer eyJ..." http://localhost:8080/api/users/me
```

Tokens sind `app.jwt.validity` lang gültig (Standard 1 Stunde) und bleiben das auch nach einer
Passwortänderung bis zum Ablauf.

| Methode | Pfad | Beschreibung |
|---|---|---|
| `POST` | `/api/users` | Registrieren (öffentlich) |
| `POST` | `/api/auth/token` | JWT holen (mit Basic Auth oder gültigem Token) 🔒 |
| `GET` | `/api/users/me` | Eigenes Profil 🔒 |
| `GET` | `/api/users/me/spots` | Eigene Spots, neueste zuerst 🔒 |
| `PUT` | `/api/users/me/password` | Passwort ändern (`currentPassword`, `newPassword`) 🔒 |
| `GET` | `/api/categories` | Alle Kategorien |
| `POST` | `/api/categories` | Kategorie anlegen 🔒 (nur Admin) |
| `GET` | `/api/spots?category=&q=&page=0&size=20` | Spots paginiert (`content`, `totalElements`, `totalPages`), Suche in Name/Stadt |
| `GET` | `/api/spots/nearby?latitude=&longitude=&radiusKm=&category=` | Umkreissuche, sortiert nach Entfernung (`distanceKm`) |
| `GET` | `/api/spots/{id}` | Spot-Details inkl. Durchschnittsbewertung und Ersteller |
| `POST` | `/api/spots` | Spot anlegen 🔒 |
| `PUT` | `/api/spots/{id}` | Spot bearbeiten 🔒 (nur Ersteller) |
| `DELETE` | `/api/spots/{id}` | Spot inkl. Bewertungen/Kommentaren löschen 🔒 (Ersteller oder Admin) |
| `POST` | `/api/spots/{id}/ratings` | Bewerten (1–5), erneutes Bewerten überschreibt 🔒 |
| `GET` | `/api/spots/{id}/ratings/me` | Eigene Bewertung 🔒 |
| `DELETE` | `/api/spots/{id}/ratings/me` | Eigene Bewertung zurücknehmen 🔒 |
| `GET` | `/api/spots/{id}/photos` | Fotos eines Spots (Metadaten mit `url`) |
| `GET` | `/api/spots/{id}/photos/{photoId}` | Das Bild selbst |
| `POST` | `/api/spots/{id}/photos` | Foto hochladen (multipart, Feld `file`, JPEG/PNG/WebP, max. 5 MB, max. 20 je Spot) 🔒 |
| `DELETE` | `/api/spots/{id}/photos/{photoId}` | Foto löschen 🔒 (Hochladender oder Admin) |
| `GET` | `/api/spots/{id}/comments` | Kommentare (neueste zuerst, mit Benutzername) |
| `POST` | `/api/spots/{id}/comments` | Kommentieren 🔒 |
| `DELETE` | `/api/spots/{id}/comments/{commentId}` | Kommentar löschen 🔒 (Autor oder Admin) |

Fehler kommen als [Problem Details](https://www.rfc-editor.org/rfc/rfc9457) (`application/problem+json`) mit
deutscher Meldung in `detail`; bei Validierungsfehlern stehen die betroffenen Felder in `errors`.

### Admins

Neue User haben die Rolle `USER`. Einen Admin ernennt man direkt in der Datenbank:

```sql
UPDATE app_user SET role = 'ADMIN' WHERE username = '<name>';
```
