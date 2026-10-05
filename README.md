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

### Produktion (App + API + Datenbank + HTTPS + Backups)

`deploy/docker-compose.prod.yml` startet alles zusammen auf einem Server: Caddy (HTTPS mit automatischem
Let's-Encrypt-Zertifikat) → nginx der App (`regenolf/sausage-app`, liefert die Web-App aus und leitet `/api` weiter) → API
→ Postgres, dazu ein tägliches `pg_dump` nach `deploy/backups/`. API, Datenbank, Actuator und Swagger sind von außen
nicht erreichbar, nur `https://DOMAIN` und `https://DOMAIN/api/…`.

```bash
git clone https://github.com/regenolf/sausage-api && git clone https://github.com/regenolf/sausage-app
cd sausage-api/deploy
cp .env.example .env        # DOMAIN, DB_PASSWORD, JWT_SECRET eintragen
docker compose -f docker-compose.prod.yml up -d --build
```

Voraussetzungen: DNS von `DOMAIN` zeigt auf den Server, Ports 80 und 443 sind offen.
Backups zusätzlich außerhalb des Servers sichern (sie enthalten auch alle Fotos). Wiederherstellen:

```bash
docker compose -f docker-compose.prod.yml exec -T db pg_restore -U sausage -d sausage --clean < backups/sausage-JJJJ-MM-TT_HHMM.dump
```

### Konfiguration (Umgebungsvariablen)

| Variable | Standard |
|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/sausage` |
| `DB_USERNAME` / `DB_PASSWORD` | `sausage` / `sausage` |
| `JWT_SECRET` | leer → zufälliger Schlüssel pro Start (Anmeldungen gehen bei Neustart verloren) – **in Produktion setzen** (mind. 32 Zeichen) |
| `TERMS_VERSION` | `2026-10-05` – bei jeder Änderung der Nutzungsbedingungen erhöhen |
| `PUBLIC_URL` | leer (aus der Anfrage); öffentliche Adresse für absolute Foto-URLs, im Produktions-Setup `https://DOMAIN` |
| `API_DOCS_ENABLED` | `true` (Swagger UI und `/v3/api-docs`; im Produktions-Setup `false`) |
| `JWT_VALIDITY` | `P30D` |
| `app.rate-limit.registrations-per-hour` | `10` Registrierungen pro IP und Stunde |
| `app.rate-limit.failed-logins-per-15-minutes` | `10` Fehlversuche pro IP und Account, danach 429 bis zum Fensterende |
| `app.rate-limit.failed-logins-per-ip-per-15-minutes` | `100` Fehlversuche pro IP über alle Accounts |
| `app.rate-limit.writes-per-hour` | `200` Schreibzugriffe (Anlegen/Ändern/Löschen) pro User und Stunde |
| `app.rate-limit.reports-per-hour` | `30` Meldungen pro IP und Stunde |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:8100,http://localhost:4200,capacitor://localhost,https://localhost,http://localhost:3000,http://localhost:5173` |

API-Doku (Swagger UI): http://localhost:8080/swagger-ui.html

Hinter einem Reverse-Proxy im privaten Netz (z. B. dem nginx der sausage-app) übernimmt die API die echte
Client-IP aus `X-Forwarded-For` (`server.forward-headers-strategy=native`), damit die Begrenzungen pro Nutzer greifen.

Health-Checks: `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`.
Die GitHub-Actions-CI (`.github/workflows/ci.yml`) führt `./mvnw verify` aus.

## API

Lesende `GET`-Endpunkte sind öffentlich, alles andere braucht Anmeldung (Frontend: `regenolf/sausage-app`) – entweder per HTTP Basic Auth
oder per JWT:

```bash
curl -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' \
     -d '{"email":"ich@example.de","password":"geheim123"}'   # E-Mail oder Benutzername
# {"token":"eyJ...","accessToken":"eyJ...","tokenType":"Bearer","expiresIn":3600}
curl -H "Authorization: Bearer eyJ..." http://localhost:8080/api/users/me
```

Tokens sind `app.jwt.validity` lang gültig (Standard 30 Tage). Die API prüft bei jeder Anfrage gegen die
Datenbank, ob das Token noch gilt: Eine Passwortänderung oder `POST /api/auth/logout-all` macht alle alten Tokens
sofort ungültig, ebenso ein gesperrter oder gelöschter Account. Rollenänderungen wirken sofort.

| Methode | Pfad | Beschreibung |
|---|---|---|
| `POST` | `/api/users` | Registrieren (`username`, `email`, `password`, `acceptTerms: true`) |
| `POST` | `/api/auth/register` | Registrieren und direkt anmelden (`email`, `password`, `acceptTerms: true` = Nutzungsbedingungen akzeptiert und mindestens 16 Jahre – noch optional, bis die App es mitschickt –, `displayName` 3–50 Zeichen: Buchstaben, Ziffern, Leerzeichen, `.`, `_`, `-`; reservierte Namen wie „Admin“ sind gesperrt) → Token |
| `POST` | `/api/auth/login` | Anmelden (`email` oder Benutzername, `password`) → Token |
| `POST` | `/api/auth/token` | JWT holen bzw. verlängern (mit Basic Auth oder gültigem Token) 🔒 |
| `POST` | `/api/auth/logout-all` | Auf allen Geräten abmelden (alle Tokens ungültig) 🔒 |
| `GET` | `/api/users/me` | Eigenes Profil inkl. akzeptierter (`termsVersion`) und aktueller Version der Nutzungsbedingungen (`currentTermsVersion`) 🔒 |
| `POST` | `/api/users/me/terms` | Aktuelle Nutzungsbedingungen akzeptieren (nach einer Änderung) 🔒 |
| `GET` | `/api/users/me/spots` | Eigene Spots, neueste zuerst 🔒 |
| `GET` | `/api/users/me/blocks` | Von mir blockierte Nutzer 🔒 |
| `POST` | `/api/users/me/blocks` | Nutzer blockieren `{"username":…}`: seine Bewertungen, Kommentare und Fotos werden für mich ausgeblendet 🔒 |
| `DELETE` | `/api/users/me/blocks/{username}` | Blockierung aufheben 🔒 |
| `GET` | `/api/users/me/export` | Alle eigenen Daten als JSON-Datei (Auskunft/Datenübertragbarkeit, Art. 15/20 DSGVO) 🔒 |
| `DELETE` | `/api/users/me` | Account löschen (Body `{"password":…}`): Bewertungen, Kommentare, Fotos werden gelöscht, eigene Spots anonymisiert 🔒 |
| `PUT` | `/api/users/me/password` | Passwort ändern (`currentPassword`, `newPassword`); meldet alle anderen Geräte ab und liefert ein neues Token 🔒 |
| `GET` | `/api/categories` | Alle Kategorien |
| `POST` | `/api/categories` | Kategorie anlegen 🔒 (nur Admin) |
| `GET` | `/api/spots?category=&q=&page=&size=` | Spots als Liste, Suche in Name/Stadt; mit `size` seitenweise, Gesamtzahl im Header `X-Total-Count` |
| `GET` | `/api/spots/nearby?latitude=&longitude=&radiusKm=&category=` | Umkreissuche, sortiert nach Entfernung (`distanceKm`) |
| `GET` | `/api/spots/{id}` | Spot-Details inkl. Durchschnittsbewertung, Ersteller und `photos` |
| `POST` | `/api/spots` | Spot anlegen 🔒 |
| `PUT` | `/api/spots/{id}` | Spot bearbeiten 🔒 (nur Ersteller) |
| `DELETE` | `/api/spots/{id}` | Spot inkl. Bewertungen/Kommentaren/Fotos löschen 🔒 (Admin; Ersteller nur, solange keine Beiträge anderer existieren, sonst 409) |
| `GET` | `/api/spots/{id}/ratings` | Bewertungen (`score`, `comment`, `author`, `authorName`), neueste zuerst |
| `POST` | `/api/spots/{id}/ratings` | Bewerten (`score` 1–5, optional `comment`), erneutes Bewerten überschreibt → Spot 🔒 |
| `PUT` | `/api/spots/{id}/ratings/{ratingId}` | Eigene Bewertung ändern → Spot 🔒 |
| `DELETE` | `/api/spots/{id}/ratings/{ratingId}` | Bewertung löschen (Verfasser oder Admin) → Spot 🔒 |
| `GET` | `/api/spots/{id}/ratings/me` | Eigene Bewertung 🔒 |
| `DELETE` | `/api/spots/{id}/ratings/me` | Eigene Bewertung zurücknehmen 🔒 |
| `GET` | `/api/spots/{id}/photos` | Fotos eines Spots (Metadaten mit `url`) |
| `GET` | `/api/spots/{id}/photos/{photoId}` | Das Bild selbst |
| `POST` | `/api/spots/{id}/photos` | Foto hochladen (multipart, Feld `file`, JPEG/PNG/WebP, max. 10 MB, 40 Megapixel und 20 je Spot; EXIF/GPS-Metadaten werden entfernt) 🔒 |
| `DELETE` | `/api/spots/{id}/photos/{photoId}` | Foto löschen 🔒 (Hochladender, Ersteller des Spots oder Admin) |
| `GET` | `/api/spots/{id}/comments` | Kommentare (neueste zuerst, mit Benutzername) |
| `POST` | `/api/spots/{id}/comments` | Kommentieren 🔒 |
| `DELETE` | `/api/spots/{id}/comments/{commentId}` | Kommentar löschen 🔒 (Autor oder Admin) |

Fehler kommen als [Problem Details](https://www.rfc-editor.org/rfc/rfc9457) (`application/problem+json`) mit
deutscher Meldung in `detail`; bei Validierungsfehlern stehen die betroffenen Felder in `errors`.

### Melden und Moderation (Art. 16 DSA)

| Methode | Pfad | Beschreibung |
|---|---|---|
| `POST` | `/api/reports` | Inhalt melden (`targetType` SPOT/RATING/COMMENT/PHOTO, `targetId`, `reason` ILLEGAL/INSULT/SPAM/PRIVACY/COPYRIGHT/WRONG_INFO/OTHER, `message`; ohne Anmeldung ist `email` Pflicht) |
| `GET` | `/api/users/me/reports` | Eigene Meldungen mit Entscheidung und Begründung 🔒 |
| `GET` | `/api/admin/reports?status=OPEN` | Meldungen mit Vorschau des Inhalts (`OPEN`, `REMOVED`, `REJECTED`, `ALL`) 🔒 Admin |
| `PUT` | `/api/admin/reports/{id}` | Entscheiden: `{"decision":"REMOVED"\|"REJECTED","note":"Begründung"}`; REMOVED löscht den Inhalt und erledigt alle offenen Meldungen dazu 🔒 Admin |
| `PUT` | `/api/admin/users/{username}/status` | Account sperren/entsperren `{"enabled":false}`; Tokens werden sofort ungültig 🔒 Admin |

Admins dürfen außerdem fremde Spots bearbeiten und löschen sowie Bewertungen, Kommentare und Fotos löschen.
Noch offen: Benachrichtigung von Meldern und Betroffenen per E-Mail (braucht Mailversand).

### Admins

Neue User haben die Rolle `USER`. Einen Admin ernennt man direkt in der Datenbank:

```sql
UPDATE app_user SET role = 'ADMIN' WHERE username = '<name>';
```
