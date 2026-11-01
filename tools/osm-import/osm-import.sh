#!/usr/bin/env bash
# Importiert Bratwurstbuden aus OpenStreetMap in die Sausage-API.
#
# Ablauf: Regionsauszug von Geofabrik laden -> mit osmium auf Imbisse/Restaurants filtern -> als GeoJSON exportieren
# -> mit jq grob vorfiltern (hält die Datei klein) -> an POST /api/admin/imports/osm schicken. Welche Orte
# wirklich als Bude gelten, entscheidet die API (OsmFeatures.java); der jq-Filter darf nur großzügiger sein.
#
# Viele Buden haben in OSM keine eigene Adresse (nur ein Punkt mit Namen). Deshalb gehen zusätzlich die
# Adresspunkte und Gebäudeadressen in der Nähe der Kandidaten mit; die API übernimmt für Buden ohne Straße die
# nächste Adresse im Umkreis von 50 m, sonst wenigstens den Ort (OsmAddresses.java).
#
# Voraussetzungen: osmium-tool, jq, curl  (macOS: brew install osmium-tool jq · Ubuntu: apt install osmium-tool jq)
#
# Aufruf:
#   ./osm-import.sh                     # Probelauf (zählt nur, speichert nichts)
#   ./osm-import.sh --apply             # wirklich importieren
#
# Umgebungsvariablen:
#   API_URL         Basis-URL der API (Standard http://localhost:8080; Produktion https://www.wurst-weiser.de)
#   ADMIN_USER      Benutzername eines Admins (wird sonst abgefragt)
#   ADMIN_PASSWORD  Passwort (wird sonst verdeckt abgefragt)
#   REGION          Geofabrik-Pfad (Standard europe/germany/thueringen; ganz Deutschland: europe/germany)
#   CATEGORY        Kategorie-Code der Spots (Standard BRATWURST)
#   WORKDIR         Arbeitsverzeichnis für Downloads (Standard ~/.cache/sausage-osm-import; wird wiederverwendet)
#   PBF             eigene .osm.pbf-Datei statt des Geofabrik-Downloads (optional)
#
# Daten: © OpenStreetMap-Mitwirkende, Lizenz ODbL (https://www.openstreetmap.org/copyright).
# Die App muss diese Quellenangabe bei importierten Spots anzeigen.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
API_URL="${API_URL:-http://localhost:8080}"
REGION="${REGION:-europe/germany/thueringen}"
CATEGORY="${CATEGORY:-BRATWURST}"
WORKDIR="${WORKDIR:-$HOME/.cache/sausage-osm-import}"
DRY_RUN=true

for arg in "$@"; do
	case "$arg" in
		--apply) DRY_RUN=false ;;
		--dry-run) DRY_RUN=true ;;
		-h|--help) sed -n '2,30p' "$0"; exit 0 ;;
		*) echo "Unbekannte Option: $arg (erlaubt: --apply, --dry-run)" >&2; exit 2 ;;
	esac
done

for tool in osmium jq curl; do
	command -v "$tool" >/dev/null || { echo "$tool fehlt (macOS: brew install osmium-tool jq, Ubuntu: apt install osmium-tool jq)" >&2; exit 1; }
done

mkdir -p "$WORKDIR"
name="$(basename "$REGION")"
pbf="$WORKDIR/$name-latest.osm.pbf"
food="$WORKDIR/$name-food.osm.pbf"
geojson="$WORKDIR/$name-food.geojson"
candidates="$WORKDIR/$name-candidates.geojson"
addresses_pbf="$WORKDIR/$name-addresses.osm.pbf"
addresses_seq="$WORKDIR/$name-addresses.geojsonseq"
cells="$WORKDIR/$name-cells.json"
nearby="$WORKDIR/$name-addresses-nearby.json"
payload="$WORKDIR/$name-import.geojson"

if [[ -n "${PBF:-}" ]]; then
	pbf="$PBF"
	echo "==> Verwende $pbf"
else
	echo "==> Lade $REGION von Geofabrik (nur wenn neuer als die lokale Kopie)"
	url="https://download.geofabrik.de/$REGION-latest.osm.pbf"
	if [[ -f "$pbf" ]]; then
		curl -fL --retry 3 -o "$pbf" -z "$pbf" "$url"
	else
		curl -fL --retry 3 -o "$pbf" "$url"
	fi
fi

echo "==> Filtere Imbisse, Restaurants, Biergärten und Food-Courts"
osmium tags-filter "$pbf" nwr/amenity=fast_food,restaurant,biergarten,food_court -o "$food" --overwrite
osmium export "$food" -c "$SCRIPT_DIR/osmium-export.json" --add-unique-id=type_id \
	--geometry-types=point,polygon -o "$geojson" --overwrite

