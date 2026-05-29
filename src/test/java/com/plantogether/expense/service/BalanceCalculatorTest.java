package com.plantogether.expense.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.plantogether.expense.service.BalanceCalculator.BalanceResult;
import com.plantogether.expense.service.BalanceCalculator.ConvertedExpense;
import com.plantogether.expense.service.BalanceCalculator.Split;
import com.plantogether.expense.service.BalanceCalculator.Transfer;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BalanceCalculatorTest {

  private static final String EUR = "EUR";

  private final BalanceCalculator calculator = new BalanceCalculator();

  // Fixed UUIDs for deterministic lexicographic tie-break assertions.
  private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
  private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
  private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
  private static final UUID D = UUID.fromString("00000000-0000-0000-0000-00000000000d");

  @Test
  @DisplayName("compute returns no transfers for an empty expense list")
  void compute_emptyExpenses_returnsNoTransfers() {
    BalanceResult result = calculator.compute(List.of(), Set.of(A, B), EUR);

    assertThat(result.transfers()).isEmpty();
    assertThat(result.participantBalances())
        .containsOnlyKeys(A, B)
        .allSatisfy((id, net) -> assertThat(net).isEqualByComparingTo("0"));
  }

  @Test
  @DisplayName("compute returns zero transfers when an even split nets everyone to zero")
  void compute_allEvenSplit_returnsZeroTransfers() {
    // Alice pays 60 for 3 people, each owes 20 (incl. Alice) -> all net to zero.
    ConvertedExpense expense =
        new ConvertedExpense(
            A,
            new BigDecimal("60.00"),
            List.of(
                new Split(A, new BigDecimal("20.00")),
                new Split(B, new BigDecimal("20.00")),
                new Split(C, new BigDecimal("20.00"))));

    BalanceResult result = calculator.compute(List.of(expense), Set.of(A, B, C), EUR);

    assertThat(result.transfers()).isEmpty();
  }

  @Test
  @DisplayName("compute returns a single transfer for one debtor and one creditor")
  void compute_singleDebtorSingleCreditor_returnsOneTransfer() {
    // Alice pays 100, split 50/50; Bob owes Alice 50.
    ConvertedExpense expense =
        new ConvertedExpense(
            A,
            new BigDecimal("100.00"),
            List.of(new Split(A, new BigDecimal("50.00")), new Split(B, new BigDecimal("50.00"))));

    BalanceResult result = calculator.compute(List.of(expense), Set.of(A, B), EUR);

    assertThat(result.transfers()).hasSize(1);
    Transfer transfer = result.transfers().get(0);
    assertThat(transfer.fromMemberId()).isEqualTo(B);
    assertThat(transfer.toMemberId()).isEqualTo(A);
    assertThat(transfer.amount()).isEqualByComparingTo("50.00");
    assertThat(transfer.currency()).isEqualTo(EUR);
  }

  @Test
  @DisplayName("compute never exceeds N-1 transfers and conserves money")
  void compute_threeParty_asymmetric_returnsAtMostNMinusOne() {
    List<ConvertedExpense> expenses =
        List.of(
            evenExpense(A, "120.00", A, B, C, D), // A pays 120, each owes 30
            evenExpense(B, "40.00", A, B, C, D)); // B pays 40, each owes 10

    Set<UUID> participants = ordered(A, B, C, D);
    BalanceResult result = calculator.compute(expenses, participants, EUR);

    assertThat(result.transfers()).hasSizeLessThanOrEqualTo(3);
    assertConserved(result, participants);
  }

  @Test
  @DisplayName("compute is deterministic across identical runs (tie-break)")
  void compute_identicalBalancesTieBreaker_isDeterministic() {
    List<ConvertedExpense> expenses = List.of(evenExpense(A, "90.00", A, B, C, D));

    BalanceResult first = calculator.compute(expenses, ordered(A, B, C, D), EUR);
    BalanceResult second = calculator.compute(expenses, ordered(A, B, C, D), EUR);

    assertThat(first.transfers()).isEqualTo(second.transfers());
  }

  @Test
  @DisplayName("compute locks the lexicographic tie-break across equal-magnitude debtors")
  void compute_sameInputTwice_producesIdenticalOutput() {
    // Three debtors each owe exactly 30 to a single creditor A.
    ConvertedExpense expense =
        new ConvertedExpense(
            A,
            new BigDecimal("90.00"),
            List.of(
                new Split(B, new BigDecimal("30.00")),
                new Split(C, new BigDecimal("30.00")),
                new Split(D, new BigDecimal("30.00"))));

    BalanceResult first = calculator.compute(List.of(expense), ordered(A, B, C, D), EUR);
    BalanceResult second = calculator.compute(List.of(expense), ordered(A, B, C, D), EUR);

    assertThat(first.transfers()).isEqualTo(second.transfers());
    // Debtors B, C, D each send 30 to A; ordering follows memberId lexicographic tie-break.
    assertThat(first.transfers()).hasSize(3);
    assertThat(first.transfers()).allSatisfy(t -> assertThat(t.toMemberId()).isEqualTo(A));
  }

  @Test
  @DisplayName("compute drops sub-cent residual balances instead of emitting ghost transfers")
  void compute_roundingErrorWithinTolerance_doesNotCreateGhostTransfer() {
    // Net balances of ±0.004 must be treated as settled.
    ConvertedExpense expense =
        new ConvertedExpense(
            A, new BigDecimal("0.004"), List.of(new Split(B, new BigDecimal("0.004"))));

    BalanceResult result = calculator.compute(List.of(expense), Set.of(A, B), EUR);

    assertThat(result.transfers()).isEmpty();
  }

  @Test
  @DisplayName("compute handles 50 expenses across 20 members under 100ms")
  void compute_fiftyExpensesTwentyMembers_runsUnderHundredMs() {
    List<UUID> members = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      members.add(UUID.randomUUID());
    }
    Set<UUID> participants = new LinkedHashSet<>(members);

    List<ConvertedExpense> expenses = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      UUID payer = members.get(i % members.size());
      List<Split> splits = new ArrayList<>();
      for (UUID member : members) {
        splits.add(new Split(member, new BigDecimal("5.00")));
      }
      expenses.add(new ConvertedExpense(payer, new BigDecimal("100.00"), splits));
    }

    long start = System.nanoTime();
    BalanceResult result = calculator.compute(expenses, participants, EUR);
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(result.transfers()).hasSizeLessThanOrEqualTo(members.size() - 1);
    assertThat(elapsedMs).isLessThanOrEqualTo(100);
  }

  private static ConvertedExpense evenExpense(UUID payer, String amount, UUID... members) {
    BigDecimal total = new BigDecimal(amount);
    BigDecimal share =
        total.divide(BigDecimal.valueOf(members.length), 4, java.math.RoundingMode.HALF_UP);
    List<Split> splits = new ArrayList<>();
    for (UUID member : members) {
      splits.add(new Split(member, share));
    }
    return new ConvertedExpense(payer, total, splits);
  }

  private static Set<UUID> ordered(UUID... ids) {
    return new LinkedHashSet<>(List.of(ids));
  }

  /** Asserts every member's (received - sent) equals their net balance within 0.01. */
  private static void assertConserved(BalanceResult result, Set<UUID> participants) {
    for (UUID member : participants) {
      BigDecimal sent =
          result.transfers().stream()
              .filter(t -> t.fromMemberId().equals(member))
              .map(Transfer::amount)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal received =
          result.transfers().stream()
              .filter(t -> t.toMemberId().equals(member))
              .map(Transfer::amount)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal net = result.participantBalances().getOrDefault(member, BigDecimal.ZERO);
      // net positive (creditor) => received - sent ≈ net.
      BigDecimal delta = received.subtract(sent).subtract(net).abs();
      assertThat(delta.compareTo(new BigDecimal("0.01")))
          .as("member %s conservation delta=%s", member, delta)
          .isLessThanOrEqualTo(0);
    }
  }
}
