package de.robertegenolf.sausageapi.importer;

import de.robertegenolf.sausageapi.importer.GeoJson.Feature;
import de.robertegenolf.sausageapi.importer.ImportRepository.ExistingSpot;
import de.robertegenolf.sausageapi.importer.OsmFeatures.Candidate;
import de.robertegenolf.sausageapi.importer.OsmFeatures.Result;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gleicht OpenStreetMap-Daten (GeoJSON aus {@code osmium export}) mit den Spots ab:
 * <ul>
 * <li>neue Buden werden ungeprüft angelegt (ohne Ersteller, Quelle OSM),</li>
 * <li>ungeprüfte Buden übernehmen Änderungen aus OSM,</li>
 * <li>geprüfte (oder von Admins bearbeitete) Buden bleiben unverändert,</li>
 * <li>gelöschte Buden kommen nicht wieder (Sperrliste spot_import_rejection),</li>
 * <li>Buden, die in OSM verschwunden sind, werden nur gezählt ({@code stale}), nicht gelöscht.</li>
 * </ul>
 * Features, die keine Bude, aber eine Adresse sind (Hausnummer mit Straße oder Ort), dienen nur dazu, fehlende
 * Adressen der Buden zu ergänzen ({@link OsmAddresses}); sie werden nicht gespeichert.
 * Der Import lässt sich beliebig oft wiederholen.
 */
@Service
class OsmImporter {

	static final String SOURCE = "OSM";

	/** Höchstzahl der Namen neuer Buden in der Antwort (zum Gegenlesen beim Probelauf). */
	static final int MAX_NEW_NAMES = 200;

	/**
	 * Ergebnis eines Imports. {@code received} = Features in der Datei, {@code matched} = als Bratwurstbude erkannt
	 * (ohne doppelte IDs); diese verteilen sich auf created/updated/unchanged/keptVerified/skippedRejected.
	 * {@code addressPoints} = mitgeschickte Adressen (nicht in skippedNoMatch), {@code addressesCompleted} /
	 * {@code citiesCompleted} = Buden, deren Adresse bzw. Ort daraus ergänzt wurde.
	 */
	public record ImportResult(boolean dryRun, String category, int received, int matched, int created, int updated,
			int unchanged, int keptVerified, int skippedRejected, int skippedNoMatch, int skippedNoName,
			int skippedInvalid, int skippedDuplicate, int stale, int addressPoints, int addressesCompleted,
			int citiesCompleted, List<String> newSpots) {
	}

	private final ImportRepository repository;

	OsmImporter(ImportRepository repository) {
		this.repository = repository;
	}

	@Transactional
	ImportResult importFeatures(String categoryCode, long categoryId, List<Feature> features, boolean dryRun) {
		Timestamp now = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS));
		Map<String, ExistingSpot> existing = repository.findImported(SOURCE);
		Set<String> rejected = repository.findRejected(SOURCE);
		Set<String> seen = new HashSet<>();
		List<Long> touched = new ArrayList<>();
		List<String> newSpots = new ArrayList<>();
		int matched = 0, created = 0, updated = 0, unchanged = 0, keptVerified = 0, skippedRejected = 0;
		int skippedNoMatch = 0, skippedNoName = 0, skippedInvalid = 0, skippedDuplicate = 0;
		int addressesCompleted = 0, citiesCompleted = 0;

		// 1. Durchlauf: Buden erkennen, Adresspunkte sammeln
		OsmAddresses addresses = new OsmAddresses();
		List<Candidate> candidates = new ArrayList<>();
		for (Feature feature : features) {
			Result result = OsmFeatures.classify(feature);
			switch (result.outcome()) {
				case NO_MATCH -> {
					OsmAddresses.Address address = OsmAddresses.parse(feature);
					if (address != null) {
						addresses.add(address);
					}
					else {
						skippedNoMatch++;
					}
				}
				case NO_NAME -> skippedNoName++;
				case INVALID -> skippedInvalid++;
				case MATCH -> candidates.add(result.candidate());
			}
		}

		// 2. Durchlauf: fehlende Adressen ergänzen und mit dem Bestand abgleichen
		for (Candidate found : candidates) {
			if (!seen.add(found.externalId())) {
				skippedDuplicate++;
				continue;
			}
			matched++;
			OsmAddresses.Completed completed = addresses.complete(found);
			Candidate candidate = completed.candidate();
			ExistingSpot spot = existing.get(candidate.externalId());
			if (spot == null && rejected.contains(candidate.externalId())) {
				skippedRejected++;
				continue;
			}
			if (spot == null || !spot.verified()) {
				switch (completed.completion()) {
					case ADDRESS -> addressesCompleted++;
					case CITY -> citiesCompleted++;
					case NONE -> {
					}
				}
			}
			if (spot == null) {
				created++;
				if (newSpots.size() < MAX_NEW_NAMES) {
					newSpots.add(candidate.city() == null ? candidate.name()
							: candidate.name() + " (" + candidate.city() + ")");
				}
				if (!dryRun) {
					repository.insert(SOURCE, categoryId, candidate, now);
				}
			}
			else if (spot.verified()) {
				keptVerified++;
				touched.add(spot.id());
			}
			else if (spot.sameAs(candidate)) {
				unchanged++;
				touched.add(spot.id());
			}
			else {
				updated++;
				if (!dryRun) {
					repository.update(spot.id(), candidate, now);
				}
			}
		}
		if (!dryRun) {
			repository.touch(touched, now);
		}
		int stale = (int) existing.entrySet().stream()
				.filter(e -> e.getValue().categoryId() == categoryId && !e.getValue().verified()
						&& !seen.contains(e.getKey()))
				.count();
		return new ImportResult(dryRun, categoryCode, features.size(), matched, created, updated, unchanged,
				keptVerified, skippedRejected, skippedNoMatch, skippedNoName, skippedInvalid, skippedDuplicate, stale,
				addresses.size(), addressesCompleted, citiesCompleted, List.copyOf(newSpots));
	}
}
