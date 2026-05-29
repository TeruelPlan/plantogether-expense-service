package com.plantogether.expense.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Pure, stateless settlement algorithm. Computes per-participant net balances and the minimal set
 * of transfers (greedy debt simplification) needed to settle a trip.
 *
 * <p>All amounts are expected to be pre-converted to the trip reference currency by the caller.
 * Identity is the per-trip {@code memberId} (UUID) — never a device id (the expense domain migrated
 * to member ids in migrations V3/V4).
 *
 * <p>The greedy "largest debtor pairs with largest creditor" approach terminates in at most N-1
 * transfers for N participants with a non-zero net balance. It is deterministic: equal-magnitude
 * pairings are tie-broken by {@code memberId.toString()} lexicographic order so repeated runs on
 * the same input produce an identical transfer list (required for caching + tests).
 */
@Component
public class BalanceCalculator {

  /** Internal working scale for net balances before pairing. */
  private static final int NET_SCALE = 4;

  /** Output scale for transfer amounts exposed to clients. */
  private static final int TRANSFER_SCALE = 2;

  /** Balances within this absolute tolerance are treated as already settled. */
  private static final BigDecimal SETTLED_TOLERANCE = new BigDecimal("0.01");

  /**
   * A trip expense already converted to the reference currency.
   *
   * @param paidByMemberId the member who paid
   * @param amountRef total amount in the reference currency
   * @param splits per-member shares in the reference currency
   */
  public record ConvertedExpense(UUID paidByMemberId, BigDecimal amountRef, List<Split> splits) {}

  /**
   * A single member's share of an expense, in the reference currency.
   *
   * @param memberId the member who owes this share
   * @param shareAmountRef the owed amount in the reference currency
   */
  public record Split(UUID memberId, BigDecimal shareAmountRef) {}

  /**
   * A minimal transfer from a debtor to a creditor.
   *
   * @param fromMemberId the member who pays
   * @param toMemberId the member who receives
   * @param amount transfer amount in the reference currency (2-decimal)
   * @param currency reference currency code
   */
  public record Transfer(UUID fromMemberId, UUID toMemberId, BigDecimal amount, String currency) {}

  /**
   * Result of a settlement computation.
   *
   * @param participantBalances net balance per member (positive = creditor, negative = debtor)
   * @param transfers minimal transfer list
   */
  public record BalanceResult(
      Map<UUID, BigDecimal> participantBalances, List<Transfer> transfers) {}

  /**
   * Computes net balances and the minimal transfer set.
   *
   * @param expenses pre-converted expenses
   * @param participants every member that belongs to the trip (so members with a zero balance still
   *     appear in {@code participantBalances})
   * @param referenceCurrency the trip reference currency stamped onto every transfer
   * @return the per-member balances and the minimal transfers
   */
  public BalanceResult compute(
      List<ConvertedExpense> expenses, Set<UUID> participants, String referenceCurrency) {

    Map<UUID, BigDecimal> net = new LinkedHashMap<>();
    for (UUID member : participants) {
      net.put(member, BigDecimal.ZERO);
    }

    for (ConvertedExpense expense : expenses) {
      net.merge(expense.paidByMemberId(), expense.amountRef(), BigDecimal::add);
      for (Split split : expense.splits()) {
        net.merge(split.memberId(), split.shareAmountRef().negate(), BigDecimal::add);
      }
    }

    Map<UUID, BigDecimal> rounded = new LinkedHashMap<>();
    for (Map.Entry<UUID, BigDecimal> entry : net.entrySet()) {
      rounded.put(entry.getKey(), entry.getValue().setScale(NET_SCALE, RoundingMode.HALF_UP));
    }

    List<Transfer> transfers =
        pairDebtorsToCreditors(new LinkedHashMap<>(rounded), referenceCurrency);

    return new BalanceResult(rounded, transfers);
  }

  private List<Transfer> pairDebtorsToCreditors(
      Map<UUID, BigDecimal> net, String referenceCurrency) {
    List<MutableBalance> debtors = new ArrayList<>();
    List<MutableBalance> creditors = new ArrayList<>();
    for (Map.Entry<UUID, BigDecimal> entry : net.entrySet()) {
      BigDecimal value = entry.getValue();
      if (value.abs().compareTo(SETTLED_TOLERANCE) <= 0) {
        continue;
      }
      if (value.signum() < 0) {
        debtors.add(new MutableBalance(entry.getKey(), value));
      } else {
        creditors.add(new MutableBalance(entry.getKey(), value));
      }
    }

    List<Transfer> transfers = new ArrayList<>();
    while (!debtors.isEmpty() && !creditors.isEmpty()) {
      // Largest debtor (most negative) and largest creditor (most positive) first; lexicographic
      // tie-break on memberId keeps equal-magnitude pairings deterministic.
      debtors.sort(
          Comparator.comparing((MutableBalance b) -> b.amount.abs())
              .reversed()
              .thenComparing(b -> b.memberId.toString()));
      creditors.sort(
          Comparator.comparing((MutableBalance b) -> b.amount)
              .reversed()
              .thenComparing(b -> b.memberId.toString()));

      MutableBalance debtor = debtors.get(0);
      MutableBalance creditor = creditors.get(0);

      BigDecimal transferAmount =
          debtor.amount.abs().min(creditor.amount).setScale(TRANSFER_SCALE, RoundingMode.HALF_UP);

      if (transferAmount.signum() > 0) {
        transfers.add(
            new Transfer(debtor.memberId, creditor.memberId, transferAmount, referenceCurrency));
      }

      debtor.amount = debtor.amount.add(transferAmount);
      creditor.amount = creditor.amount.subtract(transferAmount);

      if (debtor.amount.abs().compareTo(SETTLED_TOLERANCE) <= 0) {
        debtors.remove(0);
      }
      if (creditor.amount.abs().compareTo(SETTLED_TOLERANCE) <= 0) {
        creditors.remove(0);
      }
    }

    return transfers;
  }

  private static final class MutableBalance {
    private final UUID memberId;
    private BigDecimal amount;

    private MutableBalance(UUID memberId, BigDecimal amount) {
      this.memberId = memberId;
      this.amount = amount;
    }
  }
}