# Großzügige Vorauswahl: Wurst im Namen, passende cuisine oder food:*-Tags. Die genaue Prüfung macht die API.
jq -c '
	def sausage: test("sausage|bratwurst|currywurst|hot_dog"; "i");
	.features |= map(select(
		((.properties.name // "") | test("w[uü]rst|rostbr[aä]t"; "i"))
		or ((.properties.cuisine // "") | sausage)
		or (.properties | to_entries | any(.key | startswith("food:") and sausage))
	))' "$geojson" > "$candidates"

total="$(jq '.features | length' "$geojson")"
count="$(jq '.features | length' "$candidates")"
size="$(du -h "$candidates" | cut -f1)"
echo "==> $total Gastro-Orte in OSM, davon $count Kandidaten ($size)"

echo "==> Suche Adressen in der Nähe der Kandidaten"
osmium tags-filter "$pbf" nwr/addr:housenumber -o "$addresses_pbf" --overwrite
osmium export "$addresses_pbf" -c "$SCRIPT_DIR/osmium-export-addresses.json" --add-unique-id=type_id \
	--geometry-types=point,polygon -f geojsonseq --format-option print_record_separator=false \
	-o "$addresses_seq" --overwrite

# Position: Punkt bzw. Mittel der Eckpunkte des äußeren Rings (wie OsmFeatures.position).
# Rasterzellen von 0,01° (ca. 1,1 km x 0,7 km) mit den Positionen der Kandidaten
jq -c '
	def ring: if (.[0][0] | type) == "number" then . else (.[0] | ring) end;
	def pos: if .geometry.type == "Point" then .geometry.coordinates
		else (.geometry.coordinates | ring) as $r | [($r | map(.[0]) | add / length), ($r | map(.[1]) | add / length)] end;
	reduce (.features[] | pos) as $p ({};
		.["\(($p[1] * 100) | floor):\(($p[0] * 100) | floor)"] += [[$p[1], $p[0]]])' "$candidates" > "$cells"

# Zeilenweise (wenig Speicher): nur Adressen bis 600 m von einem Kandidaten. Bis 80 m alle (für Straße und
# Hausnummer), weiter weg reicht eine Adresse je Ort, PLZ und Rasterfeld von 0,002° (für den Ort).
jq -c --slurpfile cells "$cells" '
	def ring: if (.[0][0] | type) == "number" then . else (.[0] | ring) end;
	def pos: if .geometry.type == "Point" then .geometry.coordinates
		else (.geometry.coordinates | ring) as $r | [($r | map(.[0]) | add / length), ($r | map(.[1]) | add / length)] end;
	. as $feature | pos as $p | $p[1] as $lat | $p[0] as $lon
	| (($lat * 100) | floor) as $r | (($lon * 100) | floor) as $c
	| [range(-1; 2) as $dr | range(-1; 2) as $dc | ($cells[0]["\($r + $dr):\($c + $dc)"] // [])[]]
	| map(((($lon - .[1]) * ((($lat + .[0]) / 2 * 0.017453293) | cos)) as $x | ($lat - .[0]) as $y
		| (($x * $x + $y * $y) | sqrt) * 111320)) | min as $d
	| select($d != null and $d <= 600)
	| $feature | {type, id, geometry: {type: "Point", coordinates: $p}, properties: (.properties + {_near: ($d <= 80)})}' \
	"$addresses_seq" \
	| jq -cs '(map(select(.properties._near)) + (map(select(.properties._near | not))
		| unique_by([.properties["addr:city"] // .properties["addr:place"], .properties["addr:postcode"],
			(.geometry.coordinates | map(. * 500 | floor))])))
		| map(del(.properties._near))' > "$nearby"

jq -c --slurpfile nearby "$nearby" '.features += $nearby[0]' "$candidates" > "$payload"
addresses="$(jq 'length' "$nearby")"
size="$(du -h "$payload" | cut -f1)"
echo "==> $addresses Adressen in der Nähe, Importdatei $size"
if (( $(wc -c < "$payload") > 11 * 1024 * 1024 )); then
	echo "Achtung: nginx im Frontend nimmt höchstens 12 MB an (client_max_body_size). Bei Fehler 413 die Region"
	echo "verkleinern oder direkt gegen die API importieren (API_URL auf den Backend-Port)."
fi

if [[ -z "${ADMIN_USER:-}" ]]; then
	read -rp "Admin-Benutzer für $API_URL: " ADMIN_USER
fi
if [[ -z "${ADMIN_PASSWORD:-}" ]]; then
	read -rsp "Passwort: " ADMIN_PASSWORD
	echo
fi

if $DRY_RUN; then
	echo "==> Probelauf gegen $API_URL (es wird nichts gespeichert)"
else
	echo "==> Importiere nach $API_URL"
fi

# Zugangsdaten über stdin statt Kommandozeile, damit sie nicht in der Prozessliste stehen
response="$(printf 'user = "%s:%s"\n' "$ADMIN_USER" "$ADMIN_PASSWORD" | curl -fsS -K - \
	-H 'Content-Type: application/json' --data-binary "@$payload" \
	"$API_URL/api/admin/imports/osm?category=$CATEGORY&dryRun=$DRY_RUN")"

echo "$response" | jq '{dryRun, category, received, matched, created, updated, unchanged, keptVerified,
	skippedRejected, skippedNoMatch, skippedNoName, skippedInvalid, skippedDuplicate, stale, addressPoints,
	addressesCompleted, citiesCompleted}'
echo "==> Neue Buden (max. 200):"
echo "$response" | jq -r '.newSpots[] | "  - " + .'

if $DRY_RUN; then
	echo
	echo "Passt das? Dann mit --apply wirklich importieren."
fi
