package de.robertegenolf.sausageapi.importer;

import de.robertegenolf.sausageapi.importer.GeoJson.FeatureCollection;
import de.robertegenolf.sausageapi.importer.ImportRepository.UnverifiedSpot;
import de.robertegenolf.sausageapi.importer.OsmImporter.ImportResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Datenimporte und Prüfliste (nur Admins, siehe /api/admin/** in der SecurityConfig).
 * <p>
 * Ablehnen = Spot löschen ({@code DELETE /api/spots/{id}}); die externe ID landet dabei automatisch auf der
 * Sperrliste, damit der nächste Import die Bude nicht wieder anlegt.
 */
@RestController
@RequestMapping("/api/admin")
class ImportController {

	private final OsmImporter importer;
	private final ImportRepository repository;

	ImportController(OsmImporter importer, ImportRepository repository) {
		this.importer = importer;
		this.repository = repository;
	}

	/**
	 * Importiert Bratwurstbuden aus OpenStreetMap. Erwartet GeoJSON von
	 * {@code osmium export --add-unique-id=type_id} (siehe tools/osm-import). Mit {@code dryRun=true} wird nur
	 * gezählt, nichts gespeichert.
	 */
	@PostMapping("/imports/osm")
	ImportResult importOsm(@RequestParam(defaultValue = "BRATWURST") String category,
			@RequestParam(defaultValue = "false") boolean dryRun, @RequestBody FeatureCollection body) {
		if (body == null || body.features() == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Erwartet eine GeoJSON-FeatureCollection mit \"features\"");
		}
		long categoryId = repository.findCategoryId(category)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
						"Kategorie " + category + " existiert nicht"));
		return importer.importFeatures(category, categoryId, body.features(), dryRun);
	}

	/** Prüfliste: ungeprüfte Spots, älteste zuerst. Gesamtzahl im Header {@code X-Total-Count}. */
	@GetMapping("/spots/unverified")
	ResponseEntity<List<UnverifiedSpot>> unverified(@RequestParam(required = false) String source,
			@RequestParam(defaultValue = "200") @Min(1) @Max(1000) int limit) {
		String filter = source == null || source.isBlank() ? null : source.trim();
		return ResponseEntity.ok()
				.header("X-Total-Count", String.valueOf(repository.countUnverified(filter)))
				.body(repository.findUnverified(filter, limit));
	}

	/** Bestätigt einen Spot; ein späterer Import überschreibt ihn dann nicht mehr. */
	@PostMapping("/spots/{id}/verify")
	ResponseEntity<Void> verify(@PathVariable long id) {
		if (!repository.verify(id)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Spot " + id + " nicht gefunden");
		}
		return ResponseEntity.noContent().build();
	}
}
