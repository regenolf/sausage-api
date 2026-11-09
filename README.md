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

### Produktion

Live unter **https://www.wurst-weiser.de** auf einem k3s-Server. Server, Cluster und Deployment beschreibt das Repo
[regenolf/kvm-infra](https://github.com/regenolf/kvm-infra) (`cluster/sausage/`: Postgres über CloudNativePG, API,
Web-App, Ingress mit Let's Encrypt, tägliches `pg_dump`). API, Datenbank, Actuator und Swagger sind von außen nicht
erreichbar, nur `https://www.wurst-weiser.de` und `https://www.wurst-weiser.de/api/…` (über die Web-App).

#### Pipeline (GitOps)

```
Push auf main ─ CI grün ─▶ Image nach ghcr.io (Tags main und sha-1a2b3c4)
              ─▶ sha-Tag in kvm-infra/cluster/sausage/kustomization.yaml eintragen ─▶ Argo CD rollt aus (~1 Min.)
```

sausage-app macht dasselbe für die Web-App. Beide Repos deployen unabhängig voneinander.

- **Rollback:** in kvm-infra `cluster/sausage/kustomization.yaml` den `newTag` auf einen älteren `sha-…`-Tag setzen
  und pushen (oder den Commit der Pipeline mit `git revert` zurücknehmen). Die Tags stehen unter *Packages*.
- **Einrichtung (einmalig, in beiden Repos):** Secret `KVM_INFRA_DEPLOY_KEY` (privater Teil eines Deploy Keys mit
  Schreibrecht auf kvm-infra) und Variable `DEPLOY_ENABLED=true` – Details in der README von kvm-infra.
- **Logs, Admin anlegen, Backups:** siehe `cluster/README.md` in kvm-infra.

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
| `POST` | `/api/spots/{id}/photos` | Foto hochladen (multipart, Feld `file`, JPEG/PNG/WebP, max. 10 MB, 40 Megapixel und 20 je Spot; EXIF/GPS-Metadaten und angehängte Daten werden entfernt; Tageskontingent 50 MB je Nutzer, `PHOTO_DAILY_QUOTA_BYTES`, sonst 429; Admins ausgenommen) 🔒 |
| `DELETE` | `/api/spots/{id}/photos/{photoId}` | Foto löschen 🔒 (Hochladender, Ersteller des Spots oder Admin) |
| `GET` | `/api/spots/{id}/comments` | Kommentare (neueste zuerst, mit Benutzername) |
| `POST` | `/api/spots/{id}/comments` | Kommentieren 🔒 |
| `DELETE` | `/api/spots/{id}/comments/{commentId}` | Kommentar löschen 🔒 (Autor oder Admin) |

Listen (`/api/spots`, `/api/spots/nearby`, `/api/users/me/spots`) enthalten `coverPhotoUrl`: das erste Foto als Vorschaubild (ohne Fotos von Nutzern, die man blockiert hat), sonst `null`.

Listen und Details enthalten außerdem `source` (`USER` = in der App angelegt, `OSM` = aus OpenStreetMap importiert) und
`verified` (`false` = importiert und von der Moderation noch nicht geprüft). Details liefern bei importierten Spots
`sourceUrl` (Link auf den Eintrag in der Quelle). Bei `source: OSM` muss die App „© OpenStreetMap-Mitwirkende“ mit Link
auf https://www.openstreetmap.org/copyright anzeigen (Lizenz ODbL).

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

### Datenimport aus OpenStreetMap

Bratwurstbuden aus OpenStreetMap lassen sich wiederholt importieren. `tools/osm-import/osm-import.sh` lädt den
Regionsauszug von [Geofabrik](https://download.geofabrik.de/) (Standard ganz Deutschland, ca. 4,5 GB), filtert ihn mit
`osmium` und schickt die Kandidaten an die API:

```bash
brew install osmium-tool jq                                  # bzw. apt install osmium-tool jq
API_URL=https://www.wurst-weiser.de tools/osm-import/osm-import.sh           # Probelauf, speichert nichts
API_URL=https://www.wurst-weiser.de tools/osm-import/osm-import.sh --apply   # importieren
REGION=europe/germany/thueringen API_URL=… tools/osm-import/osm-import.sh  # nur ein Bundesland
PBF=~/Downloads/germany-latest.osm.pbf API_URL=… tools/osm-import/osm-import.sh   # vorhandene Datei statt Download
```

Für ganz Deutschland braucht der Lauf je nach Rechner 10–20 Minuten, rund 10 GB freien Platz im `WORKDIR` und
einige GB RAM für `osmium`. Der Download wird wiederverwendet (nur neu geladen, wenn Geofabrik eine neuere Datei hat).

Als Bude gilt ein Imbiss, Restaurant, Biergarten oder Food-Court mit `cuisine=sausage|bratwurst|currywurst|hot_dog`,
passendem `food:*`-Tag oder „Wurst“, „Würstchen“ bzw. „Rostbrat…“ im Namen (`OsmFeatures.java`). Regeln beim Import:

- neue Buden werden **ungeprüft** angelegt (`verified: false`, ohne Ersteller); nur Admins können sie ändern,
- ungeprüfte Buden übernehmen bei jedem Import den aktuellen Stand aus OSM,
- **geprüfte** Buden (bestätigt oder von einem Admin bearbeitet) überschreibt der Import nicht mehr,
- **gelöschte** importierte Buden kommen nicht wieder (Sperrliste `spot_import_rejection`, per Trigger gefüllt),
- in OSM verschwundene Buden werden nur gezählt (`stale`), nicht gelöscht,
- **fehlende Adressen** werden ergänzt: Buden sind in OSM oft nur ein Punkt mit Namen. Das Skript schneidet deshalb die
  Adressen rund um die Kandidaten aus und schickt je Bude die nächste Adresse mit Straße (bis 80 m) und die nächste mit
  Ort (bis 600 m) mit, nur zum Abgleich, sie werden nicht gespeichert. So bleibt die Importdatei auch für ganz Deutschland
  bei wenigen MB (Grenze: 12 MB durch nginx).
  Ohne Straße übernimmt die Bude die nächste Adresse im Umkreis von 50 m, sonst wenigstens Ort und PLZ der nächsten
  Adresse bis 500 m (`OsmAddresses.java`). Vorhandene Werte aus OSM werden nie überschrieben, geprüfte Buden bleiben
  wie sie sind.

| Methode | Pfad | Beschreibung |
|---|---|---|
| `POST` | `/api/admin/imports/osm?category=BRATWURST&dryRun=false` | GeoJSON aus `osmium export --add-unique-id=type_id` importieren; Antwort mit Zählern (`created`, `updated`, `unchanged`, `keptVerified`, `skippedRejected`, `stale`, `addressPoints`, `addressesCompleted`, `citiesCompleted`, …) und den Namen neuer Buden (`newSpots`) 🔒 Admin |
| `GET` | `/api/admin/spots/unverified?source=OSM&limit=200` | Prüfliste: ungeprüfte Spots, älteste zuerst, mit `sourceUrl`; Gesamtzahl im Header `X-Total-Count` 🔒 Admin |
| `POST` | `/api/admin/spots/{id}/verify` | Spot bestätigen 🔒 Admin |

Ablehnen = Spot löschen (`DELETE /api/spots/{id}`). Bearbeiten (`PUT /api/spots/{id}`) gilt ebenfalls als Prüfung.

### Admins

Neue User haben die Rolle `USER`. Einen Admin ernennt man direkt in der Datenbank:

```sql
UPDATE app_user SET role = 'ADMIN' WHERE username = '<name>';
```
