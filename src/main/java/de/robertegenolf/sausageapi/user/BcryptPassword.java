package de.robertegenolf.sausageapi.user;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

/**
 * BCrypt verarbeitet höchstens 72 Byte; längere Passwörter lehnt der Encoder mit einer Exception ab (sonst HTTP 500).
 * Gezählt wird in UTF-8, Umlaute zählen also doppelt. {@code null} prüft {@code @NotBlank}.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = BcryptPassword.Validator.class)
public @interface BcryptPassword {

	int MAX_BYTES = 72;

	String message() default "darf höchstens 72 Byte lang sein (Umlaute und Sonderzeichen zählen doppelt)";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	class Validator implements ConstraintValidator<BcryptPassword, String> {

		@Override
		public boolean isValid(String value, ConstraintValidatorContext context) {
			return value == null || value.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
		}
	}
}
