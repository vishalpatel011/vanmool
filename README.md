# Vanmool

Vanmool is a backend e-commerce platform that connects rural and tribal producers, artisans and makers of natural and traditional products with customers in cities.

It is built as Java / Spring Boot microservices in a single repository, as the Scaler backend capstone project, and is designed so that it can grow into a real small business.

## The problem

Producers in rural and tribal regions of Gujarat and similar areas make wooden crafts and toys, bamboo baskets, ragi and millet foods and other natural products. They have limited digital reach and only fragmented access to customers in cities. General-purpose marketplaces reduce these products to a name and a price, and lose what makes them worth buying: who made them, where, from what, and how.

## The solution

Vanmool gives these producers a structured digital storefront, and covers the full purchase flow behind it: catalog and search, cart, checkout, inventory, payments and notifications.

Products are meant to carry their origin and story, not just catalog data:

| Field | Example |
|---|---|
| Product | Handcrafted Bamboo Basket |
| Category | Traditional Crafts |
| Origin | Dangs, Gujarat |
| Producer | Local artisan cooperative |
| Materials | Bamboo |
| Story | Woven by hand using traditional techniques |

The catalog stores each product's name, description, price, image and category, and its provenance: origin region, materials and the maker's story. The producer will be linked to the seller's own account once the user service exists.

## Architecture

Vanmool is split into services along business boundaries. Each service is an independent Spring Boot application that can be built, run and deployed on its own.

- **Database per service.** Each service owns its data (for example, `product_db` for the product service). No service reads or writes another service's tables, and there are no cross-service foreign keys; services refer to each other's data by id.
- **REST** for requests that need an answer immediately, such as the order service asking the product service for a current price.
- **Kafka** for domain events that other services react to asynchronously, such as `OrderCreated` or `PaymentSucceeded`.
- **API Gateway and Eureka** give clients a single entry point and let services find each other without hardcoded addresses.

Target architecture (services are added one at a time; the table below shows what exists today):

```
                      Client
                        |
                   API Gateway  ----  Service Discovery (Eureka)
                        |
     +---------+--------+--+-----------+---------+
     |         |           |           |         |
   Auth     Product    Inventory     Cart      Order
     |         |           |           |         |
   MySQL     MySQL       MySQL       Redis     MySQL
                                                 |
                                               Kafka
                                                 |
                                   +-------------+-------------+
                                   |                           |
                                Payment                  Notification
                                   |
                         Razorpay / Stripe
```

| Service | Responsibility | Storage | Status |
|---|---|---|---|
| `product-service` | Product catalog: products, categories, search, pagination | MySQL (`product_db`) | In progress |
| `user-auth-service` | Signup, login, password hashing, JWT, roles | MySQL (`user_db`) | In progress |
| `inventory-service` | Stock levels, reservation and release | MySQL | Planned |
| `cart-service` | Shopping carts | Redis | Planned |
| `order-service` | Orders and the order lifecycle | MySQL | Planned |
| `payment-service` | Payments, webhooks and reconciliation (Razorpay, Stripe) | MySQL | Planned |
| `notification-service` | Order and payment notifications from Kafka events | | Planned |
| `api-gateway` | Single entry point and request routing | | Planned |
| `service-discovery` | Eureka service registry | | Planned |

## Tech stack

| Technology | Purpose | Status |
|---|---|---|
| Java 17 | Language | In use |
| Spring Boot 3.5 (Web, Data JPA) | REST APIs and persistence | In use |
| Hibernate / JPA | Object-relational mapping | In use |
| MySQL 8 | Relational database | In use |
| Flyway | Versioned database migrations | In use |
| Bean Validation (Hibernate Validator) | Request validation | In use |
| json-patch | JSON Patch support for `PATCH` requests | In use |
| Lombok | Boilerplate reduction | In use |
| JUnit 5, Mockito | Unit tests with mocked dependencies | In use |
| JaCoCo | Code coverage report | In use |
| Maven (wrapper included) | Build | In use |
| Spring Security, BCrypt | Password hashing, authentication and authorization | In use |
| JWT (JJWT, RS256) | Signed login tokens | In use |
| Spring Authorization Server | OAuth2 / OpenID Connect authorization code flow with PKCE | In use |
| Redis | Product cache and cart storage | Planned |
| Apache Kafka | Domain events between services | Planned |
| Spring Cloud Gateway, Eureka, RestTemplate | Routing, service discovery and calls between services | Planned |
| Razorpay, Stripe | Payments | Planned |
| Docker | Containers and local infrastructure | Planned |
| AWS (EC2, Elastic Beanstalk, VPC, RDS) | Deployment | Planned |

