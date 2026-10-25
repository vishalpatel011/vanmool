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
| `product-service` | Product catalog: products, categories, search, pagination; stock and stock reservations for orders | MySQL (`product_db`), Redis (cache) | In progress |
| `user-auth-service` | Signup, login, password hashing, JWT, roles | MySQL (`user_db`) | In progress |
| `inventory-service` | Stock levels, reservation and release | MySQL | Inside `product-service` for now (own tables and endpoints) |
| `cart-service` | Shopping carts of signed-in users, priced with current product prices | Redis | In progress |
| `order-service` | Checkout from the cart, order history, the order lifecycle and cancellation | MySQL (`order_db`) | In progress |
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
| Redis 7 (Spring Data Redis, Lettuce) | Product cache | In use |
| Redis | Cart storage | In use |
| Apache Kafka 3.9 (Spring for Apache Kafka) | Domain events between services, sent through a transactional outbox | In use |
| Mailpit | Catches the emails sent in development and shows them in a web page | In use |
| RestClient | Calls between services (cart to product), with timeouts | In use |
| Spring Cloud Gateway, Eureka | Routing and service discovery | Planned |
| Razorpay, Stripe | Payments | Planned |
| Docker Compose | Local infrastructure (Redis, Kafka, Mailpit) | In use |
| Docker | Containers for the services | Planned |
| AWS (EC2, Elastic Beanstalk, VPC, RDS) | Deployment | Planned |

## Repository structure

```
vanmool/
├── pom.xml              Maven aggregator: builds every service
├── mvnw, mvnw.cmd       Maven wrapper
├── .mvn/
├── .env.example         environment variables the services read
├── docker-compose.yml   local infrastructure: Redis, Kafka, Mailpit
├── user-auth-service/   accounts, login and tokens (same layout as product-service)
├── cart-service/       shopping carts in Redis (same layout; clients/ holds the call to product-service)
├── order-service/      orders (same layout; clients/ holds the calls to cart-service and product-service)
└── product-service/     product catalog service
    ├── pom.xml
    └── src/
        ├── main/java/com/vanmool/productservice/
        │   ├── configs/        Spring configuration (the Redis product cache)
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
- Docker, for Redis, Kafka and Mailpit (`docker compose`)
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

CREATE DATABASE order_db;
CREATE USER 'vanmool_order_user'@'localhost' IDENTIFIED BY 'choose-a-third-password';
GRANT ALL PRIVILEGES ON order_db.* TO 'vanmool_order_user'@'localhost';
```

The tables are created by Flyway, not by hand and not by Hibernate. On startup Flyway applies every script in the service's `src/main/resources/db/migration` that the database has not seen yet, and records it in the `flyway_schema_history` table. Hibernate then only checks that the tables match the entities (`spring.jpa.hibernate.ddl-auto=validate`) and stops the application if they do not.

A schema change is always a new script with the next version number, named `V<version>__<description>.sql` (for example `V1__create_category_and_product_tables.sql`). A script that has already been applied is never edited: Flyway stores a checksum of each script and refuses to start if an applied one changes.

### 2. Start Redis, Kafka and Mailpit

```
docker compose up -d
```

This starts, reachable from this machine only:

- **Redis 7** on `localhost:6379`. Its data is kept in a Docker volume (`redis-data`) and written to disk once a second (append-only file), so it survives a restart of the container.
- **Kafka 3.9** on `localhost:9092`: one broker in KRaft mode (no ZooKeeper), data in the `kafka-data` volume. Topics are not created automatically; each service declares the topics it owns when it starts.
- **Mailpit**: an SMTP server on `localhost:1025` that keeps every email instead of delivering it, with a web page at http://localhost:8025 to read them.

`docker compose stop` stops them; `docker compose down -v` also deletes their data.

### 3. Set the environment variables

Each service reads its settings from the environment of its own process, so the same variable names are used by every service. `.env.example` lists them:

