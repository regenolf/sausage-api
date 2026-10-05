package de.robertegenolf.sausageapi.moderation;

import de.robertegenolf.sausageapi.moderation.ReportRepository.Reason;
import de.robertegenolf.sausageapi.moderation.ReportRepository.Status;
import de.robertegenolf.sausageapi.moderation.ReportRepository.StoredReport;
import de.robertegenolf.sausageapi.moderation.ReportRepository.Target;
import de.robertegenolf.sausageapi.moderation.ReportRepository.TargetType;
import de.robertegenolf.sausageapi.security.LoginThrottle;
import de.robertegenolf.sausageapi.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * Melde- und Abhilfeverfahren (Art. 16 DSA): Jede Person kann Inhalte melden, Admins entscheiden und
 * begründen ihre Entscheidung. Melder sehen den Stand ihrer Meldungen unter /api/users/me/reports.
 */
@RestController
@RequestMapping("/api")
class ModerationController {

	public record ReportRequest(
			@NotNull TargetType targetType,
			@NotNull Long targetId,
			@NotNull Reason reason,
			@Size(max = 2000) String message,
			/** Pflicht, wenn nicht angemeldet (Kontaktmöglichkeit für Rückfragen und die Entscheidung). */
			@Email @Size(max = 255) String email) {
	}

	public record ReportReceipt(long id, Status status, Instant createdAt) {
	}

	/** Meldung samt aktuellem Inhalt; {@code author}/{@code contentPreview} sind null, wenn der Inhalt gelöscht ist. */
	public record ReportView(long id, TargetType targetType, long targetId, Reason reason, String message,
			String reporter, String reporterEmail, Status status, String decisionNote, String decidedBy,
			Instant decidedAt, Instant createdAt, Long spotId, String author, String contentPreview) {
	}

	/** Eigene Meldung aus Sicht des Melders (ohne Angaben zu anderen Personen). */
	public record MyReport(long id, TargetType targetType, long targetId, Reason reason, Status status,
			String decisionNote, Instant decidedAt, Instant createdAt) {
	}

	public record DecisionRequest(@NotNull Status decision, @Size(max = 2000) String note) {
	}

	public record UserStatusRequest(@NotNull Boolean enabled) {
	}

	private final ReportRepository reports;
	private final UserRepository users;
	private final LoginThrottle throttle;

	ModerationController(ReportRepository reports, UserRepository users, LoginThrottle throttle) {
		this.reports = reports;
		this.users = users;
		this.throttle = throttle;
	}

	@PostMapping("/reports")
	ResponseEntity<ReportReceipt> report(@Valid @RequestBody ReportRequest request, Authentication auth,
			HttpServletRequest http) {
		Long reporterId = isAnonymous(auth) ? null
				: users.findByUsername(auth.getName()).map(UserRepository.StoredUser::id).orElse(null);
		String email = request.email() == null || request.email().isBlank() ? null : request.email().trim();
		if (reporterId == null && email == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Bitte eine E-Mail-Adresse angeben oder anmelden, damit wir dich über die Entscheidung informieren können");
		}
		throttle.checkReport(http.getRemoteAddr());
		if (reports.findTarget(request.targetType(), request.targetId()).isEmpty()) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Der gemeldete Inhalt existiert nicht (mehr)");
		}
		long id = reports.create(request.targetType(), request.targetId(), request.reason(),
				blankToNull(request.message()), reporterId, email);
		StoredReport stored = reports.find(id).orElseThrow();
		return ResponseEntity.created(URI.create("/api/reports/" + id))
				.body(new ReportReceipt(id, stored.status(), stored.createdAt()));
	}

	@GetMapping("/users/me/reports")
	List<MyReport> myReports(Authentication auth) {
		return reports.findByReporter(currentUserId(auth)).stream()
				.map(r -> new MyReport(r.id(), r.targetType(), r.targetId(), r.reason(), r.status(), r.decisionNote(),
						r.decidedAt(), r.createdAt()))
				.toList();
	}

	/** Meldungen für Admins, standardmäßig nur offene (älteste zuerst). */
	@GetMapping("/admin/reports")
	List<ReportView> adminReports(@RequestParam(defaultValue = "OPEN") String status) {
		Status filter = "ALL".equalsIgnoreCase(status) ? null : parseStatus(status);
		return reports.findByStatus(filter).stream().map(this::view).toList();
	}

	@Transactional
	@PutMapping("/admin/reports/{id}")
	ReportView decide(@PathVariable long id, @Valid @RequestBody DecisionRequest request, Authentication auth) {
		StoredReport report = reports.find(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Meldung " + id + " nicht gefunden"));
		if (report.status() != Status.OPEN) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Meldung " + id + " ist bereits entschieden");
		}
		long adminId = currentUserId(auth);
		String note = blankToNull(request.note());
		switch (request.decision()) {
			case REMOVED -> {
				reports.deleteTarget(report.targetType(), report.targetId());
				reports.decideAllOpen(report.targetType(), report.targetId(), Status.REMOVED, note, adminId);
			}
			case REJECTED -> reports.decide(id, Status.REJECTED, note, adminId);
			case OPEN -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Entscheidung muss REMOVED oder REJECTED sein");
		}
		return view(reports.find(id).orElseThrow());
	}

	/** Sperrt oder entsperrt einen Account; gesperrte Accounts können sich nicht anmelden, Tokens werden ungültig. */
	@PutMapping("/admin/users/{username}/status")
	ResponseEntity<Void> setUserStatus(@PathVariable String username, @Valid @RequestBody UserStatusRequest request,
			Authentication auth) {
		if (username.equals(auth.getName())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Den eigenen Account kann man nicht sperren");
		}
		if (!users.setEnabled(username, request.enabled())) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Benutzer " + username + " nicht gefunden");
		}
		return ResponseEntity.noContent().build();
	}

	private ReportView view(StoredReport r) {
		Target target = reports.findTarget(r.targetType(), r.targetId()).orElse(null);
		return new ReportView(r.id(), r.targetType(), r.targetId(), r.reason(), r.message(), r.reporter(),
				r.reporterEmail(), r.status(), r.decisionNote(), r.decidedBy(), r.decidedAt(), r.createdAt(),
				target == null ? null : target.spotId(), target == null ? null : target.author(),
				target == null ? null : target.preview());
	}

	private long currentUserId(Authentication auth) {
		return users.findByUsername(auth.getName())
				.map(UserRepository.StoredUser::id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
	}

	private static boolean isAnonymous(Authentication auth) {
		return auth == null || auth instanceof AnonymousAuthenticationToken || !auth.isAuthenticated();
	}

	private static Status parseStatus(String status) {
		try {
			return Status.valueOf(status.toUpperCase());
		}
		catch (IllegalArgumentException ex) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unbekannter Status: " + status);
		}
	}

	private static String blankToNull(String text) {
		return text == null || text.isBlank() ? null : text.trim();
	}
}
