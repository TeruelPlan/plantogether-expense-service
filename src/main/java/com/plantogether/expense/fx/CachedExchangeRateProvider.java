package com.plantogether.expense.fx;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plantogether.expense.domain.RateSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataAccessException;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * FX provider that reads from Redis first and falls back through:
 * <ol>
 *   <li>same-currency identity ({@link RateSource#LIVE})</li>
 *   <li>fresh TTL-bounded cache key {@code fx:{BASE}:{QUOTE}} ({@link RateSource#CACHED})</li>
 *   <li>live FX provider (writes both keys, returns {@link RateSource#LIVE})</li>
 *   <li>unbounded mirror {@code fx:last:{BASE}:{QUOTE}} ({@link RateSource#FALLBACK})</li>
 *   <li>{@link ExchangeRateUnavailableException} (HTTP 503)</li>
 * </ol>
 */
@Slf4j
@Component
public class CachedExchangeRateProvider implements ExchangeRateProvider {

    private static final String KEY_PREFIX = "fx:";
    private static final String LAST_PREFIX = "fx:last:";

    private final StringRedisTemplate redisTemplate;
    private final FxApiClient fxApiClient;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final Duration cacheTtl;

    public CachedExchangeRateProvider(
            StringRedisTemplate redisTemplate,
            FxApiClient fxApiClient,
            Clock clock,
            ObjectMapper objectMapper,
            @Value("${fx.provider.cache-ttl-hours:24}") long cacheTtlHours) {
        this.redisTemplate = redisTemplate;
        this.fxApiClient = fxApiClient;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.cacheTtl = Duration.ofHours(cacheTtlHours);
    }

    @Override
    public FxQuote getRate(String baseCurrency, String quoteCurrency) {
        if (baseCurrency.equals(quoteCurrency)) {
            return new FxQuote(BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP),
                    RateSource.LIVE,
                    clock.instant());
        }

        String key = KEY_PREFIX + baseCurrency + ":" + quoteCurrency;
        String lastKey = LAST_PREFIX + baseCurrency + ":" + quoteCurrency;

        // 1. Fresh cache hit.
        FxPayload cached = readPayload(key);
        if (cached != null) {
            return new FxQuote(scale4(cached.rate()), RateSource.CACHED, cached.fetchedAt());
        }

        // 2. Live provider call.
        try {
            BigDecimal rate = fxApiClient.fetch(baseCurrency, quoteCurrency);
            Instant now = clock.instant();
            FxPayload payload = new FxPayload(rate, now);
            writePayload(key, payload, cacheTtl);
            writePayload(lastKey, payload, null);
            return new FxQuote(scale4(rate), RateSource.LIVE, now);
        } catch (ExchangeRateUnavailableException ex) {
            // 4xx from provider — currency pair unknown, no point falling back.
            throw ex;
        } catch (RestClientException ex) {
            log.warn("FX provider unavailable for {}->{}: {}", baseCurrency, quoteCurrency, ex.getMessage());
            // 3. Last-known mirror fallback.
            FxPayload last = readPayload(lastKey);
            if (last != null) {
                return new FxQuote(scale4(last.rate()), RateSource.FALLBACK, last.fetchedAt());
            }
            throw new ExchangeRateUnavailableException(baseCurrency, quoteCurrency, ex);
        }
    }

    private FxPayload readPayload(String key) {
        String raw;
        try {
            raw = redisTemplate.opsForValue().get(key);
        } catch (DataAccessException ex) {
            log.warn("Redis unavailable reading FX key {}: {}", key, ex.getMessage());
            return null;
        }
        if (raw == null) {
            return null;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(raw, Map.class);
            BigDecimal rate = new BigDecimal(map.get("rate").toString());
            Instant fetchedAt = Instant.parse(map.get("fetchedAt").toString());
            return new FxPayload(rate, fetchedAt);
        } catch (Exception ex) {
            log.warn("Discarding malformed FX cache entry at {}: {}", key, ex.getMessage());
            return null;
        }
    }

    private void writePayload(String key, FxPayload payload, Duration ttl) {
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "rate", payload.rate().toPlainString(),
                    "fetchedAt", payload.fetchedAt().toString()));
            if (ttl != null) {
                redisTemplate.opsForValue().set(key, json, ttl.toSeconds(), TimeUnit.SECONDS);
            } else {
                redisTemplate.opsForValue().set(key, json);
            }
        } catch (Exception ex) {
            log.warn("Failed to write FX cache entry at {}: {}", key, ex.getMessage());
        }
    }

    private static BigDecimal scale4(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private record FxPayload(BigDecimal rate, Instant fetchedAt) {
    }
}
