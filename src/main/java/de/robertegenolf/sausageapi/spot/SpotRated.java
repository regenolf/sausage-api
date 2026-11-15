package de.robertegenolf.sausageapi.spot;

/**
 * Eine Bude wurde neu bewertet (nicht beim Ändern einer bestehenden Bewertung). {@code ownerId}: Ersteller der
 * Bude, null bei importierten Buden oder gelöschten Konten.
 */
public record SpotRated(long spotId, String spotName, Long ownerId, long raterId, String rater, int score) {
}
