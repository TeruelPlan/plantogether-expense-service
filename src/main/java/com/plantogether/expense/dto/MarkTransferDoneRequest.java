package com.plantogether.expense.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Body of {@code PATCH /api/v1/trips/{tripId}/balance/settlements} (story 5.4.2). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarkTransferDoneRequest {

  @NotNull private UUID fromMemberId;

  @NotNull private UUID toMemberId;

  @NotNull @Positive private BigDecimal amount;

  @NotBlank
  @Pattern(regexp = "^[A-Z]{3}$")
  private String currency;
}
