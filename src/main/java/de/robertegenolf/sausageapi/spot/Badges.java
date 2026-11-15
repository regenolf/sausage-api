package de.robertegenolf.sausageapi.spot;

import java.util.List;
import java.util.function.ToLongFunction;

/**
 * Abzeichen im Wurst-Tagebuch. Sie werden bei jeder Abfrage aus den Zahlen berechnet und nicht gespeichert – so
 * lassen sie sich jederzeit ändern und gehen beim Löschen von Einträgen sauber zurück.
 */
final class Badges {

	/** {@code progress} ist auf {@code goal} begrenzt; {@code earned} = Ziel erreicht. */
	record Badge(String code, String title, String description, long progress, long goal, boolean earned) {
	}

	private record Rule(String code, String title, String description, long goal,
			ToLongFunction<DiaryRepository.Counts> value) {
	}

	private static final List<Rule> RULES = List.of(
			new Rule("FIRST_BITE", "Erster Biss", "Die erste Wurst ins Tagebuch eingetragen", 1,
					DiaryRepository.Counts::checkIns),
			new Rule("REGULAR", "Stammgast", "5-mal an derselben Bude gegessen", 5,
					DiaryRepository.Counts::maxAtOneSpot),
			new Rule("CONNOISSEUR", "Wurstkenner", "An 10 verschiedenen Buden gegessen", 10,
					DiaryRepository.Counts::spots),
			new Rule("WURST_WEISER", "Wurst Weiser", "An 50 verschiedenen Buden gegessen", 50,
					DiaryRepository.Counts::spots),
			new Rule("TRAVELLER", "Wurst-Reisender", "In 5 verschiedenen Orten gegessen", 5,
					DiaryRepository.Counts::cities),
			new Rule("CRITIC", "Kritiker", "10 Buden bewertet", 10, DiaryRepository.Counts::ratings),
			new Rule("PRICE_SCOUT", "Preisdetektiv", "Bei 5 Bewertungen den Preis angegeben", 5,
					DiaryRepository.Counts::ratingsWithPrice),
			new Rule("PHOTOGRAPHER", "Wurstfotograf", "10 Fotos hochgeladen", 10, DiaryRepository.Counts::photos),
			new Rule("PIONEER", "Entdecker", "Eine neue Bude eingetragen", 1, DiaryRepository.Counts::spotsCreated),
			new Rule("CARETAKER", "Kartenpfleger", "3 Hinweise gegeben, die zu einer Korrektur geführt haben", 3,
					DiaryRepository.Counts::issues));

	private Badges() {
	}

	static List<Badge> of(DiaryRepository.Counts counts) {
		return RULES.stream().map(rule -> {
			long value = rule.value().applyAsLong(counts);
			return new Badge(rule.code(), rule.title(), rule.description(), Math.min(value, rule.goal()), rule.goal(),
					value >= rule.goal());
		}).toList();
	}
}
