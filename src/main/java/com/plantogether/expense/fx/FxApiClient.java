package com.plantogether.expense.fx;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;

/**
 * Thin RestClient wrapper for the Frankfurter FX API (https://api.frankfurter.app — keyless,
 * ECB-backed).
 *
 * <p>Endpoint: {@code GET /latest?from={BASE}&to={QUOTE}}. Response: {@code
 * {"amount":1,"base":"EUR","date":"...","rates":{"USD":1.08}}}.
 */
@Slf4j
@Component
public class FxApiClient {

    private final RestClient restClient;

    public FxApiClient(@Value("${fx.provider.url:https://api.frankfurter.app}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(1).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(2).toMillis());
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /**
     * Fetches the live FX rate for {base -> quote}, rounded to 4 decimals (HALF_UP).
     *
     * @throws ExchangeRateUnavailableException on 4xx (unknown currency) — the cache layer translates
     *                                          provider 5xx / network errors into a fallback path.
     * @throws RestClientException              on transport failure / timeout / 5xx (caller handles fallback)
     */
    public BigDecimal fetch(String base, String quote) {
        JsonNode body =
                restClient
                        .get()
                        .uri(
                                uriBuilder ->
                                        uriBuilder
                                                .path("/latest")
                        .queryParam("from", base)
                                                .queryParam("to", quote)
                                                .build())
                        .retrieve()
                        .onStatus(
                                HttpStatusCode::is4xxClientError,
                                (req, resp) -> {
                                    throw new ExchangeRateUnavailableException(base, quote);
                                })
                        .body(JsonNode.class);

        if (body == null || !body.has("rates") || !body.get("rates").has(quote)) {
            throw new ExchangeRateUnavailableException(base, quote);
        }
        BigDecimal rate = body.get("rates").get(quote).decimalValue();
        return rate.setScale(4, RoundingMode.HALF_UP);
    }
}
