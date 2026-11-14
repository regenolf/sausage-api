package de.robertegenolf.sausageapi.push;

import de.robertegenolf.sausageapi.spot.SpotRated;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Löst Benachrichtigungen aus – erst nach dem Commit und im Hintergrund, damit ein langsamer Push-Dienst keine
 * Anfrage aufhält. Bisher: „Deine Bude wurde bewertet“ an den Ersteller (nicht bei eigenen Bewertungen).
 */
@Component
class PushNotifier {

	private final PushDeviceRepository devices;
	private final PushSender sender;

	PushNotifier(PushDeviceRepository devices, PushSender sender) {
		this.devices = devices;
		this.sender = sender;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void spotRated(SpotRated event) {
		if (event.ownerId() == null || event.ownerId() == event.raterId()) {
			return;
		}
		String stars = event.score() == 1 ? "1 Stern" : event.score() + " Sterne";
		notifyUser(event.ownerId(), "Neue Bewertung für " + event.spotName(),
				event.rater() + " hat " + stars + " vergeben.", Map.of("route", "/spots/" + event.spotId()));
	}

	void notifyUser(long userId, String title, String body, Map<String, String> data) {
		var tokens = devices.tokens(userId);
		if (tokens.isEmpty()) {
			return;
		}
		Thread.ofVirtual().name("push").start(() -> {
			for (String token : tokens) {
				if (sender.send(token, title, body, data) == PushSender.Result.INVALID_TOKEN) {
					devices.removeToken(token);
				}
			}
		});
	}
}
