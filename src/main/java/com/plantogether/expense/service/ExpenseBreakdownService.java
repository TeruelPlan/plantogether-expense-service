package com.plantogether.expense.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plantogether.common.exception.AccessDeniedException;
import com.plantogether.common.grpc.TripClient;
import com.plantogether.expense.dto.BreakdownResponse;
import com.plantogether.expense.dto.CategoryBreakdownEntry;
import com.plantogether.expense.repository.ExpenseRepository;
import com.plantogether.expense.repository.ExpenseRepository.CategoryTotalProjection;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Computes the per-category spend breakdown (story 5.5).
 *
 * <p>Reads the per-expense {@code amountInReferenceCurrency} stored at write time (story 5.2), so
 * the breakdown is deterministic and never re-converts at read time. Percentages are reconciled
 * with the largest-remainder method so they always sum to exactly 100.00. Cached in Redis under
 * {@code breakdown:{tripId}} (TTL 10 min), invalidated by {@code ExpenseChangedListener} on every
 * expense mutation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpenseBreakdownService {

  private static final String CACHE_KEY_PREFIX = "breakdown:";
  private static final Duration CACHE_TTL = Duration.ofSeconds(600);
  private static final String DEFAULT_REFERENCE_CURRENCY = "EUR";
  private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
  private static final BigDecimal CENT = new BigDecimal("0.01");

  private final ExpenseRepository expenseRepository;
  private final TripClient tripClient;
  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;

  @Transactional(readOnly = true)
  public BreakdownResponse getBreakdown(UUID tripId, String deviceId) {
    if (!tripClient.isMember(tripId.toString(), deviceId)) {
      throw new AccessDeniedException("Not a member of this trip");
    }

    BreakdownResponse cached = readCache(tripId);
    if (cached != null) {
      return cached;
    }

    String referenceCurrency = resolveReferenceCurrency(tripId);
    List<CategoryTotalProjection> projections = expenseRepository.aggregateByCategory(tripId);

    BreakdownResponse response =
        projections.isEmpty()
            ? emptyBreakdown(tripId, referenceCurrency)
            : buildBreakdown(tripId, referenceCurrency, projections);

    writeCache(tripId, response);
    return response;
  }

  private BreakdownResponse buildBreakdown(
      UUID tripId, String referenceCurrency, List<CategoryTotalProjection> projections) {

    BigDecimal total =
        projections.stream()
            .map(CategoryTotalProjection::getTotal)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(2, RoundingMode.HALF_UP);

    // Sort by total DESC, then category name ASC as a stable tiebreaker.
    List<CategoryTotalProjection> sorted = new ArrayList<>(projections);
    sorted.sort(
        Comparator.comparing(CategoryTotalProjection::getTotal)
            .reversed()
            .thenComparing(p -> p.getCategory().name()));

    List<BigDecimal> rawPercentages =
        sorted.stream().map(p -> percentage(p.getTotal(), total)).toList();
    List<BigDecimal> reconciled = reconcilePercentages(rawPercentages);

    List<CategoryBreakdownEntry> categories = new ArrayList<>();
    for (int i = 0; i < sorted.size(); i++) {
      CategoryTotalProjection p = sorted.get(i);
      categories.add(
          CategoryBreakdownEntry.builder()
              .category(p.getCategory())
              .totalAmount(p.getTotal().setScale(2, RoundingMode.HALF_UP))
              .percentage(reconciled.get(i))
              .expenseCount((int) p.getExpenseCount())
              .build());
    }

    return BreakdownResponse.builder()
        .tripId(tripId)
        .referenceCurrency(referenceCurrency)
        .totalAmount(total)
        .categories(categories)
        .computedAt(Instant.now())
        .build();
  }

  private BreakdownResponse emptyBreakdown(UUID tripId, String referenceCurrency) {
    return BreakdownResponse.builder()
        .tripId(tripId)
        .referenceCurrency(referenceCurrency)
        .totalAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
        .categories(List.of())
        .computedAt(Instant.now())
        .build();
  }

  private static BigDecimal percentage(BigDecimal value, BigDecimal total) {
    if (total.signum() == 0) {
      return BigDecimal.ZERO;
    }
    return value.multiply(ONE_HUNDRED).divide(total, 6, RoundingMode.HALF_UP);
  }

  /**
   * Largest-remainder (Hamilton) reconciliation: floors every percentage to 2 decimals, then
   * distributes the leftover hundredths to the entries with the largest fractional remainder so the
   * total is exactly 100.00. Input order is preserved in the output.
   */
  static List<BigDecimal> reconcilePercentages(List<BigDecimal> raw) {
    if (raw.isEmpty()) {
      return List.of();
    }
    List<BigDecimal> floors = new ArrayList<>(raw.size());
    BigDecimal floorSum = BigDecimal.ZERO;
    for (BigDecimal value : raw) {
      BigDecimal floor = value.setScale(2, RoundingMode.DOWN);
      floors.add(floor);
      floorSum = floorSum.add(floor);
    }

    int shortfallCents =
        ONE_HUNDRED.subtract(floorSum).divide(CENT, 0, RoundingMode.HALF_UP).intValue();
    if (shortfallCents <= 0) {
      return floors;
    }

    // Indices sorted by descending remainder (raw - floor), stable by index.
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < raw.size(); i++) {
      order.add(i);
    }
    order.sort(
        (a, b) -> {
          BigDecimal remA = raw.get(a).subtract(floors.get(a));
          BigDecimal remB = raw.get(b).subtract(floors.get(b));
          int cmp = remB.compareTo(remA);
          return cmp != 0 ? cmp : Integer.compare(a, b);
        });

    for (int k = 0; k < shortfallCents && k < order.size(); k++) {
      int idx = order.get(k);
      floors.set(idx, floors.get(idx).add(CENT));
    }
    return floors;
  }

  private String resolveReferenceCurrency(UUID tripId) {
    try {
      String currency = tripClient.getTripCurrency(tripId.toString());
      if (currency == null || currency.isBlank()) {
        log.warn("Trip {} returned a blank reference currency; falling back to EUR", tripId);
        return DEFAULT_REFERENCE_CURRENCY;
      }
      return currency;
    } catch (ResponseStatusException ex) {
      log.warn(
          "Could not resolve reference currency for trip {} ({}); falling back to EUR",
          tripId,
          ex.getStatusCode());
      return DEFAULT_REFERENCE_CURRENCY;
    }
  }

  private BreakdownResponse readCache(UUID tripId) {
    String json = redisTemplate.opsForValue().get(cacheKey(tripId));
    if (json == null) {
      return null;
    }
    try {
      return objectMapper.readValue(json, BreakdownResponse.class);
    } catch (JsonProcessingException ex) {
      log.warn("Discarding unreadable cached breakdown for trip {}: {}", tripId, ex.getMessage());
      return null;
    }
  }

  private void writeCache(UUID tripId, BreakdownResponse response) {
    try {
      String json = objectMapper.writeValueAsString(response);
      redisTemplate.opsForValue().set(cacheKey(tripId), json, CACHE_TTL);
    } catch (JsonProcessingException ex) {
      log.warn("Could not cache breakdown for trip {}: {}", tripId, ex.getMessage());
    }
  }

  private static String cacheKey(UUID tripId) {
    return CACHE_KEY_PREFIX + tripId;
  }
}
