# sausage-api
Springboot Applikation Backend

## Lokal starten

```bash
docker compose up -d   # Postgres auf localhost:5432
./mvnw spring-boot:run # Liquibase legt das Schema beim Start an
```

Die Tests (`./mvnw test`) laufen ebenfalls gegen diese lokale Datenbank.

API und Datenbank komplett im Container:

```bash
docker compose --profile app up --build   # API auf http://localhost:8080
```

### Konfiguration (Umgebungsvariablen)

| Variable | Standard |
|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/sausage` |
| `DB_USERNAME` / `DB_PASSWORD` | `sausage` / `sausage` |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://localhost:5173` |

API-Doku (Swagger UI): http://localhost:8080/swagger-ui.html

Health-Checks: `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`.
Die GitHub-Actions-CI (`.github/workflows/ci.yml`) startet Postgres als Service und führt `./mvnw verify` aus.

## API

Lesende `GET`-Endpunkte sind öffentlich, alles andere braucht HTTP Basic Auth.

| Methode | Pfad | Beschreibung |
|---|---|---|
| `POST` | `/api/users` | Registrieren (öffentlich) |
| `GET` | `/api/users/me` | Eigenes Profil 🔒 |
| `GET` | `/api/users/me/spots` | Eigene Spots, neueste zuerst 🔒 |
| `GET` | `/api/categories` | Alle Kategorien |
| `GET` | `/api/spots?category=&q=&page=0&size=20` | Spots paginiert (`content`, `totalElements`, `totalPages`), Suche in Name/Stadt |
| `GET` | `/api/spots/nearby?latitude=&longitude=&radiusKm=&category=` | Umkreissuche, sortiert nach Entfernung (`distanceKm`) |
| `GET` | `/api/spots/{id}` | Spot-Details inkl. Durchschnittsbewertung und Ersteller |
| `POST` | `/api/spots` | Spot anlegen 🔒 |
| `PUT` | `/api/spots/{id}` | Spot bearbeiten 🔒 (nur Ersteller) |
| `DELETE` | `/api/spots/{id}` | Spot inkl. Bewertungen/Kommentaren löschen 🔒 (nur Ersteller) |
| `POST` | `/api/spots/{id}/ratings` | Bewerten (1–5), erneutes Bewerten überschreibt 🔒 |
| `GET` | `/api/spots/{id}/ratings/me` | Eigene Bewertung 🔒 |
| `DELETE` | `/api/spots/{id}/ratings/me` | Eigene Bewertung zurücknehmen 🔒 |
| `GET` | `/api/spots/{id}/comments` | Kommentare (neueste zuerst, mit Benutzername) |
| `POST` | `/api/spots/{id}/comments` | Kommentieren 🔒 |
| `DELETE` | `/api/spots/{id}/comments/{commentId}` | Kommentar löschen 🔒 (nur Autor) |

Fehler kommen als [Problem Details](https://www.rfc-editor.org/rfc/rfc9457) (`application/problem+json`) mit
deutscher Meldung in `detail`; bei Validierungsfehlern stehen die betroffenen Felder in `errors`.
