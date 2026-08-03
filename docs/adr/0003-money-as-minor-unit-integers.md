# ADR 0003: Money stored as minor-unit integers, not BigDecimal

## Status
Accepted

## Context
Representing money as a floating-point type is a well-known footgun
(binary floats can't represent most decimal fractions exactly).
`BigDecimal` avoids that but introduces its own complexity: scale
tracking, rounding-mode decisions on every arithmetic operation, and
behavior that varies by currency (JPY has 0 decimal places, most
currencies have 2, a few have 3).

## Decision
Store amounts as `long` minor units (cents, or the equivalent smallest
unit for the account's currency). All ledger arithmetic (`+`, `-`,
comparisons) is then exact integer arithmetic with no rounding-mode
decisions anywhere in the domain or service layer.

## Consequences
- Matches the convention used by Stripe and most payment processors,
  for the same reason.
- Converting to/from a human-readable decimal string (dividing or
  multiplying by 10^exponent, where exponent depends on the currency)
  is pushed to the API/presentation boundary, not scattered through
  the domain model.
- Tradeoff: the current schema doesn't store each currency's exponent
  anywhere (it assumes callers know JPY is 0-decimal, USD is 2-decimal,
  etc.). A production version would want a currency reference table
  (ISO 4217 code → exponent) rather than that knowledge living only in
  client code or a hardcoded lookup.
