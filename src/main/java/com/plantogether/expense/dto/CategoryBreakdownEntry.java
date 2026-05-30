package com.plantogether.expense.dto;

import com.plantogether.expense.domain.ExpenseCategory;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One category's contribution to the trip spend (story 5.5). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryBreakdownEntry {

  private ExpenseCategory category;
  private BigDecimal totalAmount;
  private BigDecimal percentage;
  private int expenseCount;
}