| Variable | Description | product-service default | user-auth-service default | cart-service default | order-service default |
|---|---|---|---|---|---|
| `DB_URL` | JDBC URL of the service's database | `jdbc:mysql://localhost:3306/product_db` | `jdbc:mysql://localhost:3306/user_db` | not used | `jdbc:mysql://localhost:3306/order_db` |
| `DB_USERNAME` | Database user | `ProductService_user` | `UserAuthService_user` | not used | `OrderService_user` |
| `DB_PASSWORD` | Database password | empty | empty | not used | empty |
| `SERVER_PORT` | HTTP port | `8080` | `8081` | `8082` | `8083` |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka broker addresses | not used | `localhost:9092` | not used | not used |
| `REDIS_HOST`, `REDIS_PORT` | Redis server | `localhost`, `6379` | not used | `localhost`, `6379` | not used |
| `REDIS_PASSWORD` | Redis password, if the server requires one | empty | not used | empty | not used |
| `PRODUCT_CACHE_TTL` | Longest time a product stays cached (ISO-8601 duration) | `PT10M` (10 minutes) | not used | not used | not used |
| `AUTH_ISSUER` | Issuer of the tokens: user-auth-service's public URL | not used | `http://localhost:8081` | `http://localhost:8081` | `http://localhost:8081` |
| `AUTH_JWKS_URI` | Where user-auth-service publishes its public keys | not used | not used | `http://localhost:8081/oauth2/jwks` | `http://localhost:8081/oauth2/jwks` |
| `PRODUCT_SERVICE_URL` | Base URL of product-service | not used | not used | `http://localhost:8080` | `http://localhost:8080` |
| `CART_TTL` | A cart untouched for this long is deleted (ISO-8601 duration) | not used | not used | `P30D` (30 days) | not used |
| `CART_SERVICE_URL` | Base URL of cart-service | not used | not used | not used | `http://localhost:8082` |

- **IntelliJ IDEA:** Run > Edit Configurations > `ProductServiceApplication` (or `UserAuthServiceApplication`, `CartServiceApplication`, `OrderServiceApplication`) > Environment variables, for example `DB_USERNAME=vanmool_user;DB_PASSWORD=choose-a-password`.
- **Shell:** export the variables before running, for example `export DB_PASSWORD=choose-a-password`.

Never commit real credentials. `.env` is git-ignored for this reason.

### 4. Build and test

```
./mvnw test            # unit tests only: fast, no database needed
./mvnw verify          # unit tests, then integration tests against MySQL and Redis (Git Bash, macOS, Linux)
.\mvnw.cmd verify      # the same from Windows PowerShell or cmd
```

To build a single service: `./mvnw -pl product-service verify`, `./mvnw -pl user-auth-service verify`, `./mvnw -pl cart-service verify` or `./mvnw -pl order-service verify`.

### 5. Run

- **IntelliJ IDEA:** open the `vanmool` folder and run `ProductServiceApplication`, `UserAuthServiceApplication`, `CartServiceApplication` or `OrderServiceApplication`.
- **Command line:** `./mvnw -pl product-service spring-boot:run` (likewise `user-auth-service`, `cart-service`, `order-service`)

The product service listens on `http://localhost:8080`, the user-auth service on `http://localhost:8081`, the cart service on `http://localhost:8082` and the order service on `http://localhost:8083`, so all of them can run at once. The cart service needs Redis, tokens from the user-auth service, and the product service for prices; it starts without the other two and contacts them on the first request that needs them.

## Product Service API

| Method | Path | Success | Errors |
|---|---|---|---|
| GET | `/products` | 200, one page of products: search, filters, paging and sorting | 400 invalid filter value or sort field |
| GET | `/products/{id}` | 200, the product | 400 invalid id, 404 product not found |
| POST | `/products` | 201, the created product, with `Location: /products/{id}` | 400 invalid request body |
| PUT | `/products/{id}` | 200, the replaced product | 400 invalid id or request body, 404 product not found |
| PATCH | `/products/{id}` | 200, the patched product | 400 invalid id, patch or patched product, 404 product not found |
| DELETE | `/products/{id}` | 204, no body | 400 invalid id, 404 product not found |
| GET | `/categories` | 200, every category with its number of products | |
| GET | `/products/{id}/stock` | 200, how many units can be sold now | 400 invalid id, 404 product not found |
| POST | `/products/{id}/stock-adjustments` | 200, the new stock count | 400 invalid change, 404 product not found, 409 not enough units to remove |
| POST | `/stock-reservations` | 201, the reservation with each product's name and price (200 if this order's reservation already exists) | 400 invalid request, 404 product not found, 409 not enough stock or a different reservation for this order |
| DELETE | `/stock-reservations/{orderId}` | 204, the order's units are back in stock | 400 invalid id |

Product ids are UUIDs generated by the service, for example `/products/3f2c8a9e-5b1d-4c7a-9e2f-8d6b1a4c0e57`. An id that is not a valid UUID returns 400.

`GET /products` is the catalog. Every query parameter is optional and they combine:

