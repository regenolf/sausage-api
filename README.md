# sausage-api
Springboot Applikation Backend

## Lokal starten

```bash
docker compose up -d   # Postgres auf localhost:5432, Keycloak (Anmeldung) auf localhost:8180
./mvnw spring-boot:run # Liquibase legt das Schema beim Start an
```

Konten legt man über die Registrierung von Keycloak an (aus der App heraus oder unter
http://localhost:8180/realms/sausage/account), Admin-Konsole: http://localhost:8180/admin (`admin`/`admin`).
Damit Konto löschen und „überall abmelden“ auch Keycloak erreichen, `KEYCLOAK_ADMIN_CLIENT_SECRET` aus
`docker-compose.yml` setzen; ohne Secret ändert die API nur ihre eigene Datenbank.

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
| `OIDC_ISSUER_URI` | `http://localhost:8180/realms/sausage` – Keycloak-Realm, dessen Tokens die API akzeptiert (Produktion `https://auth.robertegenolf.de/realms/sausage`) |
| `OIDC_JWK_SET_URI` | leer → `<OIDC_ISSUER_URI>/protocol/openid-connect/certs`; im Cluster die interne Adresse |
| `OIDC_AUDIENCE` | `sausage-api` – muss im `aud` des Tokens stehen |
| `KEYCLOAK_ADMIN_URL` / `KEYCLOAK_REALM` | `http://localhost:8180` / `sausage` – Admin-API für Konto löschen und „überall abmelden“ |
| `KEYCLOAK_ADMIN_CLIENT_ID` / `KEYCLOAK_ADMIN_CLIENT_SECRET` | `sausage-api-admin` / leer (ohne Secret wird Keycloak nicht angefasst) |
| `TERMS_VERSION` | `2026-10-05` – bei jeder Änderung der Nutzungsbedingungen erhöhen |
| `PUBLIC_URL` | leer (aus der Anfrage); öffentliche Adresse für absolute Foto-URLs, im Produktions-Setup `https://DOMAIN` |
| `API_DOCS_ENABLED` | `true` (Swagger UI und `/v3/api-docs`; im Produktions-Setup `false`) |
| `app.rate-limit.writes-per-hour` | `200` Schreibzugriffe (Anlegen/Ändern/Löschen) pro User und Stunde |
| `app.rate-limit.reports-per-hour` | `30` Meldungen pro IP und Stunde |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:8100,http://localhost:4200,capacitor://localhost,https://localhost,http://localhost:3000,http://localhost:5173` |

API-Doku (Swagger UI): http://localhost:8080/swagger-ui.html

Hinter einem Reverse-Proxy im privaten Netz (z. B. dem nginx der sausage-app) übernimmt die API die echte
Client-IP aus `X-Forwarded-For` (`server.forward-headers-strategy=native`), damit die Begrenzungen pro Nutzer greifen.

Health-Checks: `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`.
Die GitHub-Actions-CI (`.github/workflows/ci.yml`) führt `./mvnw verify` aus.

## API

Lesende `GET`-Endpunkte sind öffentlich, alles andere braucht ein Access Token von **Keycloak**
(`Authorization: Bearer …`). Registrierung, Anmeldung, „Passwort vergessen“, Passwort ändern und der Schutz vor
Passwort-Raten liegen bei Keycloak (Realm `sausage`, Konfiguration in kvm-infra `cluster/keycloak/`). Die App holt
Tokens per Authorization Code + PKCE (Client `sausage-app`), Skripte per Geräte-Code (Client `sausage-cli`).

Die API prüft Signatur, Ablauf, Aussteller (`OIDC_ISSUER_URI`) und Empfänger (`aud` = `sausage-api`). Beim ersten
Request mit einem neuen Keycloak-Konto legt sie den Account der App an (Benutzername = Keycloak-Benutzername, bei
Konflikt mit angehängter Zahl; reservierte Namen werden zu „nutzer“). Alte Accounts aus der Zeit vor Keycloak werden
nur übernommen, wenn die E-Mail in Keycloak **bestätigt** ist. Rolle und Sperre kommen bei jeder Anfrage aus der
Datenbank und wirken sofort; gesperrte Accounts dürfen nur noch ihre Daten exportieren und das Konto löschen.

```bash
# Token für Tests von der Kommandozeile (Geräte-Code, Anmeldung im Browser)
curl -s -d client_id=sausage-cli -d scope=openid http://localhost:8180/realms/sausage/protocol/openid-connect/auth/device
```

| Methode | Pfad | Beschreibung |
|---|---|---|
| `GET` | `/api/users/me` | Eigenes Profil inkl. akzeptierter (`termsVersion`) und aktueller Version der Nutzungsbedingungen (`currentTermsVersion`); legt beim ersten Aufruf den Account an 🔒 |
| `POST` | `/api/users/me/terms` | Aktuelle Nutzungsbedingungen akzeptieren (nach dem ersten Login und nach Änderungen) 🔒 |
| `POST` | `/api/users/me/logout-all` | Auf allen Geräten abmelden (beendet alle Keycloak-Sitzungen) 🔒 |
| `GET` | `/api/users/me/spots` | Eigene Spots, neueste zuerst 🔒 |
| `GET` | `/api/users/me/blocks` | Von mir blockierte Nutzer 🔒 |
| `POST` | `/api/users/me/blocks` | Nutzer blockieren `{"username":…}`: seine Bewertungen, Kommentare und Fotos werden für mich ausgeblendet 🔒 |
| `DELETE` | `/api/users/me/blocks/{username}` | Blockierung aufheben 🔒 |
| `GET` | `/api/users/me/export` | Alle eigenen Daten als JSON-Datei (Auskunft/Datenübertragbarkeit, Art. 15/20 DSGVO), auch für gesperrte Konten 🔒 |
| `DELETE` | `/api/users/me` | Konto löschen, in Keycloak und in der App: Bewertungen, Kommentare, Fotos werden gelöscht, eigene Spots anonymisiert; auch für gesperrte Konten 🔒 |
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

### E-Mails

Moderations-Mails verschickt die API per SMTP (`SPRING_MAIL_HOST`, `_PORT`, `_USERNAME`, `_PASSWORD`, Absender
`MAIL_FROM`); ohne `SPRING_MAIL_HOST` werden sie nur ins Log geschrieben. Mails zu Konto und Passwort verschickt Keycloak.

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
| `PUT` | `/api/admin/users/{username}/status` | Account sperren/entsperren `{"enabled":false}`; wirkt sofort, auch für bereits ausgestellte Tokens 🔒 Admin |

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

Vor dem Hochladen fragt das Skript nach der Anmeldung: Es zeigt einen Link mit Geräte-Code, im Browser meldet man
sich als Admin der App an und stimmt zu (Keycloak-Client `sausage-cli`). Alternativ ein fertiges Token in
`ACCESS_TOKEN` übergeben.

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
