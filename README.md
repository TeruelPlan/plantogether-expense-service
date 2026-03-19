# Expense Service

> Service de gestion du budget partagé et des dépenses

## Rôle dans l'architecture

L'Expense Service gère le budget collectif du voyage. Il enregistre toutes les dépenses (hébergement, transport,
nourriture, activités, divers), calcule automatiquement qui doit rembourser qui, supporte plusieurs devises avec
conversion en temps réel, et offre des exports en CSV/PDF. Le service minimise le nombre de transactions entre
participants grâce à un algorithme de règlement optimisé.

## Fonctionnalités

- Ajout et suivi des dépenses avec catégories
- Gestion de la cagnotte partagée (kitty)
- Calcul automatique des soldes et règlements
- Support multi-devises avec taux de change en temps réel
- Algorithme d'optimisation des transactions (minimise les échanges)
- Upload sécurisé des reçus vers MinIO via presigned URLs
- Export des données en CSV et PDF
- Historique complet des transactions
- Remboursements et ajustements

## Endpoints REST

| Méthode | Endpoint                                           | Description                       |
|---------|----------------------------------------------------|-----------------------------------|
| POST    | `/api/trips/{tripId}/expenses`                     | Créer une dépense                 |
| GET     | `/api/trips/{tripId}/expenses`                     | Lister les dépenses               |
| GET     | `/api/expenses/{expenseId}`                        | Récupérer une dépense             |
| PUT     | `/api/expenses/{expenseId}`                        | Modifier une dépense (créateur)   |
| DELETE  | `/api/expenses/{expenseId}`                        | Supprimer une dépense             |
| GET     | `/api/trips/{tripId}/balance`                      | Calculer les soldes et règlements |
| PUT     | `/api/trips/{tripId}/expenses/{expenseId}/receipt` | Uploader un reçu                  |
| GET     | `/api/trips/{tripId}/expenses/export/csv`          | Exporter en CSV                   |
| GET     | `/api/trips/{tripId}/expenses/export/pdf`          | Exporter en PDF                   |
| POST    | `/api/trips/{tripId}/kitty`                        | Ajouter à la cagnotte             |
| GET     | `/api/trips/{tripId}/kitty`                        | Consulter la cagnotte             |

## Modèle de données

**Expense**

- `id` (UUID) : identifiant unique
- `trip_id` (UUID, FK) : voyage associé
- `title` (String) : description de la dépense
- `category` (ENUM: HEBERGEMENT, TRANSPORT, NOURRITURE, ACTIVITES, DIVERS)
- `amount` (BigDecimal) : montant
- `currency` (String) : devise (EUR, USD, GBP, etc.)
- `paid_by` (UUID) : ID Keycloak de celui qui a payé
- `split_type` (ENUM: EQUAL, CUSTOM, PERCENTAGE) : méthode de répartition
- `receipt_key` (String, nullable) : clé du reçu sur MinIO
- `created_at` (Timestamp)
- `updated_at` (Timestamp)

**ExpenseSplit**

- `id` (UUID)
- `expense_id` (UUID, FK)
- `keycloak_id` (UUID) : participant concerné
- `amount` (BigDecimal) : montant à partager
- `percentage` (Double, nullable) : si répartition en pourcentage

**Kitty**

- `id` (UUID)
- `trip_id` (UUID, FK)
- `total_amount` (BigDecimal) : montant total dans la cagnotte
- `balance_per_member` (Map<UUID, BigDecimal>) : solde par participant
- `last_updated` (Timestamp)

**Transaction** (résultat du calcul de règlement)

- `id` (UUID)
- `trip_id` (UUID, FK)
- `from_keycloak_id` (UUID) : qui doit rembourser
- `to_keycloak_id` (UUID) : qui doit recevoir
- `amount` (BigDecimal)
- `currency` (String)
- `calculated_at` (Timestamp)
- `status` (ENUM: PENDING, COMPLETED)

## Événements (RabbitMQ)

**Publie :**

- `ExpenseCreated` — Émis lors d'une nouvelle dépense
- `ExpenseUpdated` — Émis lors de la modification d'une dépense
- `ExpenseDeleted` — Émis lors de la suppression
- `BalanceCalculated` — Émis après calcul des règlements
- `KittyUpdated` — Émis lors d'une modification de cagnotte

**Consomme :** (aucun)

## Configuration

```yaml
server:
  port: 8084
  servlet:
    context-path: /
    
spring:
  application:
    name: plantogether-expense-service
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
  datasource:
    url: jdbc:postgresql://postgres:5432/plantogether_expense
    username: ${DB_USER}
    password: ${DB_PASSWORD}
  rabbitmq:
    host: ${RABBITMQ_HOST:rabbitmq}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USER}
    password: ${RABBITMQ_PASSWORD}
  redis:
    host: ${REDIS_HOST:redis}
    port: ${REDIS_PORT:6379}

keycloak:
  serverUrl: ${KEYCLOAK_SERVER_URL:http://keycloak:8080}
  realm: ${KEYCLOAK_REALM:plantogether}
  clientId: ${KEYCLOAK_CLIENT_ID}
  
minio:
  endpoint: ${MINIO_ENDPOINT:http://minio:9000}
  accessKey: ${MINIO_ACCESS_KEY}
  secretKey: ${MINIO_SECRET_KEY}
  bucket: ${MINIO_BUCKET:plantogether}

exchange:
  rateApi: ${EXCHANGE_RATE_API_URL:https://api.exchangerate-api.com/v4/latest}
```

## Lancer en local

```bash
# Prérequis : Docker Compose (infra), Java 21+, Maven 3.9+

# Option 1 : Maven
mvn spring-boot:run

# Option 2 : Docker
docker build -t plantogether-expense-service .
docker run -p 8084:8081 \
  -e KEYCLOAK_SERVER_URL=http://host.docker.internal:8080 \
  -e DB_USER=postgres \
  -e DB_PASSWORD=postgres \
  -e MINIO_ENDPOINT=http://host.docker.internal:9000 \
  plantogether-expense-service
```

## Dépendances

- **Keycloak 24+** : authentification et autorisation
- **PostgreSQL 16** : persistance des dépenses et calculs
- **RabbitMQ** : publication d'événements
- **Redis** : cache des taux de change et des calculs de solde
- **MinIO** : stockage des reçus
- **Spring Boot 3.3.6** : framework web
- **Apache PDFBox** : génération de PDF
- **OpenCSV** : export CSV
- **Jackson** : sérialisation JSON

## Algorithme d'optimisation des transactions

L'algorithme minimise le nombre de transactions pour régler tous les soldes :

```
1. Calculer le solde net pour chaque participant
2. Créer une liste de "débiteurs" (solde négatif) et "créditeurs" (solde positif)
3. Appareiller de manière gloutonne : le plus grand débiteur avec le plus grand créditeur
4. Répéter jusqu'à tous les soldes à 0
```

Résultat : au maximum N-1 transactions pour N participants.

## Notes de sécurité

- Les dépenses ne peuvent être modifiées que par leur créateur
- Tous les endpoints requièrent authentification Keycloak
- Les reçus uploadés sont validés (taille max 10 MB, formats autorisés)
- Les taux de change sont mis en cache avec TTL de 1 heure
- Zéro PII stockée (seuls les UUIDs Keycloak)
- Export CSV/PDF ne contient pas de données sensibles (pas de noms)
