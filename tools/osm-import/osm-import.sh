#!/usr/bin/env bash
# Importiert Bratwurstbuden aus OpenStreetMap in die Sausage-API.
#
# Ablauf: Regionsauszug von Geofabrik laden -> mit osmium auf Imbisse/Restaurants filtern -> als GeoJSON exportieren
# -> mit jq grob vorfiltern (hält die Datei klein) -> an POST /api/admin/imports/osm schicken. Welche Orte
# wirklich als Bude gelten, entscheidet die API (OsmFeatures.java); der jq-Filter darf nur großzügiger sein.
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
		-h|--help) sed -n '2,25p' "$0"; exit 0 ;;
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

echo "==> Lade $REGION von Geofabrik (nur wenn neuer als die lokale Kopie)"
url="https://download.geofabrik.de/$REGION-latest.osm.pbf"
if [[ -f "$pbf" ]]; then
	curl -fL --retry 3 -o "$pbf" -z "$pbf" "$url"
else
	curl -fL --retry 3 -o "$pbf" "$url"
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
	-H 'Content-Type: application/json' --data-binary "@$candidates" \
	"$API_URL/api/admin/imports/osm?category=$CATEGORY&dryRun=$DRY_RUN")"

echo "$response" | jq '{dryRun, category, received, matched, created, updated, unchanged, keptVerified,
	skippedRejected, skippedNoMatch, skippedNoName, skippedInvalid, skippedDuplicate, stale}'
echo "==> Neue Buden (max. 200):"
echo "$response" | jq -r '.newSpots[] | "  - " + .'

if $DRY_RUN; then
	echo
	echo "Passt das? Dann mit --apply wirklich importieren."
fi
