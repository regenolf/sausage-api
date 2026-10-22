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
→ Postgres, dazu ein tägliches `pg_dump` nach `backups/`. API, Datenbank, Actuator und Swagger sind von außen
nicht erreichbar, nur `https://DOMAIN` und `https://DOMAIN/api/…`.

Live unter **https://www.wurst-weiser.de** (KVM-Server bei Hostinger, Domain bei Alfahosting).

#### Pipeline

```
Push auf main ─ CI grün ─▶ Image nach ghcr.io (Tags main und sha-1a2b3c4) ─▶ per SSH auf den Server:
                            docker compose pull api && up -d --wait api ─▶ Prüfung https://DOMAIN/api/categories
```

sausage-app macht dasselbe für den Dienst `app`. Beide Repos deployen unabhängig voneinander; auf dem Server liegen nur
`docker-compose.prod.yml`, `Caddyfile` (überträgt die Pipeline bei jedem Deployment) und `.env`.

**Rollback:** auf dem Server in `.env` z. B. `API_TAG=sha-1a2b3c4` setzen und
`docker compose -f docker-compose.prod.yml up -d api`. Die Tags stehen unter *Packages* im GitHub-Repo.

#### Einrichtung (einmalig)

1. **DNS bei Alfahosting:** A-Records für `www` und `@` (die Domain ohne www) auf die IPv4 des Servers, bei IPv6
   zusätzlich AAAA-Records. MX-Einträge (E-Mail) bleiben unverändert. Prüfen: `dig +short www.wurst-weiser.de`.
2. **Server (Ubuntu 24.04)** als root:

   ```bash
   curl -fsSL https://get.docker.com | sh
   adduser --disabled-password --gecos "" deploy && usermod -aG docker deploy
   install -d -o deploy -g deploy /opt/sausage
   ufw allow OpenSSH && ufw allow 80/tcp && ufw allow 443 && ufw enable
   ```

   Danach den **öffentlichen** Pipeline-Schlüssel in `/home/deploy/.ssh/authorized_keys` eintragen und in
   `/etc/ssh/sshd_config` `PasswordAuthentication no` sowie `PermitRootLogin prohibit-password` setzen
   (vorher den eigenen Schlüssel hinterlegen!). In der Hostinger-Firewall ebenfalls nur 22, 80 und 443 freigeben.
3. **Schlüssel für die Pipeline** (lokal): `ssh-keygen -t ed25519 -f sausage-deploy -N "" -C github-actions`.
4. **`.env` auf dem Server** (`/opt/sausage/.env`, nur dort, nie in Git) nach `deploy/.env.example` anlegen:
   `DOMAIN=www.wurst-weiser.de`, `REDIRECT_DOMAINS=wurst-weiser.de`, `DB_PASSWORD` und `JWT_SECRET` jeweils mit
   `openssl rand -base64 48` erzeugen. Rechte: `chmod 600 .env`.
5. **GitHub, in beiden Repos** unter *Settings → Environments → production* die Secrets anlegen:

   | Secret | Wert |
   |---|---|
   | `SSH_HOST` | IP des Servers |
   | `SSH_USER` | `deploy` |
   | `SSH_PRIVATE_KEY` | Inhalt von `sausage-deploy` (privater Schlüssel) |
   | `SSH_KNOWN_HOSTS` | Ausgabe von `ssh-keyscan <IP>` |

   Optional unter *Required reviewers* sich selbst eintragen: Dann wartet jedes Deployment auf eine Freigabe.
   Unter *Settings → Secrets and variables → Actions → Variables* `DEPLOY_ENABLED=true` setzen – erst dann laufen
   die Deploy-Jobs. Unter *Settings → Branches* für `main` „Require status checks to pass“ aktivieren.
6. **Erster Start:** Sobald beide Pipelines je einmal gelaufen sind (Images liegen in ghcr.io), auf dem Server einmal
   alles starten – danach erledigt die Pipeline Updates:

   ```bash
   cd /opt/sausage
   echo <GitHub-Token mit read:packages> | docker login ghcr.io -u regenolf --password-stdin
   docker compose -f docker-compose.prod.yml up -d && docker logout ghcr.io
   ```

   Alternativ die Packages unter *Package settings* auf „Public“ stellen (der Code bleibt privat).
7. **Ersten Admin anlegen:** registrieren und dann
   `docker compose -f docker-compose.prod.yml exec db psql -U sausage -c "UPDATE app_user SET role='ADMIN' WHERE username='<name>'"`.

**Backups** liegen in `/opt/sausage/backups` – zusätzlich außerhalb des Servers sichern (sie enthalten auch alle Fotos),
z. B. per `rclone` oder den Snapshots von Hostinger. Wiederherstellen:

```bash
docker compose -f docker-compose.prod.yml exec -T db pg_restore -U sausage -d sausage --clean < backups/sausage-JJJJ-MM-TT_HHMM.dump
```

Ohne Registry direkt aus den Quellen (sausage-app neben sausage-api ausgecheckt):
`docker compose -f docker-compose.prod.yml -f docker-compose.build.yml up -d --build`.

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