## Repository structure

```
vanmool/
├── pom.xml              Maven aggregator: builds every service
├── mvnw, mvnw.cmd       Maven wrapper
├── .mvn/
├── .env.example         environment variables the services read
├── user-auth-service/   accounts, login and tokens (same layout as product-service)
└── product-service/     product catalog service
    ├── pom.xml
    └── src/
        ├── main/java/com/vanmool/productservice/
        │   ├── controllers/    REST endpoints
        │   ├── services/       business logic (ProductService and its database implementation, CategoryService)
        │   ├── repositories/   Spring Data JPA repositories
        │   ├── models/         JPA entities
        │   ├── dtos/           request and response objects
        │   ├── projections/    query results that hold only the selected columns
        │   └── exceptions/     custom exceptions and the global exception handler
        ├── main/resources/
        │   ├── application.properties
        │   └── db/migration/   Flyway migration scripts (V1__..., V2__...)
        └── test/
```

Each service has its own `pom.xml` with the Spring Boot parent. The root `pom.xml` only lists the services as modules, so one command builds all of them, and each service can still be built, packaged and deployed on its own.

## Getting started

### Prerequisites

- JDK 17 or newer
- MySQL 8
- Maven is not required; the repository includes the Maven wrapper.

### 1. Create the database

Run as a MySQL administrator, using your own user name and password:

Each service has its own database and its own database user, which can reach only that database:

```sql
CREATE DATABASE product_db;
CREATE USER 'vanmool_user'@'localhost' IDENTIFIED BY 'choose-a-password';
GRANT ALL PRIVILEGES ON product_db.* TO 'vanmool_user'@'localhost';

CREATE DATABASE user_db;
CREATE USER 'vanmool_auth_user'@'localhost' IDENTIFIED BY 'choose-another-password';
GRANT ALL PRIVILEGES ON user_db.* TO 'vanmool_auth_user'@'localhost';
```

The tables are created by Flyway, not by hand and not by Hibernate. On startup Flyway applies every script in the service's `src/main/resources/db/migration` that the database has not seen yet, and records it in the `flyway_schema_history` table. Hibernate then only checks that the tables match the entities (`spring.jpa.hibernate.ddl-auto=validate`) and stops the application if they do not.

A schema change is always a new script with the next version number, named `V<version>__<description>.sql` (for example `V1__create_category_and_product_tables.sql`). A script that has already been applied is never edited: Flyway stores a checksum of each script and refuses to start if an applied one changes.

### 2. Set the environment variables

Each service reads its settings from the environment of its own process, so the same variable names are used by every service. `.env.example` lists them:

| Variable | Description | product-service default | user-auth-service default |
|---|---|---|---|
| `DB_URL` | JDBC URL of the service's database | `jdbc:mysql://localhost:3306/product_db` | `jdbc:mysql://localhost:3306/user_db` |
| `DB_USERNAME` | Database user | `ProductService_user` | `UserAuthService_user` |
| `DB_PASSWORD` | Database password | empty | empty |
| `SERVER_PORT` | HTTP port | `8080` | `8081` |

- **IntelliJ IDEA:** Run > Edit Configurations > `ProductServiceApplication` (or `UserAuthServiceApplication`) > Environment variables, for example `DB_USERNAME=vanmool_user;DB_PASSWORD=choose-a-password`.
- **Shell:** export the variables before running, for example `export DB_PASSWORD=choose-a-password`.

Never commit real credentials. `.env` is git-ignored for this reason.

### 3. Build and test

```
./mvnw test            # unit tests only: fast, no database needed
./mvnw verify          # unit tests, then integration tests against MySQL (Git Bash, macOS, Linux)
.\mvnw.cmd verify      # the same from Windows PowerShell or cmd
```

To build a single service: `./mvnw -pl product-service verify` or `./mvnw -pl user-auth-service verify`.

### 4. Run

- **IntelliJ IDEA:** open the `vanmool` folder and run `ProductServiceApplication` or `UserAuthServiceApplication`.
- **Command line:** `./mvnw -pl product-service spring-boot:run` or `./mvnw -pl user-auth-service spring-boot:run`

