package com.coruja.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

// Esta anotação mágica avisa o Spring para traduzir a exceção em um HTTP 503 para o frontend
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class DetranIndisponivelException extends RuntimeException {
    public DetranIndisponivelException(String message) {
        super(message);
    }
}