| Parameter | Meaning | Example |
|---|---|---|
| `q` | Text found in the name, description, materials, origin region or story | `q=bamboo` |
| `category` | Exact category name | `category=Traditional%20Crafts` |
| `origin` | Part of the origin region | `origin=Dangs` |
| `minPrice`, `maxPrice` | Price range, inclusive | `minPrice=100&maxPrice=600` |
| `page` | Page number, starting at 0 | `page=1` |
| `size` | Products per page: 20 by default, at most 100 | `size=10` |
| `sort` | `name`, `price` or `createdAt`, then `asc` or `desc`; repeatable. Default `createdAt,desc` (newest first) | `sort=price,asc` |

Matching ignores letter case. `%` and `_` in `q` or `origin` are searched for literally. Deleted products never appear. Products with the same sort value always come in the same order, so paging never shows a product twice or skips one.

The response is one page:

```json
{
  "content": [ { "id": "150dac81-6130-4903-9385-9b682db879b3", "name": "Handcrafted Bamboo Basket", "price": 499.99, "category": "Traditional Crafts", "originRegion": "Dangs, Gujarat", "...": "..." } ],
  "page": { "size": 20, "number": 0, "totalElements": 1, "totalPages": 1 }
}
```

Sorting by any other field, or a `minPrice` that is not a number, returns 400 `INVALID_PARAMETER`.

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
| `INSUFFICIENT_STOCK` | 409 | A reservation or a stock removal needs more units than there are |
| `RESERVATION_CONFLICT` | 409 | The order already has a reservation with other items, or it was released |
| `NOT_FOUND`, `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE`, ... | 404, 405, 415, ... | Errors raised by Spring before a controller runs; the code is the HTTP status name |
| `INTERNAL_ERROR` | 500 | An unexpected server error. The details are logged on the server, never sent to the client |

### Stock

Each product has a number of units that can still be sold, kept in its own `stock` table rather than in the product row: it changes with every order, while the product's description is cached. Stock is never part of the cached product JSON; `GET /products/{id}/stock` always reads MySQL. A product without stock has 0 units.

- **Adjustments are changes, not totals.** `POST /products/{id}/stock-adjustments` with `{ "change": 10 }` adds 10 units (a producer delivers 10 more baskets); `{ "change": -2 }` removes 2 (damaged). A change never overwrites units that orders took in the meantime. Removing more units than there are gets 409 `INSUFFICIENT_STOCK`.
- **Reservations are for orders.** The order service calls `POST /stock-reservations` when an order is placed:

  ```json
  { "orderId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
    "items": [ { "productId": "150dac81-6130-4903-9385-9b682db879b3", "quantity": 2 } ] }
  ```

  The answer lists each product's name and current price; the order is placed at exactly these prices. Every product's units are taken in one database transaction: if one product has too few, nothing is taken and the answer is 409 `INSUFFICIENT_STOCK`, naming the products.
- **No overselling.** Each unit count changes in a single `UPDATE ... WHERE quantity >= n`, which MySQL runs one at a time per product. When many orders want the last units at once, exactly as many succeed as there are units; a `CHECK (quantity >= 0)` constraint guards the table as well. Rows are always locked in the same order, so two orders for the same products cannot deadlock.
- **Safe to repeat.** A reservation is keyed by the order id. Sending the same request again returns the first reservation (200) without taking more units; the same order id with other items is 409 `RESERVATION_CONFLICT`. `DELETE /stock-reservations/{orderId}` puts the units back once, however many times it is called, and answers 204 also for an order that never reserved anything.

### Product cache (Redis)

`GET /products/{id}` is served from Redis when it can be. The first request for a product reads MySQL and stores the response under the key `product:{id}`, as the same JSON the API returns; later requests for that product read only Redis.

- **Changes remove the entry.** `PUT`, `PATCH` and `DELETE` remove the product's key once their database transaction has committed. A change that fails or is rolled back leaves the entry as it was, because MySQL still holds the old product.
- **Entries expire after 10 minutes** (`PRODUCT_CACHE_TTL`). This bounds how long a change made outside the API, for example directly in MySQL, can stay invisible.
- **"Not found" is not cached**, so a product is never hidden by an earlier miss.
- **Redis is optional at run time.** If Redis is down, the failed cache call is logged as a warning and the request is answered from MySQL. While the connection is down, commands are rejected at once instead of waiting for a timeout, so requests are not slowed down; the cache is used again as soon as the connection is back. An entry that could not be removed during an outage can be stale for at most the 10 minutes above.
- **Only single products are cached.** Catalog pages (`GET /products`) always come from MySQL: they have too many combinations of filters, and every product change would have to remove all of them.

