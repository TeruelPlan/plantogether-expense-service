package com.plantogether.expense.fx;

/**
 * Thrown when the FX provider is unreachable AND no cached or last-known rate exists for the
 * requested currency pair. Maps to HTTP 503 in the global handler.
 */
public class ExchangeRateUnavailableException extends RuntimeException {

  private final String base;
  private final String quote;

  public ExchangeRateUnavailableException(String base, String quote) {
    super("Exchange rate unavailable for " + base + " -> " + quote);
    this.base = base;
    this.quote = quote;
  }

  public ExchangeRateUnavailableException(String base, String quote, Throwable cause) {
    super("Exchange rate unavailable for " + base + " -> " + quote, cause);
    this.base = base;
    this.quote = quote;
  }

  public String base() {
    return base;
  }

  public String quote() {
    return quote;
  }

  public String pair() {
    return base + " -> " + quote;
  }
}
