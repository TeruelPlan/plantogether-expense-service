package com.plantogether.expense.service;

import com.plantogether.common.exception.AccessDeniedException;
import com.plantogether.common.grpc.TripClient;
import com.plantogether.common.grpc.TripMember;
import com.plantogether.expense.validation.AllowedCurrenciesValidator;
import io.grpc.StatusRuntimeException;
import com.plantogether.expense.domain.Expense;
import com.plantogether.expense.domain.ExpenseSplit;
import com.plantogether.expense.dto.ExpenseResponse;
import com.plantogether.expense.dto.RecordExpenseRequest;
import com.plantogether.expense.event.publisher.ExpenseEventPublisher.ExpenseCreatedInternalEvent;
import com.plantogether.expense.fx.ExchangeRateProvider;
import com.plantogether.expense.fx.ExchangeRateProvider.FxQuote;
import com.plantogether.expense.repository.ExpenseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ExpenseService {

    private final ExpenseRepository expenseRepository;
    private final TripClient tripClient;
    private final ApplicationEventPublisher eventPublisher;
    private final ExchangeRateProvider exchangeRateProvider;

    @Transactional
    public ExpenseResponse recordExpense(UUID tripId, String deviceId, RecordExpenseRequest req) {
        if (!tripClient.isMember(tripId.toString(), deviceId)) {
            throw new AccessDeniedException("Not a member of this trip");
        }

        Set<UUID> memberIds = loadTripMemberIds(tripId);

        UUID paidBy = req.getPaidBy() != null ? req.getPaidBy() : UUID.fromString(deviceId);
        if (!memberIds.contains(paidBy)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "paidBy must be a member of the trip");
        }

        String description = sanitizeDescription(req.getDescription());

        List<ExpenseSplit> splits = resolveSplits(req, memberIds);

        // Multi-currency: snapshot the FX rate at entry time. Values are immutable after creation.
        String referenceCurrency;
        try {
            referenceCurrency = tripClient.getTripCurrency(tripId.toString());
        } catch (StatusRuntimeException ex) {
            throw mapGrpcException(ex);
        }
        if (referenceCurrency == null || referenceCurrency.isBlank()
                || !AllowedCurrenciesValidator.SUPPORTED.contains(referenceCurrency)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Trip reference currency is unavailable or unsupported: " + referenceCurrency);
        }
        FxQuote fx = exchangeRateProvider.getRate(req.getCurrency(), referenceCurrency);
        BigDecimal exchangeRate = fx.rate().setScale(4, RoundingMode.HALF_UP);
        BigDecimal amountInReferenceCurrency = req.getAmount()
                .multiply(exchangeRate)
                .setScale(4, RoundingMode.HALF_UP);

        Expense expense = Expense.builder()
                .tripId(tripId)
                .paidBy(paidBy)
                .amount(req.getAmount())
                .currency(req.getCurrency())
                .category(req.getCategory())
                .description(description)
                .receiptKey(req.getReceiptKey())
                .splitMode(req.getSplitMode())
                .exchangeRate(exchangeRate)
                .amountInReferenceCurrency(amountInReferenceCurrency)
                .referenceCurrency(referenceCurrency)
                .rateSource(fx.source())
                .rateFetchedAt(fx.fetchedAt())
                .build();

        splits.forEach(expense::addSplit);

        Expense saved = expenseRepository.save(expense);

        eventPublisher.publishEvent(new ExpenseCreatedInternalEvent(
                saved.getId(),
                saved.getTripId(),
                saved.getPaidBy().toString(),
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
        return expenseRepository.findByTripIdAndDeletedAtIsNull(tripId, pageable)
                .map(ExpenseResponse::from);
    }

    private Set<UUID> loadTripMemberIds(UUID tripId) {
        List<TripMember> members;
        try {
            members = tripClient.getTripMembers(tripId.toString());
        } catch (StatusRuntimeException ex) {
            throw mapGrpcException(ex);
        }
        Set<UUID> memberIds = members.stream()
                .map(TripMember::deviceId)
                .collect(Collectors.toSet());
        if (memberIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Trip not found or has no members");
        }
        return memberIds;
    }

    private static ResponseStatusException mapGrpcException(StatusRuntimeException ex) {
        return switch (ex.getStatus().getCode()) {
            case UNAVAILABLE, DEADLINE_EXCEEDED ->
                    new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus().getDescription());
            case PERMISSION_DENIED ->
                    new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getStatus().getDescription());
            case NOT_FOUND ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getStatus().getDescription());
            default ->
                    new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");
        };
    }

    private String sanitizeDescription(String raw) {
        String stripped = raw.strip();
        if (stripped.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "description must not be blank");
        }
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (Character.isISOControl(c) && c != '\n' && c != '\t') {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "description must not contain control characters");
            }
        }
        return stripped;
    }

    private List<ExpenseSplit> resolveSplits(RecordExpenseRequest req, Set<UUID> memberIds) {
        if (req.getSplits() != null && !req.getSplits().isEmpty()) {
            return resolveExplicitSplits(req, memberIds);
        }

        if (req.getSplitMode() == com.plantogether.expense.domain.SplitMode.EQUAL) {
            return resolveEqualSplits(req.getAmount(), memberIds);
        }

        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "CUSTOM and PERCENTAGE split modes require explicit splits");
    }

    private List<ExpenseSplit> resolveExplicitSplits(RecordExpenseRequest req, Set<UUID> memberIds) {
        if (req.getSplitMode() == com.plantogether.expense.domain.SplitMode.EQUAL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "EQUAL split mode must not include explicit splits");
        }

        List<RecordExpenseRequest.SplitInput> inputs = req.getSplits();

        Set<UUID> seen = new HashSet<>();
        for (RecordExpenseRequest.SplitInput s : inputs) {
            if (!memberIds.contains(s.getDeviceId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "splits[] contains a deviceId that is not a member of the trip");
            }
            if (!seen.add(s.getDeviceId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "splits[] contains duplicate deviceId entries");
            }
        }

        if (req.getSplitMode() == com.plantogether.expense.domain.SplitMode.PERCENTAGE) {
            BigDecimal pctSum = inputs.stream()
                    .map(RecordExpenseRequest.SplitInput::getShareAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (pctSum.compareTo(BigDecimal.valueOf(100)) != 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "PERCENTAGE splits must sum to 100");
            }
            return convertPercentagesToShares(inputs, req.getAmount());
        }

        // CUSTOM
        BigDecimal sum = inputs.stream()
                .map(RecordExpenseRequest.SplitInput::getShareAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(req.getAmount()) != 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "CUSTOM splits must sum to amount");
        }
        return inputs.stream()
                .map(s -> ExpenseSplit.builder()
                        .deviceId(s.getDeviceId())
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
            BigDecimal share = amount.multiply(s.getShareAmount())
                    .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
            accumulated = accumulated.add(share);
            splits.add(ExpenseSplit.builder()
                    .deviceId(s.getDeviceId())
                    .shareAmount(share)
                    .build());
        }
        RecordExpenseRequest.SplitInput last = inputs.get(inputs.size() - 1);
        splits.add(ExpenseSplit.builder()
                .deviceId(last.getDeviceId())
                .shareAmount(amount.subtract(accumulated))
                .build());
        return splits;
    }

    private List<ExpenseSplit> resolveEqualSplits(BigDecimal total, Set<UUID> memberIds) {
        List<String> sorted = memberIds.stream()
                .map(UUID::toString)
                .sorted()
                .toList();

        int n = sorted.size();
        BigDecimal each = total.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
        BigDecimal accumulated = each.multiply(BigDecimal.valueOf(n - 1));
        BigDecimal last = total.subtract(accumulated);

        List<ExpenseSplit> splits = new ArrayList<>();
        for (int i = 0; i < n - 1; i++) {
            splits.add(ExpenseSplit.builder()
                    .deviceId(UUID.fromString(sorted.get(i)))
                    .shareAmount(each)
                    .build());
        }
        splits.add(ExpenseSplit.builder()
                .deviceId(UUID.fromString(sorted.get(n - 1)))
                .shareAmount(last)
                .build());
        return splits;
    }
}