```
docker compose exec redis redis-cli --scan --pattern 'product:*'
docker compose exec redis redis-cli GET product:150dac81-6130-4903-9385-9b682db879b3
docker compose exec redis redis-cli TTL product:150dac81-6130-4903-9385-9b682db879b3
```

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

## Cart Service API

The cart service (port 8082) keeps the shopping cart of each signed-in user in Redis. Every request needs a token from the user-auth service (`Authorization: Bearer <token>`, from `POST /auth/login` or the OAuth2 flow). There is no user id in the paths: the cart is always the one of the user the token belongs to (`sub`), so nobody can reach another user's cart.

| Method | Path | Success | Errors |
|---|---|---|---|
| GET | `/cart` | 200, the cart with current prices | 401, 503 |
| GET | `/cart/items` | 200, only the product ids and quantities, read from Redis alone (used at checkout) | 401, 503 |
| POST | `/cart/items` | 200, the updated cart: the quantity is added to what is there | 400, 401, 404 unknown product, 409 limit reached, 503 |
| PUT | `/cart/items/{productId}` | 200, the updated cart: the quantity is set, and the product added if missing | 400, 401, 404 unknown product, 409 limit reached, 503 |
| DELETE | `/cart/items/{productId}` | 204, the product is no longer in the cart (also if it never was) | 400 invalid id, 401, 503 |
| DELETE | `/cart` | 204, the cart is emptied | 401, 503 |

`POST /cart/items` takes `{ "productId": "150dac81-6130-4903-9385-9b682db879b3", "quantity": 2 }`, and `PUT` takes `{ "quantity": 3 }`. A quantity is from 1 to 20; a product is removed with `DELETE`, not with quantity 0. A cart holds at most 20 of one product and at most 50 different products; a request that would go over gets 409 `CART_LIMIT_EXCEEDED` and changes nothing.

The cart as `GET /cart` and the changes return it:

```json
{
  "items": [
    {
      "productId": "150dac81-6130-4903-9385-9b682db879b3",
      "name": "Handcrafted Bamboo Basket",
      "imageUrl": "https://example.com/images/bamboo-basket.jpg",
      "unitPrice": 499.99,
      "quantity": 2,
      "lineTotal": 999.98,
      "available": true
    }
  ],
  "totalQuantity": 2,
  "totalPrice": 999.98
}
```

How it works:

- **Storage.** A cart is one Redis hash, `cart:{userId}`, mapping each product id to its quantity. Adding uses `HINCRBY`, which is atomic, so two quick clicks on "add" both count. A cart that nobody changes for 30 days (`CART_TTL`) expires; every change starts the 30 days again. Redis writes its data to disk (see Start Redis), so carts survive a restart.
- **Prices are never stored in the cart.** Each read asks the product service for the current name and price of every product (`GET /products/{id}`, which the product service answers from its own Redis cache). A price change shows up in the cart at once. The order service does not use the cart's prices: it takes the prices from the stock reservation when the order is placed.
- **Products removed from the catalog** stay in the cart with `"available": false` and no price, and are left out of the totals, so the customer sees what happened. Adding an unknown product is refused with 404.
- **When the product service is down or slow** (more than 1 s to connect or 2 s to answer), reading or changing the cart answers 503 `PRODUCT_SERVICE_UNAVAILABLE` instead of a cart without prices. Removing products and emptying the cart need only Redis, so they keep working.
- **When Redis is down**, every request answers 503 `CART_UNAVAILABLE` at once.
- **Checkout reads `GET /cart/items`**, which needs only Redis: the order service takes its prices from the stock reservation, so pricing the cart for it would only add one product-service call per line.
- **Tokens are checked here, without calling the user-auth service:** the signature against the user-auth service's public keys (`/oauth2/jwks`, fetched on first use and cached), the issuer and the expiry. A forged, altered, expired or unsigned token gets 401 `INVALID_TOKEN`. The limit of checking locally: a token that was logged out keeps working in the cart service until it expires.

Errors use the same format as the other services. Codes: `INVALID_TOKEN` (401, with `WWW-Authenticate: Bearer`), `FORBIDDEN` (403, a token that does not belong to a user), `PRODUCT_NOT_FOUND` (404), `CART_LIMIT_EXCEEDED` (409), `PRODUCT_SERVICE_UNAVAILABLE` and `CART_UNAVAILABLE` (503), `VALIDATION_FAILED`, `INVALID_PARAMETER` and `MALFORMED_REQUEST` (400).

