package com.plantogether.expense.service;

import com.plantogether.common.exception.AccessDeniedException;
import com.plantogether.common.exception.ResourceNotFoundException;
import com.plantogether.common.grpc.Role;
import com.plantogether.common.grpc.TripClient;
import com.plantogether.common.grpc.TripMember;
import com.plantogether.common.grpc.TripMembership;
import com.plantogether.expense.domain.Expense;
import com.plantogether.expense.domain.ExpenseSplit;
import com.plantogether.expense.domain.SplitMode;
import com.plantogether.expense.dto.ExpenseResponse;
import com.plantogether.expense.dto.RecordExpenseRequest;
import com.plantogether.expense.dto.UpdateExpenseRequest;
import com.plantogether.expense.event.publisher.ExpenseEventPublisher.ExpenseCreatedInternalEvent;
import com.plantogether.expense.event.publisher.ExpenseEventPublisher.ExpenseDeletedInternalEvent;
import com.plantogether.expense.fx.ExchangeRateProvider;
import com.plantogether.expense.fx.ExchangeRateProvider.FxQuote;
import com.plantogether.expense.repository.ExpenseRepository;
import com.plantogether.expense.validation.AllowedCurrenciesValidator;
import io.grpc.StatusRuntimeException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ExpenseService {

  private static final BigDecimal SPLIT_SUM_TOLERANCE = new BigDecimal("0.01");

  private final ExpenseRepository expenseRepository;
  private final TripClient tripClient;
  private final ApplicationEventPublisher eventPublisher;
  private final ExchangeRateProvider exchangeRateProvider;

  @Transactional
  public ExpenseResponse recordExpense(UUID tripId, String deviceId, RecordExpenseRequest req) {
    TripMembership membership;
    try {
      membership = tripClient.requireMembership(tripId.toString(), deviceId);
    } catch (StatusRuntimeException ex) {
      throw mapGrpcException(ex);
    }
    UUID callerMemberId = UUID.fromString(membership.tripMemberId());

    Set<UUID> memberIds = loadMemberIds(tripId);

    UUID paidByMemberId =
        req.getPaidByMemberId() != null ? req.getPaidByMemberId() : callerMemberId;
    if (!memberIds.contains(paidByMemberId)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "paidBy must be a member of the trip");
    }

    String description = sanitizeDescription(req.getDescription());

    List<ExpenseSplit> splits =
        resolveSplits(req.getAmount(), req.getSplitMode(), req.getSplits(), memberIds);

    FxSnapshot fx = snapshotFx(tripId, req.getCurrency(), req.getAmount());

    Expense expense =
        Expense.builder()
            .tripId(tripId)
            .paidByTripMemberId(paidByMemberId)
            .amount(req.getAmount())
            .currency(req.getCurrency())
            .category(req.getCategory())
            .description(description)
            .receiptKey(req.getReceiptKey())
            .splitMode(req.getSplitMode())
            .exchangeRate(fx.exchangeRate)
            .amountInReferenceCurrency(fx.amountInReferenceCurrency)
            .referenceCurrency(fx.referenceCurrency)
            .rateSource(fx.rateSource)
            .rateFetchedAt(fx.rateFetchedAt)
            .build();

    splits.forEach(expense::addSplit);

    Expense saved = expenseRepository.save(expense);

    eventPublisher.publishEvent(
        new ExpenseCreatedInternalEvent(
            saved.getId(),
            saved.getTripId(),
            saved.getPaidByTripMemberId().toString(),
            saved.getAmount(),
            saved.getDescription(),
            saved.getCreatedAt()));

    return ExpenseResponse.from(saved);
  }

  @Transactional(readOnly = true)
  public Page<ExpenseResponse> listExpenses(UUID tripId, String deviceId, Pageable pageable) {
    if (!tripClient.isMember(tripId.toString(), deviceId)) {
      throw new AccessDeniedException("Not a member of this trip");
    }
    return expenseRepository
        .findByTripIdAndDeletedAtIsNull(tripId, pageable)
        .map(ExpenseResponse::from);
  }

  @Transactional
  public ExpenseResponse updateExpense(UUID expenseId, String deviceId, UpdateExpenseRequest req) {
    Expense expense =
        expenseRepository
            .findByIdAndDeletedAtIsNull(expenseId)
            .orElseThrow(() -> new ResourceNotFoundException("Expense", expenseId));

    assertCanModify(expense, deviceId);

    Set<UUID> memberIds = loadMemberIds(expense.getTripId());

    String description = sanitizeDescription(req.getDescription());
    List<ExpenseSplit> newSplits =
        resolveSplits(req.getAmount(), req.getSplitMode(), req.getSplits(), memberIds);

    FxSnapshot fx = snapshotFx(expense.getTripId(), req.getCurrency(), req.getAmount());

    expense.setAmount(req.getAmount());
    expense.setCurrency(req.getCurrency());
    expense.setCategory(req.getCategory());
    expense.setDescription(description);
    expense.setReceiptKey(req.getReceiptKey());
    expense.setSplitMode(req.getSplitMode());
    expense.setExchangeRate(fx.exchangeRate);
    expense.setAmountInReferenceCurrency(fx.amountInReferenceCurrency);
    expense.setReferenceCurrency(fx.referenceCurrency);
    expense.setRateSource(fx.rateSource);
    expense.setRateFetchedAt(fx.rateFetchedAt);

    expense.getSplits().clear();
    newSplits.forEach(expense::addSplit);

    Expense saved = expenseRepository.save(expense);
    return ExpenseResponse.from(saved);
  }

  @Transactional
  public void deleteExpense(UUID expenseId, String deviceId) {
    Expense expense =
        expenseRepository
            .findByIdAndDeletedAtIsNull(expenseId)
            .orElseThrow(() -> new ResourceNotFoundException("Expense", expenseId));

    TripMembership membership = assertCanModify(expense, deviceId);

    Instant deletedAt = Instant.now();
    expense.setDeletedAt(deletedAt);
    expenseRepository.save(expense);

    eventPublisher.publishEvent(
        new ExpenseDeletedInternalEvent(
            expense.getId(),
            expense.getTripId(),
            expense.getPaidByTripMemberId(),
            UUID.fromString(membership.tripMemberId()),
            deletedAt));
  }

  /**
   * Authorizes a modify (edit/delete) operation: the caller must currently be a trip member AND
   * either the original payer OR the trip ORGANIZER. The membership gate runs unconditionally so a
   * former member who happens to be the original payer can no longer mutate the expense after being
   * removed from the trip.
   */
  private TripMembership assertCanModify(Expense expense, String deviceId) {
    TripMembership membership;
    try {
      membership = tripClient.requireMembership(expense.getTripId().toString(), deviceId);
    } catch (StatusRuntimeException ex) {
      throw mapGrpcException(ex);
    }
    UUID callerMemberId = UUID.fromString(membership.tripMemberId());
    boolean isPayer = callerMemberId.equals(expense.getPaidByTripMemberId());
    if (!isPayer && membership.role() != Role.ORGANIZER) {
      throw new AccessDeniedException(
          "Only the payer or the trip organizer can modify this expense");
    }
    return membership;
  }

  private Set<UUID> loadMemberIds(UUID tripId) {
    List<TripMember> members;
    try {
      members = tripClient.getTripMembers(tripId.toString());
    } catch (StatusRuntimeException ex) {
      throw mapGrpcException(ex);
    }
    if (members.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Trip not found or has no members");
    }
    Set<UUID> ids = new LinkedHashSet<>();
    for (TripMember m : members) {
      ids.add(UUID.fromString(m.tripMemberId()));
    }
    return ids;
  }

  private FxSnapshot snapshotFx(UUID tripId, String currency, BigDecimal amount) {
    String referenceCurrency;
    try {
      referenceCurrency = tripClient.getTripCurrency(tripId.toString());
    } catch (StatusRuntimeException ex) {
      throw mapGrpcException(ex);
    }
    if (referenceCurrency == null
        || referenceCurrency.isBlank()
        || !AllowedCurrenciesValidator.SUPPORTED.contains(referenceCurrency)) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Trip reference currency is unavailable or unsupported: " + referenceCurrency);
    }
    FxQuote fx = exchangeRateProvider.getRate(currency, referenceCurrency);
    BigDecimal exchangeRate = fx.rate().setScale(4, RoundingMode.HALF_UP);
    BigDecimal amountInReferenceCurrency =
        amount.multiply(exchangeRate).setScale(4, RoundingMode.HALF_UP);
    return new FxSnapshot(
        exchangeRate, amountInReferenceCurrency, referenceCurrency, fx.source(), fx.fetchedAt());
  }

  private static ResponseStatusException mapGrpcException(StatusRuntimeException ex) {
    return switch (ex.getStatus().getCode()) {
      case UNAVAILABLE, DEADLINE_EXCEEDED ->
          new ResponseStatusException(
              HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus().getDescription());
      case PERMISSION_DENIED ->
          new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getStatus().getDescription());
      case NOT_FOUND ->
          new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getStatus().getDescription());
      default -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");
    };
  }

  private String sanitizeDescription(String raw) {
    String stripped = raw.strip();
    if (stripped.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "description must not be blank");
    }
    for (int i = 0; i < stripped.length(); i++) {
      char c = stripped.charAt(i);
      if (Character.isISOControl(c) && c != '\n' && c != '\t') {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "description must not contain control characters");
      }
    }
    return stripped;
  }

  private List<ExpenseSplit> resolveSplits(
      BigDecimal amount,
      SplitMode splitMode,
      List<RecordExpenseRequest.SplitInput> inputs,
      Set<UUID> memberIds) {
    if (inputs != null && !inputs.isEmpty()) {
      return resolveExplicitSplits(amount, splitMode, inputs, memberIds);
    }
    if (splitMode == SplitMode.EQUAL) {
      return resolveEqualSplits(amount, memberIds);
    }
    throw new ResponseStatusException(
        HttpStatus.BAD_REQUEST, "CUSTOM and PERCENTAGE split modes require explicit splits");
  }

  private List<ExpenseSplit> resolveExplicitSplits(
      BigDecimal amount,
      SplitMode splitMode,
      List<RecordExpenseRequest.SplitInput> inputs,
      Set<UUID> memberIds) {
    if (splitMode == SplitMode.EQUAL) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "EQUAL split mode must not include explicit splits");
    }

    Set<UUID> seen = new HashSet<>();
    for (RecordExpenseRequest.SplitInput s : inputs) {
      if (!memberIds.contains(s.getMemberId())) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "splits[] contains a memberId that is not a member of the trip");
      }
      if (!seen.add(s.getMemberId())) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "splits[] contains duplicate memberId entries");
      }
    }

    if (splitMode == SplitMode.PERCENTAGE) {
      BigDecimal pctSum =
          inputs.stream()
              .map(RecordExpenseRequest.SplitInput::getShareAmount)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      if (pctSum.compareTo(BigDecimal.valueOf(100)) != 0) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "PERCENTAGE splits must sum to 100");
      }
      return convertPercentagesToShares(inputs, amount);
    }

    BigDecimal sum =
        inputs.stream()
            .map(RecordExpenseRequest.SplitInput::getShareAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    if (sum.subtract(amount).abs().compareTo(SPLIT_SUM_TOLERANCE) > 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "CUSTOM splits must sum to amount within ±0.01");
    }
    return inputs.stream()
        .map(
            s ->
                ExpenseSplit.builder()
                    .tripMemberId(s.getMemberId())
                    .shareAmount(s.getShareAmount())
                    .build())
        .toList();
  }

  private List<ExpenseSplit> convertPercentagesToShares(
      List<RecordExpenseRequest.SplitInput> inputs, BigDecimal amount) {
    List<ExpenseSplit> splits = new ArrayList<>();
    BigDecimal accumulated = BigDecimal.ZERO;
    for (int i = 0; i < inputs.size() - 1; i++) {
      RecordExpenseRequest.SplitInput s = inputs.get(i);
      BigDecimal share =
          amount
              .multiply(s.getShareAmount())
              .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
      accumulated = accumulated.add(share);
      splits.add(ExpenseSplit.builder().tripMemberId(s.getMemberId()).shareAmount(share).build());
    }
    RecordExpenseRequest.SplitInput last = inputs.getLast();
    splits.add(
        ExpenseSplit.builder()
            .tripMemberId(last.getMemberId())
            .shareAmount(amount.subtract(accumulated))
            .build());
    return splits;
  }

  private List<ExpenseSplit> resolveEqualSplits(BigDecimal total, Set<UUID> memberIds) {
    List<String> sorted = memberIds.stream().map(UUID::toString).sorted().toList();

    int n = sorted.size();
    BigDecimal each = total.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
    BigDecimal accumulated = each.multiply(BigDecimal.valueOf(n - 1));
    BigDecimal last = total.subtract(accumulated);

    List<ExpenseSplit> splits = new ArrayList<>();
    for (int i = 0; i < n - 1; i++) {
      splits.add(
          ExpenseSplit.builder()
              .tripMemberId(UUID.fromString(sorted.get(i)))
              .shareAmount(each)
              .build());
    }
    splits.add(
        ExpenseSplit.builder()
            .tripMemberId(UUID.fromString(sorted.get(n - 1)))
            .shareAmount(last)
            .build());
    return splits;
  }

  private record FxSnapshot(
      BigDecimal exchangeRate,
      BigDecimal amountInReferenceCurrency,
      String referenceCurrency,
      com.plantogether.expense.domain.RateSource rateSource,
      Instant rateFetchedAt) {}
}
