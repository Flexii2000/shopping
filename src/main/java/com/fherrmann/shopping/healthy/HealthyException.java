package com.fherrmann.shopping.healthy;

/** Der Kalorienzaehler war nicht erreichbar oder hat nicht verwertbar geantwortet. */
public class HealthyException extends RuntimeException {

    public HealthyException(String message) {
        super(message);
    }

    public HealthyException(String message, Throwable cause) {
        super(message, cause);
    }
}
