package de.robertegenolf.sausageapi.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Liefert Fehler einheitlich als RFC-9457 Problem Details (application/problem+json),
 * inklusive der deutschen Fehlermeldungen und der fehlerhaften Felder bei Validierungsfehlern.
 */
@RestControllerAdvice
class ApiExceptionHandler {

	@ExceptionHandler(ResponseStatusException.class)
	ProblemDetail handleStatus(ResponseStatusException ex) {
		ProblemDetail problem = ProblemDetail.forStatus(ex.getStatusCode());
		if (ex.getReason() != null) {
			problem.setDetail(ex.getReason());
		}
		return problem;
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ProblemDetail handleInvalidBody(MethodArgumentNotValidException ex) {
		Map<String, String> errors = new LinkedHashMap<>();
		ex.getBindingResult().getFieldErrors()
				.forEach(e -> errors.putIfAbsent(e.getField(), e.getDefaultMessage()));
		return validationProblem(errors);
	}

	@ExceptionHandler(HandlerMethodValidationException.class)
	ProblemDetail handleInvalidParameters(HandlerMethodValidationException ex) {
		Map<String, String> errors = new LinkedHashMap<>();
		ex.getParameterValidationResults().forEach(result -> result.getResolvableErrors()
				.forEach(e -> errors.putIfAbsent(result.getMethodParameter().getParameterName(),
						e.getDefaultMessage())));
		return validationProblem(errors);
	}

	private static ProblemDetail validationProblem(Map<String, String> errors) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Ungültige Eingabe");
		problem.setProperty("errors", errors);
		return problem;
	}
}
