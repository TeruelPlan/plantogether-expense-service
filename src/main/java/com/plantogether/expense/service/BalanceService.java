package com.plantogether.expense.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plantogether.common.exception.AccessDeniedException;
import com.plantogether.common.grpc.Role;
import com.plantogether.common.grpc.TripClient;
import com.plantogether.common.grpc.TripMember;
import com.plantogether.common.grpc.TripMembership;
import com.plantogether.expense.domain.Expense;
import com.plantogether.expense.domain.ExpenseSplit;
import com.plantogether.expense.domain.SettlementTransfer;
import com.plantogether.expense.dto.BalanceResponse;
import com.plantogether.expense.dto.MarkTransferDoneRequest;
import com.plantogether.expense.dto.SettlementTransferDto;
import com.plantogether.expense.dto.SettlementTransferResponse;
import com.plantogether.expense.repository.ExpenseRepository;
import com.plantogether.expense.repository.SettlementTransferRepository;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Computes the trip-wide settlement plan and persists "mark as done" transfers.
 *
 * <p>Identity is the per-trip {@code memberId} (UUID) throughout — the expense domain migrated from
 * device ids to member ids in migrations V3/V4. The balance is cached in Redis under {@code
 * balance:{tripId}} (TTL 5 min) and invalidated on every expense mutation (by {@code
 * ExpenseChangedListener}) and on every {@code markTransferDone}.
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
  private final SettlementTransferRepository settlementTransferRepository;
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

    BalanceResponse response = buildBalanceResponse(tripId);
    writeCache(tripId, response);
    return response;
  }

  @Transactional
  public SettlementTransferResponse markTransferDone(
      UUID tripId, String callerDeviceId, MarkTransferDoneRequest req) {
    TripMembership membership = tripClient.requireMembership(tripId.toString(), callerDeviceId);
    UUID callerMemberId = parseMemberId(membership);

    boolean involved =
        callerMemberId.equals(req.getFromMemberId()) || callerMemberId.equals(req.getToMemberId());
    if (!involved && membership.role() != Role.ORGANIZER) {
      throw new AccessDeniedException(
          "Only the involved members or the trip organizer can mark this transfer done");
    }

    // Recompute from source (never trust a possibly-stale cache) and confirm the requested transfer
    // is part of the current plan.
    SourceBalance source = computeFromSource(tripId);
    boolean matchesPlan = source.transfers().stream().anyMatch(t -> matches(t, req));
    if (!matchesPlan) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Transfer does not match current settlement plan");
    }

    Optional<SettlementTransfer> existing =
        settlementTransferRepository.findByTripIdAndFromMemberIdAndToMemberIdAndAmountAndCurrency(
            tripId, req.getFromMemberId(), req.getToMemberId(), req.getAmount(), req.getCurrency());

    SettlementTransfer persisted =
        existing.orElseGet(
            () ->
                settlementTransferRepository.save(
                    SettlementTransfer.builder()
                        .tripId(tripId)
                        .fromMemberId(req.getFromMemberId())
                        .toMemberId(req.getToMemberId())
                        .amount(req.getAmount())
                        .currency(req.getCurrency())
                        .settledByMemberId(callerMemberId)
                        .build()));

    evictCache(tripId);
    return SettlementTransferResponse.from(persisted);
  }

  // ---------------------------------------------------------------------------
  // Computation
  // ---------------------------------------------------------------------------

  /** Computes the raw plan from expenses only (no persisted DONE overlay, no cache). */
  private SourceBalance computeFromSource(UUID tripId) {
    String referenceCurrency = resolveReferenceCurrency(tripId);
    List<Expense> expenses = expenseRepository.findAllByTripIdAndDeletedAtIsNull(tripId);
    Set<UUID> participants = resolveParticipants(tripId, expenses);

    List<ConvertedExpense> converted = expenses.stream().map(this::toConvertedExpense).toList();
    BalanceResult result = balanceCalculator.compute(converted, participants, referenceCurrency);
    return new SourceBalance(referenceCurrency, result.transfers(), result.participantBalances());
  }

  /** Builds the API response, overlaying persisted DONE rows onto the computed plan. */
  private BalanceResponse buildBalanceResponse(UUID tripId) {
    SourceBalance source = computeFromSource(tripId);
    List<SettlementTransfer> persisted = settlementTransferRepository.findByTripId(tripId);

    List<SettlementTransferDto> settlements =
        source.transfers().stream().map(transfer -> toSettlementDto(transfer, persisted)).toList();

    boolean allSettled =
        settlements.isEmpty()
            || settlements.stream()
                .allMatch(s -> SettlementTransferDto.STATUS_DONE.equals(s.getStatus()));

    return BalanceResponse.builder()
        .tripId(tripId)
        .referenceCurrency(source.referenceCurrency())
        .participantBalances(source.participantBalances())
        .settlements(settlements)
        .allSettled(allSettled)
        .computedAt(Instant.now())
        .build();
  }

  private static SettlementTransferDto toSettlementDto(
      Transfer transfer, List<SettlementTransfer> persisted) {
    Optional<SettlementTransfer> match =
        persisted.stream().filter(p -> matches(transfer, p)).findFirst();
    return SettlementTransferDto.builder()
        .fromMemberId(transfer.fromMemberId())
        .toMemberId(transfer.toMemberId())
        .amount(transfer.amount())
        .currency(transfer.currency())
        .status(
            match.isPresent()
                ? SettlementTransferDto.STATUS_DONE
                : SettlementTransferDto.STATUS_PENDING)
        .settledAt(match.map(SettlementTransfer::getSettledAt).orElse(null))
        .settledByMemberId(match.map(SettlementTransfer::getSettledByMemberId).orElse(null))
        .build();
  }

  private static boolean matches(Transfer transfer, SettlementTransfer persisted) {
    return transfer.fromMemberId().equals(persisted.getFromMemberId())
        && transfer.toMemberId().equals(persisted.getToMemberId())
        && transfer.amount().compareTo(persisted.getAmount()) == 0
        && transfer.currency().equals(persisted.getCurrency());
  }

  private static boolean matches(Transfer transfer, MarkTransferDoneRequest req) {
    return transfer.fromMemberId().equals(req.getFromMemberId())
        && transfer.toMemberId().equals(req.getToMemberId())
        && transfer.amount().compareTo(req.getAmount()) == 0
        && transfer.currency().equals(req.getCurrency());
  }

  /**
   * Each expense already carries {@code amountInReferenceCurrency} (stored at write time by story
   * 5.2, non-null column). Per-split shares are stored in the expense's original currency, so they
   * are converted to the reference currency with the same stored {@code exchangeRate}.
   */
  private ConvertedExpense toConvertedExpense(Expense expense) {
    BigDecimal exchangeRate = expense.getExchangeRate();
    List<Split> splits =
        splitsOf(expense).stream()
            .map(split -> new Split(split.getTripMemberId(), toReferenceShare(split, exchangeRate)))
            .toList();
    return new ConvertedExpense(
        expense.getPaidByTripMemberId(), expense.getAmountInReferenceCurrency(), splits);
  }

  private static List<ExpenseSplit> splitsOf(Expense expense) {
    return expense.getSplits() != null ? expense.getSplits() : List.of();
  }

  private static BigDecimal toReferenceShare(ExpenseSplit split, BigDecimal exchangeRate) {
    return split.getShareAmount().multiply(exchangeRate).setScale(REF_SCALE, RoundingMode.HALF_UP);
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
      for (ExpenseSplit split : splitsOf(expense)) {
        ids.add(split.getTripMemberId());
      }
    }
    return ids;
  }

  private static UUID parseMemberId(TripMembership membership) {
    if (membership.tripMemberId() == null) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Trip membership did not return a member id");
    }
    return UUID.fromString(membership.tripMemberId());
  }

  // ---------------------------------------------------------------------------
  // Redis cache
  // ---------------------------------------------------------------------------

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

  private void evictCache(UUID tripId) {
    redisTemplate.delete(cacheKey(tripId));
  }

  private static String cacheKey(UUID tripId) {
    return CACHE_KEY_PREFIX + tripId;
  }

  /** Internal carrier for the raw computed plan. */
  private record SourceBalance(
      String referenceCurrency,
      List<Transfer> transfers,
      Map<UUID, BigDecimal> participantBalances) {}
}