The product service listens on `http://localhost:8080` and the user-auth service on `http://localhost:8081`, so both can run at once.

## Product Service API

| Method | Path | Success | Errors |
|---|---|---|---|
| GET | `/products` | 200, list of products | |
| GET | `/products?category={name}` | 200, the products in that category (empty list if none) | |
| GET | `/products/{id}` | 200, the product | 400 invalid id, 404 product not found |
| POST | `/products` | 201, the created product, with `Location: /products/{id}` | 400 invalid request body |
| PUT | `/products/{id}` | 200, the replaced product | 400 invalid id or request body, 404 product not found |
| PATCH | `/products/{id}` | 200, the patched product | 400 invalid id, patch or patched product, 404 product not found |
| DELETE | `/products/{id}` | 204, no body | 400 invalid id, 404 product not found |
| GET | `/categories` | 200, every category with its number of products | |

Product ids are UUIDs generated by the service, for example `/products/3f2c8a9e-5b1d-4c7a-9e2f-8d6b1a4c0e57`. An id that is not a valid UUID returns 400.

`GET /products?category=Natural%20Foods` lists only the products in that category. The match ignores case, an unknown category returns an empty list, and leaving the parameter out (or empty) lists every product.

`GET /categories` lists the categories in alphabetical order with how many products each one has. Deleted products are not counted, and a category whose products were all deleted is listed with `0`:

```json
[{ "name": "Natural Foods", "productCount": 1 }, { "name": "Traditional Crafts", "productCount": 1 }]
```

Request body for `POST` and `PUT`:

```json
{
  "name": "Handcrafted Bamboo Basket",
  "price": 450,
  "description": "A round basket for fruit or bread, about 30 cm across",
  "imageUrl": "https://example.com/images/bamboo-basket.jpg",
  "category": "Traditional Crafts",
  "originRegion": "Dangs, Gujarat",
  "materials": "Bamboo",
  "story": "Woven by hand by tribal artisans in the Dangs district, using techniques passed down in their families"
}
```

Request bodies are validated before anything is saved:

| Field | Rules |
|---|---|
| `name` | required, not blank, at most 255 characters |
| `price` | required, greater than 0, at most 8 digits before and 2 after the decimal point |
| `description`, `story` | optional, at most 2000 characters each |
| `imageUrl`, `category`, `originRegion`, `materials` | optional, at most 255 characters each |

A request that breaks a rule gets 400 with every failing field in the message (`"error": "VALIDATION_FAILED"`, `"message": "name: must not be blank; price: must be greater than 0"`).

A new category name creates the category; an existing name reuses it. `PUT` replaces every field of an existing product. It does not create a product at a client-chosen id, because ids are generated by the database.

`PATCH` takes a JSON Patch document (`Content-Type: application/json-patch+json`), applied to the product as `GET` returns it. The patched product must pass the same rules, and the id cannot be changed:

```json
[{ "op": "replace", "path": "/price", "value": 550 }]
```

`DELETE` is a soft delete: the row stays in the database with its `deleted` flag set, and the product no longer appears in any response. Other services will refer to products by id (an order keeps the ids of the products it contains), so a product row is never physically removed.

Every error, including the ones Spring itself raises (unknown path, wrong method, wrong `Content-Type`), has the same shape:

```json
{
  "timestamp": "2026-10-25T10:05:01.667Z",
  "status": 404,
  "error": "PRODUCT_NOT_FOUND",
  "message": "Product with id 3f2c8a9e-5b1d-4c7a-9e2f-8d6b1a4c0e57 not found",
  "path": "/products/3f2c8a9e-5b1d-4c7a-9e2f-8d6b1a4c0e57"
}
```

`error` is a fixed code that clients can check; `message` is for people and may change.

| `error` | Status | When |
|---|---|---|
| `PRODUCT_NOT_FOUND` | 404 | No product with that id, or it was deleted |
| `VALIDATION_FAILED` | 400 | A request body, or the result of a `PATCH`, breaks a validation rule |
| `INVALID_PARAMETER` | 400 | A path or query value has the wrong type, such as an id that is not a UUID |
| `MALFORMED_REQUEST` | 400 | The body is missing or is not valid JSON for the request |
| `INVALID_PATCH` | 400 | The JSON Patch cannot be applied, for example a path that does not exist |
| `NOT_FOUND`, `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE`, ... | 404, 405, 415, ... | Errors raised by Spring before a controller runs; the code is the HTTP status name |
| `INTERNAL_ERROR` | 500 | An unexpected server error. The details are logged on the server, never sent to the client |

