package com.plantogether.expense.repository;

import com.plantogether.expense.domain.Expense;
import com.plantogether.expense.domain.ExpenseCategory;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExpenseRepository extends JpaRepository<Expense, UUID> {

  @EntityGraph(attributePaths = {"splits"})
  Page<Expense> findByTripIdAndDeletedAtIsNull(UUID tripId, Pageable pageable);

  /** All non-deleted expenses for a trip with their splits eagerly fetched (settlement compute). */
  @EntityGraph(attributePaths = {"splits"})
  List<Expense> findAllByTripIdAndDeletedAtIsNull(UUID tripId);

  Optional<Expense> findByIdAndDeletedAtIsNull(UUID id);

  /** Per-category totals in the reference currency for the breakdown (story 5.5). */
  @Query(
      "SELECT e.category AS category,"
          + " SUM(e.amountInReferenceCurrency) AS total,"
          + " COUNT(e) AS expenseCount"
          + " FROM Expense e"
          + " WHERE e.tripId = :tripId AND e.deletedAt IS NULL"
          + " GROUP BY e.category")
  List<CategoryTotalProjection> aggregateByCategory(@Param("tripId") UUID tripId);

  /** Projection backing {@link #aggregateByCategory(UUID)}. */
  interface CategoryTotalProjection {
    ExpenseCategory getCategory();

    BigDecimal getTotal();

    long getExpenseCount();
  }
}
