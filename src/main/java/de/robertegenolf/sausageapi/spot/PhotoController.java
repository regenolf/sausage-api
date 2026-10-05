package de.robertegenolf.sausageapi.spot;

import de.robertegenolf.sausageapi.spot.SpotDtos.SpotPhoto;
import de.robertegenolf.sausageapi.user.UserRepository;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/spots/{spotId}/photos")
class PhotoController {

	static final int MAX_PHOTOS_PER_SPOT = 20;

	private final PhotoRepository photos;
	private final SpotRepository spots;
	private final UserRepository users;

	PhotoController(PhotoRepository photos, SpotRepository spots, UserRepository users) {
		this.photos = photos;
		this.spots = spots;
		this.users = users;
	}

	@GetMapping
	List<SpotPhoto> list(@PathVariable long spotId, Authentication auth) {
		requireSpot(spotId);
		long viewerId = auth == null || auth instanceof AnonymousAuthenticationToken ? -1
				: users.findByUsername(auth.getName()).map(UserRepository.StoredUser::id).orElse(-1L);
		return photos.findBySpot(spotId, viewerId);
	}

	@GetMapping("/{photoId}")
	ResponseEntity<byte[]> image(@PathVariable long spotId, @PathVariable long photoId) {
		PhotoRepository.PhotoData photo = photos.findData(spotId, photoId)
				.orElseThrow(() -> photoNotFound(photoId));
		// Fotos ändern sich nie, werden aber evtl. gelöscht (z. B. nach einer Meldung) - daher nur einen Tag cachen
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(photo.contentType()))
				.cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
				.header("X-Content-Type-Options", "nosniff")
				.body(photo.data());
	}

	@Transactional
	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	ResponseEntity<SpotPhoto> upload(@PathVariable long spotId, @RequestParam("file") MultipartFile file,
			Authentication auth) throws IOException {
		requireSpot(spotId);
		if (file.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Die Datei ist leer");
		}
		byte[] upload = file.getBytes();
		String contentType = detectImageType(upload);
		if (contentType == null) {
			throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
					"Erlaubt sind nur JPEG-, PNG- und WebP-Bilder");
		}
		byte[] data;
		try {
			ImageMetadataStripper.checkDimensions(upload, contentType);
			// GPS-Position und andere Metadaten nicht veröffentlichen
			data = ImageMetadataStripper.strip(upload, contentType);
		}
		catch (ImageMetadataStripper.InvalidImageException ex) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
		}
		if (photos.countBySpot(spotId) >= MAX_PHOTOS_PER_SPOT) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"Pro Spot sind höchstens " + MAX_PHOTOS_PER_SPOT + " Fotos erlaubt");
		}
		long userId = currentUserId(auth);
		long id = photos.create(spotId, userId, contentType, data);
		SpotPhoto created = new SpotPhoto(id, photos.url(spotId, id), auth.getName(), contentType,
				data.length, Instant.now());
		return ResponseEntity.created(URI.create(created.url())).body(created);
	}

	@Transactional
	@DeleteMapping("/{photoId}")
	ResponseEntity<Void> delete(@PathVariable long spotId, @PathVariable long photoId, Authentication auth) {
		long uploaderId = photos.findUploader(spotId, photoId).orElseThrow(() -> photoNotFound(photoId));
		boolean admin = auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
		if (!admin) {
			// Löschen dürfen der Hochladende und der Ersteller des Spots
			long userId = currentUserId(auth);
			long spotOwnerId = spots.findSpotOwner(spotId).orElse(SpotRepository.NO_OWNER);
			if (userId != uploaderId && userId != spotOwnerId) {
				throw new ResponseStatusException(HttpStatus.FORBIDDEN,
						"Nur eigene Fotos oder Fotos am eigenen Spot dürfen gelöscht werden");
			}
		}
		photos.delete(photoId);
		return ResponseEntity.noContent().build();
	}

	/**
	 * Erkennt den Bildtyp anhand der ersten Bytes, statt dem vom Client gesendeten Content-Type zu vertrauen.
	 */
	static String detectImageType(byte[] d) {
		if (d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) {
			return MediaType.IMAGE_JPEG_VALUE;
		}
		if (d.length >= 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G'
				&& d[4] == 0x0D && d[5] == 0x0A && d[6] == 0x1A && d[7] == 0x0A) {
			return MediaType.IMAGE_PNG_VALUE;
		}
		if (d.length >= 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
				&& d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') {
			return "image/webp";
		}
		return null;
	}

	private void requireSpot(long spotId) {
		if (!spots.spotExists(spotId)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Spot " + spotId + " nicht gefunden");
		}
	}

	private long currentUserId(Authentication auth) {
		return users.findByUsername(auth.getName())
				.map(UserRepository.StoredUser::id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
	}

	private static ResponseStatusException photoNotFound(long photoId) {
		return new ResponseStatusException(HttpStatus.NOT_FOUND, "Foto " + photoId + " nicht gefunden");
	}
}