```
docker compose exec redis redis-cli --scan --pattern 'cart:*'
docker compose exec redis redis-cli HGETALL cart:<user id>
docker compose exec redis redis-cli TTL cart:<user id>
```

## Order Service API

The order service (port 8083) turns the signed-in customer's cart into an order. Every request needs a token from the user-auth service; the customer is always the token's user, and another customer's order answers 404, as if it did not exist.

| Method | Path | Success | Errors |
|---|---|---|---|
| POST | `/orders` | 201, the order, with `Location: /orders/{id}` | 400, 401, 409 cart empty, not enough stock or product no longer sold, 503 |
| GET | `/orders?page=0&size=10` | 200, a page of the customer's orders, newest first (at most 50 per page) | 401 |
| GET | `/orders/{id}` | 200, the order | 400 invalid id, 401, 404 |
| POST | `/orders/{id}/cancel` | 200, the order, now `CANCELLED` | 400, 401, 404, 409 the order can no longer be cancelled |

`POST /orders` takes only where to send the order; the products come from the cart and the prices from the product service, never from the client:

```json
{
  "shippingAddress": {
    "name": "Asha Gamit",
    "phone": "9876543210",
    "line1": "12 Station Road",
    "line2": "Near the bus stand",
    "city": "Ahwa",
    "state": "Gujarat",
    "pincode": "394710"
  }
}
```

`phone` is a 10-digit mobile number, `pincode` a 6-digit PIN code; `line2` is optional. The answer:

```json
{
  "id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "status": "PENDING_PAYMENT",
  "items": [
    { "productId": "150dac81-6130-4903-9385-9b682db879b3", "name": "Handcrafted Bamboo Basket",
      "unitPrice": 499.99, "quantity": 2, "lineTotal": 999.98 }
  ],
  "totalAmount": 999.98,
  "shippingAddress": { "name": "Asha Gamit", "phone": "9876543210", "...": "..." },
  "createdAt": "2026-10-25T15:20:41.123456Z"
}
```

**Placing an order** involves three services, with no transaction across them. Each step is arranged so that a failure leaves nothing behind:

1. The cart is read from the cart service, with the customer's own token. An empty cart is 409 `CART_EMPTY`.
2. The stock is reserved in the product service under a new order id. It is all or nothing: if any product is short, nothing is reserved (409 `INSUFFICIENT_STOCK`); a product no longer sold is 409 `PRODUCT_UNAVAILABLE`. The reservation returns each product's current name and price, and the order is placed at exactly these prices. If the product service does not answer in time, the order id is released, in case the units were taken anyway.
3. The order is saved in `order_db` as `PENDING_PAYMENT`. If that fails, the reservation is released again.
4. The cart is emptied. If that fails, the order still stands.

**Idempotency-Key.** A client can send `Idempotency-Key: <a unique string>` (letters, digits, `-` and `_`, up to 100), for example a UUID it creates once per checkout. Sending the same request again, after a timeout or a double click, returns the order already placed instead of placing a second one; a unique key in the database also covers two requests arriving at the same moment.

**Order states:**

```
PENDING_PAYMENT --(payment)--> CONFIRMED --> SHIPPED --> DELIVERED
       |
       +--(cancel)--> CANCELLED
```

Payment is not connected yet, so orders stay `PENDING_PAYMENT` until they are cancelled; `CONFIRMED`, `SHIPPED` and `DELIVERED` arrive with the payment service and producer tools. Only a `PENDING_PAYMENT` order can be cancelled: the status changes in one conditional `UPDATE`, so a cancel and a payment arriving together cannot both win, and then the reserved units go back to the stock. Cancelling a cancelled order again is allowed and releases the units again (releasing is idempotent), which completes a cancel during which the product service was unreachable.

Errors use the same format as the other services. Codes: `INVALID_TOKEN` (401), `FORBIDDEN` (403), `ORDER_NOT_FOUND` (404), `CART_EMPTY`, `INSUFFICIENT_STOCK`, `PRODUCT_UNAVAILABLE` and `INVALID_ORDER_STATE` (409), `CART_SERVICE_UNAVAILABLE` and `PRODUCT_SERVICE_UNAVAILABLE` (503), `VALIDATION_FAILED`, `INVALID_PARAMETER` and `MALFORMED_REQUEST` (400).

## Events (Kafka)

