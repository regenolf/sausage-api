package de.robertegenolf.sausageapi.moderation;

import de.robertegenolf.sausageapi.mail.Mailer;
import de.robertegenolf.sausageapi.moderation.ReportRepository.Reason;
import de.robertegenolf.sausageapi.moderation.ReportRepository.Status;
import de.robertegenolf.sausageapi.moderation.ReportRepository.StoredReport;
import de.robertegenolf.sausageapi.moderation.ReportRepository.TargetType;
import de.robertegenolf.sausageapi.user.UserRepository;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Teilt Entscheidungen mit (DSA Art. 16 Abs. 5 und Art. 17): Melder erfahren, wie entschieden wurde; wessen Inhalt
 * entfernt wurde, bekommt eine Begründung mit Hinweis auf die Möglichkeit zu widersprechen.
 */
@Component
class ModerationNotifier {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY)
			.withZone(ZoneId.of("Europe/Berlin"));

	private final Mailer mailer;
	private final UserRepository users;

	ModerationNotifier(Mailer mailer, UserRepository users) {
		this.mailer = mailer;
		this.users = users;
	}

	void decided(List<StoredReport> reports, Status decision, String note) {
		String reasoning = note == null ? "" : "\nBegründung: " + note + "\n";
		for (StoredReport report : reports) {
			String to = report.notifyEmail();
			if (to == null) {
				continue;
			}
			String outcome = decision == Status.REMOVED
					? "Wir haben den Inhalt geprüft und entfernt."
					: "Wir haben den Inhalt geprüft und keinen Verstoß festgestellt. Er bleibt online.";
			mailer.send(to, "Sausage: Entscheidung zu deiner Meldung", """
					Hallo,

					danke für deine Meldung vom %s (%s, Grund: %s).

					%s
					%s
					Den Stand deiner Meldungen findest du angemeldet unter „Mein Konto → Meine Meldungen“.

					Dein Sausage-Team
					""".formatted(DATE.format(report.createdAt()), typeLabel(report.targetType()),
					reasonLabel(report.reason()), outcome, reasoning));
		}
		if (decision != Status.REMOVED) {
			return;
		}
		// Je betroffene Person eine Mail, auch wenn mehrere Meldungen zum selben Inhalt vorlagen
		Set<Long> authors = new LinkedHashSet<>();
		for (StoredReport report : reports) {
			if (report.targetAuthorId() == null || !authors.add(report.targetAuthorId())) {
				continue;
			}
			users.findEmailById(report.targetAuthorId()).ifPresent(to -> mailer.send(to,
					"Sausage: Ein Inhalt von dir wurde entfernt", """
							Hallo,

							nach einer Meldung haben wir einen Inhalt von dir entfernt.

							Art: %s
							Grund der Meldung: %s
							Inhalt (Auszug): %s
							%s
							Wenn du die Entscheidung für falsch hältst, kannst du innerhalb von sechs Monaten
							widersprechen – antworte einfach auf diese Mail. Die Entscheidung findest du auch
							angemeldet unter „Mein Konto → Meine Meldungen“.

							Dein Sausage-Team
							""".formatted(typeLabel(report.targetType()), reasonLabel(report.reason()),
							excerpt(report.contentSnapshot()), reasoning)));
		}
	}

	private static String excerpt(String text) {
		if (text == null) {
			return "–";
		}
		return text.length() <= 200 ? text : text.substring(0, 200) + " …";
	}

	static String typeLabel(TargetType type) {
		return switch (type) {
			case SPOT -> "Bude";
			case RATING -> "Bewertung";
			case COMMENT -> "Kommentar";
			case PHOTO -> "Foto";
		};
	}

	static String reasonLabel(Reason reason) {
		return switch (reason) {
			case ILLEGAL -> "Rechtswidriger Inhalt";
			case INSULT -> "Beleidigung oder Hassrede";
			case SPAM -> "Spam oder Werbung";
			case PRIVACY -> "Verletzt Privatsphäre";
			case COPYRIGHT -> "Urheberrechtsverletzung";
			case WRONG_INFO -> "Falsche Angaben";
			case OTHER -> "Sonstiges";
		};
	}
}
