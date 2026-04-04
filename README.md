# Expense Service

> Shared budget and trip expense management service

## Role in the Architecture

The Expense Service manages group expenses, cost splitting, and the settlement algorithm.
It verifies trip membership via gRPC (TripService.IsMember) and retrieves the reference currency via
TripService.GetTripCurrency for multi-currency support.

## Features

- Expense recording with split modes (EQUAL / CUSTOM / PERCENTAGE)
- Soft delete of expenses (`deleted_at`)
- Greedy settlement algorithm (minimum transactions for N participants)
- Multi-currency support (conversion via ECB/Fixer API to the trip's reference currency)
- CSV or PDF expense report export
- Membership verification via gRPC before each operation

## REST Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/trips/{id}/expenses` | Add an expense |
| GET | `/api/v1/trips/{id}/expenses` | List expenses (paginated) |
| PUT | `/api/v1/expenses/{id}` | Update an expense |
| DELETE | `/api/v1/expenses/{id}` | Delete (soft delete) |
| GET | `/api/v1/trips/{id}/balance` | Computed balance |
| GET | `/api/v1/trips/{id}/expenses/export` | CSV or PDF export |

## gRPC Clients

- `TripService.IsMember(tripId, deviceId)` — membership verification
- `TripService.GetTripCurrency(tripId)` — reference currency for settlement
- `FileService.GetPresignedUrl(key)` — read URL for receipts

## Data Model (`db_expense`)

**expense**

| Column | Type | Description |
|--------|------|-------------|
| `id` | UUID PK | Unique identifier (UUID v7) |
| `trip_id` | UUID NOT NULL | Trip reference |
| `paid_by` | UUID NOT NULL | device_id of the payer |
| `amount` | DECIMAL NOT NULL | Amount |
| `currency` | VARCHAR(3) NOT NULL | Currency (ISO 4217) |
| `category` | ENUM NOT NULL | FOOD / TRANSPORT / ACCOMMODATION / ACTIVITY / OTHER |
| `description` | VARCHAR(255) NOT NULL | Description |
| `receipt_key` | VARCHAR(500) NULLABLE | MinIO key for receipt |
| `split_mode` | ENUM NOT NULL | EQUAL / CUSTOM / PERCENTAGE |
| `created_at` | TIMESTAMP NOT NULL | |
| `updated_at` | TIMESTAMP NOT NULL | |
| `deleted_at` | TIMESTAMP NULLABLE | Soft delete |

**expense_split**

| Column | Type | Description |
|--------|------|-------------|
| `id` | UUID PK | |
| `expense_id` | UUID NOT NULL FK→expense | |
| `device_id` | UUID NOT NULL | Participant device UUID |
| `share_amount` | DECIMAL NOT NULL | Share owed by this participant |

## Settlement Algorithm

The `BalanceCalculator` algorithm minimizes the number of transactions to settle group accounts:

1. Compute the net balance of each participant: `total_paid - total_owed`
2. Separate debtors (balance < 0) from creditors (balance > 0)
3. Sort both lists by absolute value descending
4. For each pair (largest debtor, largest creditor): transfer the minimum of the two amounts
5. Repeat until all balances are zero (± 0.01)

Guarantees at most **N-1 transactions** for N participants. Multi-currency is handled by converting all
expenses to the trip's reference currency via the ECB/Fixer API.

## RabbitMQ Events (Exchange: `plantogether.events`)

**Publishes:**

| Routing Key | Trigger |
|-------------|---------|
| `expense.created` | Expense added |
| `expense.deleted` | Expense deleted |

**Consumes:** none

## Configuration

```yaml
server:
  port: 8084

spring:
  application:
    name: plantogether-expense-service
  datasource:
    url: jdbc:postgresql://postgres:5432/db_expense
    username: ${DB_USER}
    password: ${DB_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: validate

grpc:
  client:
    trip-service:
      address: static://trip-service:9081
    file-service:
      address: static://file-service:9088
  server:
    port: 9084
```

## Running Locally

```bash
# Prerequisites: docker compose up -d
# + plantogether-proto and plantogether-common installed

mvn spring-boot:run
```

## Dependencies

- **PostgreSQL 16** (`db_expense`): expenses and splits
- **RabbitMQ**: event publishing (`expense.created`, `expense.deleted`)
- **Redis**: rate limiting (Bucket4j — 30 expenses/hour/device)
- **Trip Service** (gRPC 9081): membership verification + trip currency
- **File Service** (gRPC 9088): presigned URLs for receipts
- **plantogether-proto**: gRPC contracts (client + server)
- **plantogether-common**: event DTOs, DeviceIdFilter, SecurityAutoConfiguration, CorsConfig

## Security

- Anonymous device-based identity: `X-Device-Id` header on every request
- `DeviceIdFilter` (from plantogether-common, auto-configured via `SecurityAutoConfiguration`) extracts the device UUID and sets the SecurityContext principal
- No JWT, no Keycloak, no login, no sessions
- Trip membership is verified via gRPC before each operation
- Only the expense creator or ORGANIZER can modify or delete an expense
- Zero PII stored (only `device_id` references)