Services tell each other what happened through Kafka, when the sender does not need an answer: a signup does not wait for the welcome email. Requests that need an answer at once (a price, a stock reservation) stay REST calls.

| Topic | Owner (producer) | Event | Key | Consumers |
|---|---|---|---|---|
| `user-events` | user-auth-service | `UserRegistered`: `userId`, `name`, `email` | user id | notification-service (welcome email) |

Every event is one JSON object with `eventId`, `eventType` and `occurredAt` first, then its own fields, for example:

```json
{ "eventId": "0b6f3c1e-8a0d-4f7e-9a51-3c2a6e8d9f10", "eventType": "UserRegistered", "occurredAt": "2026-10-25T15:38:42.512Z",
  "userId": "a8c1e6ff-556d-4dd2-a238-148b3fca351c", "name": "Asha Gamit", "email": "asha@example.com" }
```

**Transactional outbox.** A service never sends an event straight to Kafka from its business code. It writes the event into its own `outbox_event` table in the same database transaction as the change (the new account), so both are saved or neither is. A relay sends unsent rows to Kafka every second, oldest first, waits for the broker to confirm each one (`acks=all`), and marks it sent. If Kafka is down, events wait in the table and go out when it is back; nothing is lost and no event is sent for a change that was rolled back. Sent rows are deleted after 7 days.

Delivery is therefore *at least once*: an event can arrive twice (for example if the service stops right after Kafka accepted it), so consumers recognise repeats by `eventId`. Events with the same key go to the same partition and are read in order.

```
MSYS_NO_PATHCONV=1 docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 --topic user-events --from-beginning --property print.key=true
```

(`MSYS_NO_PATHCONV=1` is only needed in Git Bash on Windows.)

## Testing

Tests follow the testing pyramid: many fast unit tests, a few slower integration tests.

| Kind | Naming | Runs in | Needs | What it covers |
|---|---|---|---|---|
| Unit tests | `*Test` | `./mvnw test` (Maven Surefire) | Only the JVM | Services and controllers as plain Java classes, with their dependencies replaced by Mockito mocks |
| Web layer tests | `*WebMvcTest` | `./mvnw test` (Maven Surefire) | Only the JVM | Real HTTP requests through Spring MVC with MockMvc (`@WebMvcTest`): routing, JSON in and out, validation, status codes, headers and the error format, with the service replaced by a mock (`@MockitoBean`) |
| Integration tests | `*IT` | `./mvnw verify` (Maven Failsafe) | A running MySQL (`DB_URL`) and Redis | The whole application against the real database and Redis: Flyway's migrations apply, Hibernate accepts the schema, queries, the product cache and carts behave as expected |

Unit tests use JUnit 5 and Mockito, follow Arrange-Act-Assert, and sit in packages that mirror the main code (`services/ProductDbServiceTest` tests `services/ProductDbService`). They cover the happy path, edge cases and failure cases of each business rule: a `PUT` never creates a product, a `PATCH` cannot change the id and must still pass validation, a delete only sets the `deleted` flag, and finding or creating a category recovers when two requests create the same new name at once.

Web layer tests check the JSON field by field with `jsonPath`, or against the whole expected body with `content().json(..., STRICT)`. The expected JSON is written out in the test rather than produced by the same serializer the application uses.

In the cart service, the call to the product service is tested against `MockRestServiceServer` (exact responses: found, not found, server error) and against real sockets on the test machine (connection refused, and a server that never answers, to prove the read timeout). `JwtValidationIT` checks the real token settings with real signed JWTs: a small local HTTP server publishes a test public key in place of the user-auth service, and tokens signed with another key, from another issuer, expired or unsigned are refused.

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
- [x] Redis: product cache
- [x] Redis: cart storage
- [x] Product search, pagination, sorting and filtering
- [x] Kafka domain events through a transactional outbox: `UserRegistered`
- [ ] Payment Service: Razorpay and Stripe, webhooks, reconciliation
- [x] Cart service: carts in Redis, current prices from the product service, JWTs checked with the user-auth service's public keys
- [x] Order service: checkout from the cart with stock reservations, Idempotency-Key, order history, cancellation
- [x] Stock and stock reservations (inside the product service)
- [ ] Notification service
- [ ] Spring Cloud: Eureka service discovery and API Gateway
- [ ] Docker and Docker Compose

## Future scope

- Producer onboarding and tools for producers to manage their own listings
- Richer product search and recommendations
- Logistics and shipping integration
- Web and mobile clients