## User Auth Service API

The user-auth service (port 8081) owns user accounts. It never stores or returns a password: only a BCrypt hash is kept.

| Method | Path | Success | Errors |
|---|---|---|---|
| POST | `/auth/signup` | 201, the new user | 400 invalid request body, 409 email already registered |
| POST | `/auth/login` | 200, a bearer token and its expiry | 400 invalid request body, 401 email or password incorrect |
| POST | `/auth/logout` | 204, the token stops working | 401 missing or malformed `Authorization` header |
| GET | `/auth/validate` | 200, the token's user | 401 token missing, invalid, expired or revoked |
| GET | `/users/me` | 200, the signed-in user | 401 token missing, invalid, expired or revoked |
| PUT | `/users/{id}/roles/{role}` | 200, the user with the role added (ADMIN only) | 400 unknown role, 401 no valid token, 403 not an ADMIN, 404 user not found |

Request body for `POST /auth/signup`:

```json
{ "name": "Asha Gamit", "email": "asha@example.com", "password": "correct horse battery" }
```

| Field | Rules |
|---|---|
| `name` | required, not blank, at most 100 characters |
| `email` | required, a valid email address, at most 255 characters; stored in lower case, so one address is one account |
| `password` | required, at least 8 characters and at most 72 bytes (BCrypt's limit; a Gujarati or Hindi letter takes 3 bytes) |

Response, without the password:

```json
{ "id": "5b0e8f3c-2a41-4c47-9d0a-6f1c2e7b9a10", "name": "Asha Gamit", "email": "asha@example.com", "roles": ["CUSTOMER"] }
```

Every new account has the `CUSTOMER` role.

`POST /auth/login` takes `{ "email": "...", "password": "..." }` and answers:

```json
{ "token": "5m9ew8_eJUU4RFqV69tOEITqm-Fa7SwkZi4ToZLFsZ0", "tokenType": "Bearer", "expiresAt": "2026-10-25T22:03:30Z" }
```

The client sends the token on later requests as `Authorization: Bearer <token>`. The token is a JWT signed with RS256: its header names the signing key (`kid`), and its payload carries the issuer, the user's id (`sub`), the token's id (`jti`), email, roles, and issue and expiry times. Tokens are valid for 10 hours (`auth.token-validity`) unless the user logs out first: logout marks the token's `jti` as revoked, and both `/auth/validate` and protected endpoints such as `/users/me` refuse it from then on.

Passwords are checked by Spring Security (a `UserDetailsService` that reads `user_db`, and the BCrypt `PasswordEncoder`). A wrong password and an unknown email get the same `401 INVALID_CREDENTIALS` answer, after the same BCrypt work, so the API does not reveal which emails have accounts.

Roles: every new account is a `CUSTOMER`. An `ADMIN` can grant `SELLER` (for producers who list products) or `ADMIN` with `PUT /users/{id}/roles/{role}`. The first administrator is made directly in the database:

```sql
INSERT INTO user_role (user_id, role) SELECT id, 'ADMIN' FROM users WHERE email = 'admin@example.com';
```

Errors use the same format as the product service (`timestamp`, `status`, `error`, `message`, `path`). Codes: `EMAIL_ALREADY_REGISTERED` (409), `INVALID_CREDENTIALS` (401), `INVALID_TOKEN` (401, with `WWW-Authenticate: Bearer`), `FORBIDDEN` (403), `USER_NOT_FOUND` (404), `VALIDATION_FAILED`, `INVALID_PARAMETER` and `MALFORMED_REQUEST` (400).

### OAuth2 and OpenID Connect

The service is also an OAuth2 Authorization Server with OpenID Connect (Spring Authorization Server), so an application can get tokens for a user without ever handling the user's password: the user signs in on this service's own page.

| Endpoint | Purpose |
|---|---|
| `GET /.well-known/openid-configuration` | Discovery: the issuer and every endpoint below |
| `GET /oauth2/authorize` | Starts the sign-in; sends a person who is not signed in to the form at `/login` |
| `POST /oauth2/token` | Exchanges an authorization code for an access token and an ID token |
| `GET /oauth2/jwks` | The public key (JWK set) that checks every token this service signs |
| `GET /userinfo` | Who the access token belongs to (OpenID Connect) |

One client is registered, `vanmool-web` (the future web or mobile front end). It is a public client, so it uses the authorization code flow with PKCE (`code_challenge_method=S256`); its redirect URIs are set by `WEB_CLIENT_REDIRECT_URIS`. Access tokens from `/oauth2/token` carry the same `sub`, `email` and `roles` claims as tokens from `POST /auth/login`, and both are signed with the same RSA key, so another service can check either kind with the public key from `/oauth2/jwks`.

Configuration:

| Variable | Description | Default |
|---|---|---|
| `JWT_PRIVATE_KEY` | RSA private key that signs all tokens, as a PKCS#8 PEM or its Base64 on one line (`openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048`). Every instance must use the same key | none: a key is generated at startup, for local development only (tokens stop working on restart) |
| `AUTH_ISSUER` | The issuer written into tokens and published in the discovery document; the public URL in a deployment | `http://localhost:8081` |
| `WEB_CLIENT_REDIRECT_URIS` | Comma-separated redirect URIs of `vanmool-web` | `http://127.0.0.1:3000/callback,https://oauth.pstmn.io/v1/callback` |

## Testing

Tests follow the testing pyramid: many fast unit tests, a few slower integration tests.

| Kind | Naming | Runs in | Needs | What it covers |
|---|---|---|---|---|
| Unit tests | `*Test` | `./mvnw test` (Maven Surefire) | Only the JVM | Services and controllers as plain Java classes, with their dependencies replaced by Mockito mocks |
| Web layer tests | `*WebMvcTest` | `./mvnw test` (Maven Surefire) | Only the JVM | Real HTTP requests through Spring MVC with MockMvc (`@WebMvcTest`): routing, JSON in and out, validation, status codes, headers and the error format, with the service replaced by a mock (`@MockitoBean`) |
| Integration tests | `*IT` | `./mvnw verify` (Maven Failsafe) | A running MySQL (`DB_URL`) | The whole application starting against the database: Flyway's migrations apply and Hibernate accepts the schema |

Unit tests use JUnit 5 and Mockito, follow Arrange-Act-Assert, and sit in packages that mirror the main code (`services/ProductDbServiceTest` tests `services/ProductDbService`). They cover the happy path, edge cases and failure cases of each business rule: a `PUT` never creates a product, a `PATCH` cannot change the id and must still pass validation, a delete only sets the `deleted` flag, and finding or creating a category recovers when two requests create the same new name at once.

Web layer tests check the JSON field by field with `jsonPath`, or against the whole expected body with `content().json(..., STRICT)`. The expected JSON is written out in the test rather than produced by the same serializer the application uses.

`./mvnw test` also writes a JaCoCo code coverage report to `product-service/target/site/jacoco/index.html`. Lombok-generated getters and setters are left out of it (`product-service/lombok.config`).

## Roadmap

- [x] REST API for products with correct HTTP methods and status codes
- [x] Centralized exception handling with `@RestControllerAdvice`
- [x] Third-party API integration with `RestTemplate`: the first version of the service read and wrote products through the public FakeStore API, behind the same `ProductService` interface, before the database implementation replaced it
- [x] Persistence with Spring Data JPA, Hibernate and MySQL
- [x] Database settings read from environment variables
- [x] Request validation with Bean Validation
- [x] UUID identifiers; shared entity fields through a `@MappedSuperclass`
- [x] Database migrations with Flyway
- [x] Derived query methods, JPQL queries and DTO projections
- [x] Fetch types: LAZY associations, entity graphs instead of N+1 queries, transactional writes, open-in-view off
- [x] Unit tests with JUnit 5 and Mockito, unit and integration tests run separately, JaCoCo coverage
- [x] MockMvc web layer tests with JSON assertions
- [x] User Auth Service: signup and login with BCrypt, JWT, Spring Security, OAuth2 / OpenID Connect
- [ ] AWS deployment: EC2, Elastic Beanstalk, VPC, RDS
- [ ] Redis: product caching and cart storage
- [ ] Product search, pagination, sorting and filtering
- [ ] Kafka domain events
- [ ] Payment Service: Razorpay and Stripe, webhooks, reconciliation
- [ ] Inventory, Cart, Order and Notification services
- [ ] Spring Cloud: Eureka service discovery and API Gateway
- [ ] Docker and Docker Compose

## Future scope

- Producer onboarding and tools for producers to manage their own listings
- Richer product search and recommendations
- Logistics and shipping integration
- Web and mobile clients