Listen (`/api/spots`, `/api/spots/nearby`, `/api/users/me/spots`) enthalten `coverPhotoUrl`: das erste Foto als Vorschaubild (ohne Fotos von Nutzern, die man blockiert hat), sonst `null`.
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
| `POST` | `/api/spots/{id}/photos` | Foto hochladen (multipart, Feld `file`, JPEG/PNG/WebP, max. 10 MB, 40 Megapixel und 20 je Spot; EXIF/GPS-Metadaten und angehängte Daten werden entfernt; Tageskontingent 50 MB je Nutzer, `PHOTO_DAILY_QUOTA_BYTES`, sonst 429; Admins ausgenommen) 🔒 |
| `DELETE` | `/api/spots/{id}/photos/{photoId}` | Foto löschen 🔒 (Hochladender, Ersteller des Spots oder Admin) |
| `GET` | `/api/spots/{id}/comments` | Kommentare (neueste zuerst, mit Benutzername) |
| `POST` | `/api/spots/{id}/comments` | Kommentieren 🔒 |
| `DELETE` | `/api/spots/{id}/comments/{commentId}` | Kommentar löschen 🔒 (Autor oder Admin) |

Fehler kommen als [Problem Details](https://www.rfc-editor.org/rfc/rfc9457) (`application/problem+json`) mit
deutscher Meldung in `detail`; bei Validierungsfehlern stehen die betroffenen Felder in `errors`.

### Passwort vergessen

| Methode | Pfad | Beschreibung |
|---|---|---|
| `POST` | `/api/auth/password-reset` | `{"email":"…"}` → immer 204; ist die Adresse registriert, kommt ein Link `…/reset-password?token=…` (60 Minuten gültig, einmal verwendbar, gespeichert nur als SHA-256-Hash). Limit pro IP und pro Adresse |
| `POST` | `/api/auth/password-reset/confirm` | `{"token":"…","newPassword":"…"}` → 204, alle bisherigen Anmeldungen werden ungültig; 400 bei ungültigem/abgelaufenem Link |

E-Mails gehen per SMTP raus (`SPRING_MAIL_HOST`, `_PORT`, `_USERNAME`, `_PASSWORD`, Absender `MAIL_FROM`); ohne
`SPRING_MAIL_HOST` werden sie nur ins Log geschrieben. Links zeigen auf `FRONTEND_URL` bzw. `PUBLIC_URL`.

### Datenschutz ohne Anmeldung (gesperrte Konten)

| Methode | Pfad | Beschreibung |
|---|---|---|
| `POST` | `/api/auth/data/export` | `{"login":"…","password":"…"}` → alle eigenen Daten als JSON-Datei, auch für gesperrte Konten (Art. 15/20 DSGVO); gedrosselt wie der Login |
| `POST` | `/api/auth/data/delete` | `{"login":"…","password":"…"}` → 204, Konto gelöscht (Art. 17 DSGVO) |

### Suchmaschinen und Link-Vorschauen

| Methode | Pfad | Beschreibung |
|---|---|---|
| `GET` | `/api/seo/spots/{id}` | HTML mit Titel, Beschreibung, Open-Graph-Bild (erstes Foto) und schema.org-`FoodEstablishment` (Adresse, Geo, Bewertung). nginx im Frontend leitet Crawler und Vorschau-Dienste (WhatsApp, Telegram, Facebook, Google …) von `/spots/{id}` hierher |
| `GET` | `/api/seo/sitemap.xml` | Sitemap aller Spots, im Frontend unter `/sitemap.xml` |

Absolute Adressen kommen aus `PUBLIC_URL` oder aus `X-Forwarded-Proto`/`Host`.

### Melden und Moderation (Art. 16 DSA)

| Methode | Pfad | Beschreibung |
|---|---|---|
| `POST` | `/api/reports` | Inhalt melden (`targetType` SPOT/RATING/COMMENT/PHOTO, `targetId`, `reason` ILLEGAL/INSULT/SPAM/PRIVACY/COPYRIGHT/WRONG_INFO/OTHER, `message`; ohne Anmeldung ist `email` Pflicht) |
| `GET` | `/api/users/me/reports` | Eigene Meldungen mit Entscheidung und Begründung 🔒 |
| `GET` | `/api/admin/reports?status=OPEN` | Meldungen mit Vorschau des Inhalts (`OPEN`, `REMOVED`, `REJECTED`, `ALL`) 🔒 Admin |
| `PUT` | `/api/admin/reports/{id}` | Entscheiden: `{"decision":"REMOVED"\|"REJECTED","note":"Begründung"}`; REMOVED löscht den Inhalt und erledigt alle offenen Meldungen dazu 🔒 Admin |
| `GET` | `/api/admin/users?status=DISABLED&q=…` | Accounts suchen (`ENABLED`, `DISABLED`, `ALL`; `q` in Benutzername und E-Mail, max. 100 Treffer), z. B. gesperrte zum Entsperren 🔒 Admin |
| `PUT` | `/api/admin/users/{username}/status` | Account sperren/entsperren `{"enabled":false}`; Tokens werden sofort ungültig 🔒 Admin |

Admins dürfen außerdem fremde Spots bearbeiten und löschen sowie Bewertungen, Kommentare und Fotos löschen.
| `GET` | `/api/users/me/moderation-decisions` | Entfernte eigene Inhalte mit Grund, Begründung und gesichertem Auszug (DSA Art. 17) 🔒 |

Beim Melden werden Verfasser und Inhalt gesichert (`content_snapshot`), damit Entscheidung und Begründung auch nach dem
Entfernen nachvollziehbar bleiben; die Admin-Ansicht zeigt dann den gesicherten Stand (`contentDeleted: true`).
Nach einer Entscheidung bekommen Melder eine Mail mit dem Ergebnis, bei REMOVED auch die Verfasser (mit Begründung und
Hinweis auf Widerspruch per Antwort-Mail). Im Datenexport stehen die eigenen gemeldeten Inhalte unter
`meldungen_zu_eigenen_inhalten`.

### Admins

Neue User haben die Rolle `USER`. Einen Admin ernennt man direkt in der Datenbank:

```sql
UPDATE app_user SET role = 'ADMIN' WHERE username = '<name>';
```
