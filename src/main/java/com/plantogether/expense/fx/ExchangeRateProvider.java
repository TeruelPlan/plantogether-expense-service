package com.plantogether.expense.fx;

import com.plantogether.expense.domain.RateSource;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Provider of FX rates for expense currency conversion. Implementations are
 * responsible for caching, fallback policy, and 4-decimal rate rounding.
 */
public interface ExchangeRateProvider {

    /**
     * Returns the FX quote for converting {@code baseCurrency} into {@code quoteCurrency}.
     *
     * @throws ExchangeRateUnavailableException when no live, cached, or last-known rate is available
     */
    FxQuote getRate(String baseCurrency, String quoteCurrency);

    /** Immutable FX snapshot returned by {@link #getRate(String, String)}. */
    record FxQuote(BigDecimal rate, RateSource source, Instant fetchedAt) {
    }
}
