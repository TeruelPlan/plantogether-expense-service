package com.plantogether.expense.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plantogether.common.grpc.TripClient;
import com.plantogether.common.grpc.TripMember;
import com.plantogether.expense.domain.Expense;
import com.plantogether.expense.domain.ExpenseSplit;
import com.plantogether.expense.dto.BalanceResponse;
import com.plantogether.expense.dto.SettlementTransferDto;
import com.plantogether.expense.repository.ExpenseRepository;
import com.plantogether.expense.service.BalanceCalculator.BalanceResult;
import com.plantogether.expense.service.BalanceCalculator.ConvertedExpense;
import com.plantogether.expense.service.BalanceCalculator.Split;
import com.plantogether.expense.service.BalanceCalculator.Transfer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Computes the trip-wide settlement plan (read-only for story 5.4.1).
 *
 * <p>Identity is the per-trip {@code memberId} (UUID) throughout — the expense domain migrated from
 * device ids to member ids in migrations V3/V4. The balance is cached in Redis under {@code
 * balance:{tripId}} (TTL 5 min) and invalidated explicitly by {@code ExpenseChangedListener} on
 * every expense mutation, so the TTL is only a safety net.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BalanceService {

  private static final String CACHE_KEY_PREFIX = "balance:";
  private static final Duration CACHE_TTL = Duration.ofSeconds(300);
  private static final String DEFAULT_REFERENCE_CURRENCY = "EUR";
  private static final int REF_SCALE = 4;

  private final ExpenseRepository expenseRepository;
  private final BalanceCalculator balanceCalculator;
  private final TripClient tripClient;
  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;

  @Transactional(readOnly = true)
  public BalanceResponse getBalance(UUID tripId, String deviceId) {
    // Throws AccessDeniedException (403) when the caller is not a member.
    tripClient.requireMembership(tripId.toString(), deviceId);

    BalanceResponse cached = readCache(tripId);
    if (cached != null) {
      return cached;
    }

    String referenceCurrency = resolveReferenceCurrency(tripId);
    List<Expense> expenses = expenseRepository.findAllByTripIdAndDeletedAtIsNull(tripId);
    Set<UUID> participants = resolveParticipants(tripId, expenses);

    List<ConvertedExpense> converted = expenses.stream().map(this::toConvertedExpense).toList();
    BalanceResult result = balanceCalculator.compute(converted, participants, referenceCurrency);

    List<SettlementTransferDto> settlements =
        result.transfers().stream().map(BalanceService::toSettlementDto).toList();

    BalanceResponse response =
        BalanceResponse.builder()
            .tripId(tripId)
            .referenceCurrency(referenceCurrency)
            .participantBalances(result.participantBalances())
            .settlements(settlements)
            .computedAt(Instant.now())
            .build();

    writeCache(tripId, response);
    return response;
  }

  /**
   * Each expense already carries {@code amountInReferenceCurrency} (stored at write time by story
   * 5.2, non-null column). Per-split shares are stored in the expense's original currency, so they
   * are converted to the reference currency with the same stored {@code exchangeRate}.
   */
  private ConvertedExpense toConvertedExpense(Expense expense) {
    BigDecimal exchangeRate = expense.getExchangeRate();
    List<Split> splits =
        expense.getSplits().stream()
            .map(split -> new Split(split.getTripMemberId(), toReferenceShare(split, exchangeRate)))
            .toList();
    return new ConvertedExpense(
        expense.getPaidByTripMemberId(), expense.getAmountInReferenceCurrency(), splits);
  }

  private static BigDecimal toReferenceShare(ExpenseSplit split, BigDecimal exchangeRate) {
    return split.getShareAmount().multiply(exchangeRate).setScale(REF_SCALE, RoundingMode.HALF_UP);
  }

  private static SettlementTransferDto toSettlementDto(Transfer transfer) {
    return SettlementTransferDto.builder()
        .fromMemberId(transfer.fromMemberId())
        .toMemberId(transfer.toMemberId())
        .amount(transfer.amount())
        .currency(transfer.currency())
        .build();
  }

  /**
   * Reads the trip reference currency. A read must degrade gracefully: if trip-service is
   * unavailable we fall back to EUR and log a WARN instead of failing the whole settlement view.
   */
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

  /**
   * Seeds the participant set from the trip member list so members with a zero balance still
   * appear. If trip-service cannot return the members, degrade gracefully by deriving participants
   * from the expenses themselves (payers + split members) — transfers stay correct either way.
   */
  private Set<UUID> resolveParticipants(UUID tripId, List<Expense> expenses) {
    try {
      List<TripMember> members = tripClient.getTripMembers(tripId.toString());
      if (!members.isEmpty()) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (TripMember member : members) {
          ids.add(UUID.fromString(member.tripMemberId()));
        }
        return ids;
      }
      log.warn("Trip {} returned no members; deriving participants from expenses", tripId);
    } catch (ResponseStatusException ex) {
      log.warn(
          "Could not resolve members for trip {} ({}); deriving participants from expenses",
          tripId,
          ex.getStatusCode());
    }
    return deriveParticipantsFromExpenses(expenses);
  }

  private Set<UUID> deriveParticipantsFromExpenses(List<Expense> expenses) {
    Set<UUID> ids = new LinkedHashSet<>();
    for (Expense expense : expenses) {
      ids.add(expense.getPaidByTripMemberId());
      for (ExpenseSplit split : expense.getSplits()) {
        ids.add(split.getTripMemberId());
      }
    }
    return ids;
  }

  private BalanceResponse readCache(UUID tripId) {
    String json = redisTemplate.opsForValue().get(cacheKey(tripId));
    if (json == null) {
      return null;
    }
    try {
      return objectMapper.readValue(json, BalanceResponse.class);
    } catch (JsonProcessingException ex) {
      log.warn("Discarding unreadable cached balance for trip {}: {}", tripId, ex.getMessage());
      return null;
    }
  }

  private void writeCache(UUID tripId, BalanceResponse response) {
    try {
      String json = objectMapper.writeValueAsString(response);
      redisTemplate.opsForValue().set(cacheKey(tripId), json, CACHE_TTL);
    } catch (JsonProcessingException ex) {
      log.warn("Could not cache balance for trip {}: {}", tripId, ex.getMessage());
    }
  }

  private static String cacheKey(UUID tripId) {
    return CACHE_KEY_PREFIX + tripId;
  }
}
