# Vanmool

Vanmool is a backend e-commerce platform that connects rural and tribal producers, artisans and makers of natural and traditional products with customers in cities. It is a set of Java / Spring Boot microservices in one repository, built as the Scaler backend capstone project and designed so it can grow into a real small business.

**Contents:** [Overview](#overview) · [Features](#features) · [Architecture](#architecture) · [Tech stack](#tech-stack) · [Getting started](#getting-started) · [API](#api) · [Events](#events) · [Testing](#testing) · [Deployment on AWS](#deployment-on-aws) · [Results](#results) · [Project structure](#project-structure) · [Limitations and future scope](#limitations-and-future-scope)

## Overview

Producers in Gujarat and similar regions make bamboo baskets, wooden toys, ragi and millet foods and other natural products, but have little digital reach. General marketplaces reduce these products to a name and a price, and lose what makes them worth buying: who made them, where, from what, and how.

Vanmool gives these producers a storefront with that story built in, and runs the whole purchase behind it: catalog and search, cart, checkout with stock reservation, payments, order tracking and email notifications.

```json
{ "name": "Handcrafted Bamboo Basket", "price": 499.99, "category": "Traditional Crafts",
  "originRegion": "Dangs, Gujarat", "materials": "Bamboo",
  "story": "Woven by hand by tribal artisans in the Dangs district, using techniques passed down in their families" }
```

## Features

- **Catalog:** products with provenance (origin, materials, story), images and specifications; search, filters, sorting and paging; categories; stock per product; product details cached in Redis.
- **Accounts and security:** signup and login with BCrypt; RS256-signed JWTs; OAuth2 / OpenID Connect (authorization code with PKCE); roles `CUSTOMER`, `SELLER` and `ADMIN`; profile and password change; password reset through a one-time emailed link; logout ends a session everywhere; rate-limited sign-in.
- **Cart and checkout:** carts in Redis, priced with current prices; checkout reserves all items' stock at once, without overselling; repeated checkouts are safe (`Idempotency-Key`); unpaid orders are cancelled after 60 minutes and their stock released.
- **Orders:** history, cancellation, and tracking (the operations team ships and delivers orders, and each order keeps its status history).
- **Payments:** Razorpay and Stripe in test mode, behind one interface; signed webhooks, callbacks and scheduled reconciliation with the gateway; one open payment per order; a fake gateway for development.
- **Notifications:** emails for sign-up, password changes and resets, orders, payments, shipping and delivery, each sent once.
- **Platform:** API gateway, service discovery, domain events on Kafka through a transactional outbox, Docker images and Docker Compose, and an AWS deployment described as code (CloudFormation).

## Architecture

```
                                   Clients
                                      │ HTTP
                                      ▼
                          API gateway (:8000) ───── Redis: sign-in rate limits, ended tokens
                                      │ routes by path, to an instance from the registry
        ┌──────────────┬──────────────┼───────────────┬────────────────┐
        ▼              ▼              ▼               ▼                ▼
   user-auth       product          cart            order           payment ──▶ Razorpay / Stripe
    (:8081)        (:8080)         (:8082)         (:8083)          (:8085)
    user_db       product_db        Redis          order_db        payment_db
                   + Redis
        │              ▲              ▲   REST by service name (Eureka, :8761)  │
        └──────────────┴──── Kafka: user-events, order-events, payment-events ──┘
                                      │
                                      ▼
                           notification ──▶ SMTP (Mailpit locally)
```

| Service | Responsibility | Data |
|---|---|---|
| `api-gateway` | Single entry point: routes by path, request ids, error format, retries, sign-in rate limits, refuses ended tokens | Redis |
| `service-discovery` | Eureka registry; every HTTP service registers and finds the others by name | |
| `user-auth-service` | Accounts, passwords, JWTs, OAuth2 / OpenID Connect, roles, password reset | MySQL `user_db` |
| `product-service` | Catalog, categories, search; stock and stock reservations for orders | MySQL `product_db`, Redis cache |
| `cart-service` | Carts of signed-in users, priced live from the product service | Redis |
| `order-service` | Checkout, order lifecycle, history, cancellation, tracking | MySQL `order_db` |
| `payment-service` | Payment links, webhooks, callbacks, reconciliation | MySQL `payment_db` |
| `notification-service` | Emails for domain events (no HTTP API) | Redis (handled event ids) |

**How the services talk.** A request that needs an answer at once (a price, a stock reservation) is a REST call by service name, resolved through Eureka with client-side load balancing, short timeouts and one retry on another instance. Something other services react to later (an order placed, a payment received) is a Kafka event. Each service owns its database; there are no cross-service tables or foreign keys, only ids.

**Key design decisions**
- **Events are never lost or invented.** A service writes its event into its own outbox table in the same transaction as the change, and a relay publishes it to Kafka. Consumers are idempotent (delivery is at least once), keep per-key order, retry, and park poison messages on their own dead-letter topic.
- **Tokens are checked locally.** Services verify JWTs with the user-auth service's public keys (JWKS, fetched by service name). A logout or password change publishes `TokenRevoked`, and the gateway refuses those tokens until they expire.
- **Stock cannot be oversold.** Each count changes in one conditional `UPDATE ... WHERE quantity >= n` inside one transaction for the whole order; reservations are keyed by order id, so retries are safe.
- **The payment gateway is the source of truth.** Webhooks are signature-checked and processed once, callbacks are verified with the gateway, and reconciliation catches lost webhooks.
- **Failures are fast and temporary failures say so.** No connection to a database within 2 s gives `503`; events wait while a database is down instead of being parked; caches and rate limits keep working without Redis (fail open).
- **Schemas are versioned.** Flyway applies each service's migrations at start, and Hibernate only validates the result.

## Tech stack

| Area | Technology |
|---|---|
| Language and framework | Java 17, Spring Boot 3.5 (Web, Data JPA, Validation, Security, Actuator) |
| Microservices | Spring Cloud 2025.0: Gateway (WebFlux), Netflix Eureka, LoadBalancer, Spring Retry |
| Data | MySQL 8 (8.4 in Docker and on AWS), Hibernate, Flyway, Redis 7.4 (Spring Data Redis, Lettuce) |
| Security | Spring Security, Spring Authorization Server (OAuth2 / OpenID Connect), BCrypt, JJWT and Nimbus JOSE (RS256) |
| Messaging and email | Apache Kafka 3.9 (KRaft, Spring for Apache Kafka), Spring Mail; Mailpit in development |
| Payments | Razorpay Java SDK, Stripe Java SDK (test mode) |
| Testing | JUnit 5, Mockito, MockMvc, WebTestClient, MockRestServiceServer, JaCoCo |
| Build and run | Maven (wrapper), Docker, Docker Compose |
| Cloud | AWS: EC2, EBS, VPC, security groups, RDS, Elastic Beanstalk, CloudFormation |

## Getting started

**Prerequisites:** JDK 17 or newer, Docker with Docker Compose 2.20 or newer, and Git. Maven is not needed (the repository has the Maven wrapper). A local MySQL 8 is needed only to run the services from an IDE.

### Run everything with Docker

```
cp .env.example .env        # then set the passwords in its last block
docker compose --profile app up -d --build
```

This builds the eight images and starts them with MySQL 8.4, Redis, Kafka and Mailpit; the first start takes about a minute until everything is healthy (`docker compose --profile app ps`).

| URL | What |
|---|---|
| http://127.0.0.1:8000 | The API, through the gateway (the only port clients use) |
| http://127.0.0.1:8761 | Eureka dashboard: registered instances |
| http://127.0.0.1:8025 | Mailpit: every email the platform sends |

Try it:

```
curl "http://127.0.0.1:8000/products?q=bamboo"
curl -X POST http://127.0.0.1:8000/auth/signup -H "Content-Type: application/json" \
     -d '{"name":"Asha Gamit","email":"asha@example.com","password":"correct horse battery"}'
curl -X POST http://127.0.0.1:8000/auth/login -H "Content-Type: application/json" \
     -d '{"email":"asha@example.com","password":"correct horse battery"}'
```

In `.env`, `PAYMENT_FAKE_GATEWAY_ENABLED=true` turns on a test checkout page so payments can be tried without a gateway account, and `JWT_PRIVATE_KEY` keeps sessions valid across restarts. `docker compose --profile app down` stops the stack; add `-v` to delete its data.

### Run the services from an IDE

1. Create the four databases and their users in your MySQL (as an administrator):
   ```sql
   CREATE DATABASE product_db;  CREATE USER 'ProductService_user'@'localhost'  IDENTIFIED BY '<password>';
   GRANT ALL PRIVILEGES ON product_db.* TO 'ProductService_user'@'localhost';
   CREATE DATABASE user_db;     CREATE USER 'UserAuthService_user'@'localhost' IDENTIFIED BY '<password>';
   GRANT ALL PRIVILEGES ON user_db.* TO 'UserAuthService_user'@'localhost';
   CREATE DATABASE order_db;    CREATE USER 'OrderService_user'@'localhost'    IDENTIFIED BY '<password>';
   GRANT ALL PRIVILEGES ON order_db.* TO 'OrderService_user'@'localhost';
   CREATE DATABASE payment_db;  CREATE USER 'PaymentService_user'@'localhost'  IDENTIFIED BY '<password>';
   GRANT ALL PRIVILEGES ON payment_db.* TO 'PaymentService_user'@'localhost';
   ```
   The tables are created by Flyway when each service starts.
2. Start the infrastructure: `docker compose up -d` (Redis, Kafka and Mailpit only).
3. Start `service-discovery` first, then the other services (IntelliJ run configurations, or `./mvnw -pl <service> spring-boot:run`), each with its `DB_PASSWORD` set.

### Configuration

Every setting comes from environment variables with local defaults; `.env.example` lists all of them. The main ones:

| Variable | Used by | Default |
|---|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | product, user-auth, order, payment | `jdbc:mysql://localhost:3306/product_db` (`user_db`, `order_db`, `payment_db`), the service's user, empty |
| `REDIS_HOST`, `KAFKA_BOOTSTRAP_SERVERS`, `EUREKA_URL` | the services that use them | `localhost`, `localhost:9092`, `http://localhost:8761/eureka/` |
| `AUTH_ISSUER` | user-auth (writes it), the others (check it) | `http://localhost:8081` |
| `JWT_PRIVATE_KEY` | user-auth | none: a key is generated at start (development only) |
| `RAZORPAY_*`, `STRIPE_*` | payment | empty: that gateway is off |
| `MAIL_HOST`, `MAIL_PORT`, `MAIL_FROM` | notification | Mailpit on `localhost:1025` |
| `DB_HOST`, `MYSQL_CONTAINER_PROFILE`, `GATEWAY_PUBLISH`, `PUBLIC_URL` | Docker Compose | the MySQL container, `127.0.0.1:8000`, `http://127.0.0.1:8000` |

## API

Clients call the gateway; the services' own ports are internal.

- **Authentication:** `Authorization: Bearer <token>` with a token from `POST /auth/login` (valid 10 hours) or the OAuth2 flow.
- **Errors** share one format everywhere, with a stable `error` code for clients and a `message` for people:
  ```json
  { "timestamp": "2026-10-25T10:05:01.667Z", "status": 404, "error": "PRODUCT_NOT_FOUND",
    "message": "Product with id 3f2c8a9e-5b1d-4c7a-9e2f-8d6b1a4c0e57 not found", "path": "/products/3f2c8a9e-5b1d-4c7a-9e2f-8d6b1a4c0e57" }
  ```
- **Request ids:** every response carries `X-Request-Id`, the client's own if it sent one.
- **Limits:** sign-in, signup and password-reset requests: 10 a minute per client address (`429`). A cart holds at most 20 of a product and 50 products.

| Method and path | Access | Purpose |
|---|---|---|
| `POST /auth/signup` · `POST /auth/login` · `POST /auth/logout` | public · public · token | Create an account · get a token · end the session |
| `POST /auth/password-reset` · `POST /auth/password-reset/confirm` | public | Email a one-time reset link · set the new password |
| `GET /users/me` · `PATCH /users/me` · `PUT /users/me/password` | token | Profile · change the name · change the password (other sessions end) |
| `PUT /users/{id}/roles/{role}` | ADMIN | Grant `SELLER` or `ADMIN` |
| `GET /products` | public | Catalog: `q`, `category`, `origin`, `minPrice`, `maxPrice`, `page`, `size`, `sort` (`name`, `price`, `createdAt`) |
| `GET /products/{id}` · `GET /categories` · `GET /products/{id}/stock` | public | Product details · categories with counts · units for sale |
| `POST /products` · `PUT`, `PATCH` (JSON Patch), `DELETE /products/{id}` | SELLER, ADMIN | List, replace, patch, remove (soft delete) a product |
| `POST /products/{id}/stock-adjustments` | SELLER, ADMIN | Add or remove units: `{ "change": 10 }` |
| `GET /cart` · `POST /cart/items` · `PUT`, `DELETE /cart/items/{productId}` · `DELETE /cart` | token | The cart, priced live · add · set or remove · empty |
| `POST /orders` | token | Checkout: the cart becomes an order with a shipping address; optional `Idempotency-Key` |
| `GET /orders` · `GET /orders/{id}` · `POST /orders/{id}/cancel` | token | History · one order with shipment and status history · cancel an unpaid order |
| `GET /admin/orders` · `POST /admin/orders/{id}/ship` · `POST .../deliver` | ADMIN | Paid orders to send · ship with carrier and tracking number · mark delivered |
| `POST /payments` · `GET /payments/{id}` | token | Start a payment for an order (returns the gateway's payment page) · payment and its history |
| `POST /payments/webhooks/{gateway}` · `GET /payments/callback/{gateway}` | gateway signature · public | Gateway notifications · the customer's return from the gateway |
| `POST /payments/reconciliation` | ADMIN | Compare open and recent payments with the gateway |

The user-auth service is also an OAuth2 / OpenID Connect authorization server (`/.well-known/openid-configuration`, `/oauth2/authorize`, `/oauth2/token`, `/oauth2/jwks`, `/userinfo`) at its own address. The first administrator is made in the database: `INSERT INTO user_role (user_id, role) SELECT id, 'ADMIN' FROM users WHERE email = '<email>';`

## Events

| Topic | Producer | Events | Consumers |
|---|---|---|---|
| `user-events` | user-auth | `UserRegistered`, `PasswordChanged`, `PasswordResetRequested`, `TokenRevoked` | notification; api-gateway (`TokenRevoked`) |
| `order-events` | order | `OrderPlaced`, `OrderCancelled`, `OrderShipped`, `OrderDelivered` | notification; product (releases stock); payment (closes the open link) |
| `payment-events` | payment | `PaymentSucceeded`, `PaymentFailed` | order (confirms the order); notification |

Each event is a JSON object starting with `eventId`, `eventType` and `occurredAt`, keyed by the user or order id so an aggregate's events stay in order. Topics are created by their owners; failed events go to `<topic>.<consumer>.DLT` with the error in the headers.

## Testing

```
./mvnw test       # unit and web layer tests: no infrastructure needed
./mvnw verify     # then the integration tests (needs MySQL, plus docker compose up -d)
./mvnw -pl product-service verify   # one service
```

| Kind | Naming | Covers |
|---|---|---|
| Unit | `*Test` | Business rules with dependencies mocked (JUnit 5, Mockito) |
| Web layer | `*WebMvcTest` | HTTP mapping, validation, JSON (asserted field by field), status codes, security rules, the error format (MockMvc) |
| Integration | `*IT` | Each service against real MySQL, Redis and Kafka: migrations, queries, caches, events end to end, signed tokens, gateway routing and failures |

**523 unit and web layer tests and 112 integration tests** across the eight modules pass. Important behaviours were also checked by breaking the code on purpose and confirming a test fails. `./mvnw test` writes a JaCoCo coverage report for each of the six business services under `target/site/jacoco`.

## Deployment on AWS

`infrastructure/aws/` holds two CloudFormation templates; one command creates each environment and one deletes it.

```
               one admin address only
Internet ─────────────────────▶ EC2 (public subnet): the Docker Compose stack, gateway on port 80
                                        │ MySQL from this security group only
Internet ──▶ Elastic Beanstalk          ▼
             product-service ──▶ RDS MySQL 8.4 (private subnets, not publicly accessible, encrypted)
```

- **`vanmool.yml`:** a VPC with public and private subnets in two zones; security groups (the server admits one address; the database admits only the application's security groups); RDS MySQL 8.4; an Ubuntu EC2 server (`m7i-flex.large`, encrypted EBS, Elastic IP) that installs Docker at first boot.
- **`vanmool-beanstalk.yml`:** product-service as a jar on Elastic Beanstalk's Java 17 platform: single instance, settings as environment variables, `/actuator/health` as its health check, against the same RDS.

Steps, with the AWS CLI signed in:
1. `aws ec2 create-key-pair --key-name vanmool-server --key-type ed25519 --query KeyMaterial --output text > vanmool-server.pem`
2. `aws cloudformation create-stack --stack-name vanmool --template-body file://infrastructure/aws/vanmool.yml --parameters file://<kept outside the repo>.json` with `AdminCidr`, `KeyName` and `DbMasterPassword`.
3. On the server, write `.env` with `MYSQL_CONTAINER_PROFILE=none`, `DB_HOST=<RDS endpoint>`, `GATEWAY_PUBLISH=80`, `PUBLIC_URL=http://<server address>`, the passwords and `JWT_PRIVATE_KEY`.
4. Create the databases on RDS with `infrastructure/mysql/init/create-databases.sh` (run from a `mysql:8.4` container with the RDS administrator login), then `docker compose --profile app up -d --build`.
5. Beanstalk: upload the product-service jar to the Beanstalk bucket and create the `vanmool-beanstalk` stack with `--capabilities CAPABILITY_IAM`.
6. Remove everything with `aws cloudformation delete-stack` for both stacks, and delete the key pair.

## Results

Measured on 2026-10-27 and 2026-10-28.

| Check | Result |
|---|---|
| Whole stack in Docker | healthy 48–74 s after `up`; 3.9 GB of memory with every container limited |
| A purchase end to end | order `CONFIRMED` 0.6 s after paying (webhook, payment event, order update), three emails sent |
| Load: 10 clients for 60 s through the gateway | 546 requests a second, median 13–19 ms, p95 29–44 ms; after a gateway fix, two runs with 61,233 requests in total and none failed |
| Database outage | `503` within about 2 s, events wait and are handled when MySQL returns; recovery in 26 s without restarts |
| Kafka outage during an order | the order is placed; its email is sent once Kafka is back |
| AWS (Sydney) | stack in 8.4 min; services healthy 150 s after start; a full purchase through the public address; RDS unreachable from the internet and open to the server; Beanstalk environment Green in 2 min |

## Project structure

```
vanmool/
├── pom.xml                  Maven aggregator: one command builds every service
├── docker-compose.yml       infrastructure, and with --profile app the whole stack
├── .env.example             every setting, with placeholders
├── infrastructure/
│   ├── mysql/init/          creates the databases and their users
│   └── aws/                 CloudFormation templates
├── api-gateway/  service-discovery/  user-auth-service/  product-service/
├── cart-service/  order-service/  payment-service/  notification-service/
│
└── <service>/               each one an independent Spring Boot application
    ├── Dockerfile
    ├── pom.xml
    └── src/
        ├── main/java/com/vanmool/<service>/
        │   ├── controllers/  services/  repositories/  models/  dtos/     (as each service needs)
        │   ├── configs/  events/  clients/  exceptions/
        └── main/resources/
            ├── application.properties
            └── db/migration/   Flyway scripts
```

## Limitations and future scope

**Current limitations**
- Payments were run end to end with the fake gateway; Razorpay and Stripe are integrated and tested in test mode without live accounts. Refunds are logged for manual handling.
- Any seller can change any product: products are not yet linked to the seller who listed them.
- One instance of each service; the AWS environment is a demonstration (HTTP by address, one admitted client).
- Google sign-in, refresh tokens and metrics dashboards are not built.

**Future scope**
- Producer onboarding and tools for producers to manage their listings
- An assistant that drafts product descriptions and stories from a producer's notes in Gujarati or Hindi
- Richer search and recommendations
- Logistics integration with carriers' APIs
- Web and mobile clients
