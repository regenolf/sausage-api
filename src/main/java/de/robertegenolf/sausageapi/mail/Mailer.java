package de.robertegenolf.sausageapi.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Verschickt Text-Mails im Hintergrund (die Antwortzeit verrät so nicht, ob eine Adresse registriert ist).
 * Ohne SMTP-Konfiguration (SPRING_MAIL_HOST) gibt es keinen JavaMailSender – dann landet die Mail im Log,
 * damit Links in der Entwicklung trotzdem nutzbar sind.
 */
@Component
public class Mailer {

	private static final Logger log = LoggerFactory.getLogger(Mailer.class);

	private final ObjectProvider<JavaMailSender> sender;
	private final String from;
	private final boolean configured;

	Mailer(ObjectProvider<JavaMailSender> sender, @Value("${app.mail.from}") String from,
			@Value("${spring.mail.host:}") String host) {
		this.sender = sender;
		this.from = from;
		// Docker Compose setzt nicht gesetzte Variablen als Leerstring – das zählt wie „nicht konfiguriert“
		this.configured = !host.isBlank();
	}

	public void send(String to, String subject, String text) {
		JavaMailSender mail = configured ? sender.getIfAvailable() : null;
		if (mail == null) {
			log.warn("Kein SMTP konfiguriert (SPRING_MAIL_HOST) – Mail an {} nur protokolliert: {}\n{}", to, subject, text);
			return;
		}
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(from);
		message.setTo(to);
		message.setSubject(subject);
		message.setText(text);
		Thread.ofVirtual().name("mail").start(() -> {
			try {
				mail.send(message);
			}
			catch (MailException ex) {
				log.warn("Mail an {} fehlgeschlagen: {}", to, ex.getMessage());
			}
		});
	}
}
