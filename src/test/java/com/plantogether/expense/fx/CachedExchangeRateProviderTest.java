package com.plantogether.expense.fx;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plantogether.expense.domain.RateSource;
import com.plantogether.expense.fx.ExchangeRateProvider.FxQuote;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CachedExchangeRateProviderTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private FxApiClient fxApiClient;

    private CachedExchangeRateProvider provider;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Instant fixed = Instant.parse("2026-04-28T10:00:00Z");
    private final Clock clock = Clock.fixed(fixed, ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        provider = new CachedExchangeRateProvider(redisTemplate, fxApiClient, clock, objectMapper, 24L);
    }

    private void stubValueOps() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void sameCurrency_returnsOne_withoutCacheOrApi() {
        FxQuote quote = provider.getRate("EUR", "EUR");

        assertThat(quote.rate()).isEqualByComparingTo("1.0000");
        assertThat(quote.source()).isEqualTo(RateSource.LIVE);
        assertThat(quote.fetchedAt()).isEqualTo(fixed);
        verify(fxApiClient, never()).fetch(any(), any());
    }

    @Test
    void cacheHit_returnsCached_withSourceCACHED() throws Exception {
        stubValueOps();
        Instant cachedAt = Instant.parse("2026-04-28T07:00:00Z");
        String json =
                objectMapper.writeValueAsString(Map.of("rate", "1.0823", "fetchedAt", cachedAt.toString()));
        when(valueOps.get("fx:EUR:USD")).thenReturn(json);

        FxQuote quote = provider.getRate("EUR", "USD");

        assertThat(quote.rate()).isEqualByComparingTo("1.0823");
        assertThat(quote.source()).isEqualTo(RateSource.CACHED);
        assertThat(quote.fetchedAt()).isEqualTo(cachedAt);
        verify(fxApiClient, never()).fetch(any(), any());
    }

    @Test
    void cacheMiss_fetchesFromApi_writesBothKeys_returnsLive() {
        stubValueOps();
        when(valueOps.get("fx:EUR:USD")).thenReturn(null);
        when(fxApiClient.fetch("EUR", "USD")).thenReturn(new BigDecimal("1.0823"));

        FxQuote quote = provider.getRate("EUR", "USD");

        assertThat(quote.source()).isEqualTo(RateSource.LIVE);
        assertThat(quote.rate()).isEqualByComparingTo("1.0823");
        assertThat(quote.fetchedAt()).isEqualTo(fixed);
        verify(valueOps).set(eq("fx:EUR:USD"), any(String.class), anyLong(), eq(TimeUnit.SECONDS));
        verify(valueOps).set(eq("fx:last:EUR:USD"), any(String.class));
    }

    @Test
    void apiDown_lastKnownExists_returnsFallback() throws Exception {
        stubValueOps();
        Instant lastFetched = Instant.parse("2026-04-20T10:00:00Z");
        String json =
                objectMapper.writeValueAsString(
                        Map.of("rate", "1.0500", "fetchedAt", lastFetched.toString()));
        when(valueOps.get("fx:EUR:USD")).thenReturn(null);
        when(valueOps.get("fx:last:EUR:USD")).thenReturn(json);
        when(fxApiClient.fetch("EUR", "USD"))
                .thenThrow(new ResourceAccessException("connect timed out"));

        FxQuote quote = provider.getRate("EUR", "USD");

        assertThat(quote.source()).isEqualTo(RateSource.FALLBACK);
        assertThat(quote.rate()).isEqualByComparingTo("1.0500");
        assertThat(quote.fetchedAt()).isEqualTo(lastFetched);
    }

    @Test
    void apiDown_noLastKnown_throwsExchangeRateUnavailable() {
        stubValueOps();
        when(valueOps.get("fx:EUR:USD")).thenReturn(null);
        when(valueOps.get("fx:last:EUR:USD")).thenReturn(null);
        when(fxApiClient.fetch("EUR", "USD")).thenThrow(new ResourceAccessException("offline"));

        assertThatThrownBy(() -> provider.getRate("EUR", "USD"))
                .isInstanceOf(ExchangeRateUnavailableException.class);
    }

    @Test
    void rate_isAlwaysRoundedTo4Decimals() {
        stubValueOps();
        when(valueOps.get("fx:EUR:USD")).thenReturn(null);
        when(fxApiClient.fetch("EUR", "USD")).thenReturn(new BigDecimal("1.0823"));

        FxQuote quote = provider.getRate("EUR", "USD");

        assertThat(quote.rate().scale()).isEqualTo(4);
    }
}
