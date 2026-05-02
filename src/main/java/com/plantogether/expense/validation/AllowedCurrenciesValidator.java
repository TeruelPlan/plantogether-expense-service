package com.plantogether.expense.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Set;

public class AllowedCurrenciesValidator implements ConstraintValidator<AllowedCurrencies, String> {

  /** MVP currency allow-list. Order matches the UI dropdown. */
  public static final Set<String> SUPPORTED = Set.of("EUR", "USD", "GBP", "CHF", "JPY");

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    if (value == null) {
      // @NotBlank handles null/empty separately — let it produce its own message.
      return true;
    }
    return SUPPORTED.contains(value);
  }
}
