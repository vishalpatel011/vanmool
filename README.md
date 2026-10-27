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
| `payment-service` | Payment links (Razorpay, Stripe), webhooks, callbacks, reconciliation | MySQL (`payment_db`) | In progress |
| `notification-service` | Emails for events on Kafka: welcome, order placed, order cancelled | Redis (handled event ids) | In progress |
| `api-gateway` | Single entry point: routes each request to the service that owns it, request ids, access log | | In progress |
| `service-discovery` | Eureka service registry: every service registers here and finds the others by name | | In progress |

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
| Spring Mail (Jakarta Mail) | Sending email over SMTP | In use |
| RestClient | Calls between services (cart to product), with timeouts | In use |
| Spring Cloud Gateway, Eureka | Routing, rate limits and service discovery | In use |
| Razorpay Java SDK 1.4, Stripe Java SDK 34 | Payment links, webhook and callback signature checks | Implemented in test mode, verified with the SDKs' own signature checks (no live gateway accounts are used) |
| Docker Compose | Local infrastructure (Redis, Kafka, Mailpit) | In use |
| Docker | An image per service (two-stage build, layered jar, non-root user) | In use |
| AWS (EC2, Elastic Beanstalk, VPC, RDS) | Deployment | Planned |

## Repository structure

```
vanmool/
├── pom.xml              Maven aggregator: builds every service
├── mvnw, mvnw.cmd       Maven wrapper
├── .mvn/
├── .env.example         environment variables the services read
├── docker-compose.yml   local infrastructure: Redis, Kafka, Mailpit; MySQL with --profile app
├── infrastructure/mysql/init/  creates the services' databases and users in the MySQL container
├── user-auth-service/   accounts, login and tokens (same layout as product-service)
├── cart-service/       shopping carts in Redis (same layout; clients/ holds the call to product-service)
├── order-service/      orders (same layout; clients/ holds the calls to cart-service and product-service)
├── notification-service/  emails from Kafka events (events/ listeners, emails/ composing and sending; no HTTP API)
├── payment-service/    payments (gateways/ holds Razorpay, Stripe and the fake gateway behind one interface)
├── service-discovery/  Eureka service registry (a Spring Boot application with @EnableEurekaServer)
├── api-gateway/        Spring Cloud Gateway: routes, request ids (filters/), the gateway's error format (errors/)
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

Each service has its own `pom.xml` with the Spring Boot parent and its own `Dockerfile`. The root `pom.xml` only lists the services as modules, so one command builds all of them, and each service can still be built, packaged and deployed on its own.

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

CREATE DATABASE payment_db;
CREATE USER 'vanmool_payment_user'@'localhost' IDENTIFIED BY 'choose-a-fourth-password';
GRANT ALL PRIVILEGES ON payment_db.* TO 'vanmool_payment_user'@'localhost';
```

The tables are created by Flyway, not by hand and not by Hibernate. On startup Flyway applies every script in the service's `src/main/resources/db/migration` that the database has not seen yet, and records it in the `flyway_schema_history` table. Hibernate then only checks that the tables match the entities (`spring.jpa.hibernate.ddl-auto=validate`) and stops the application if they do not.

A schema change is always a new script with the next version number, named `V<version>__<description>.sql` (for example `V1__create_category_and_product_tables.sql`). A script that has already been applied is never edited: Flyway stores a checksum of each script and refuses to start if an applied one changes.

### 2. Start Redis, Kafka and Mailpit

```
docker compose up -d
```

This starts, reachable from this machine only:

- **Redis 7** on `localhost:6379`. Its data is kept in a Docker volume (`redis-data`) and written to disk once a second (append-only file), so it survives a restart of the container.
- **Kafka 3.9** on `localhost:9092`: one broker in KRaft mode (no ZooKeeper), data in the `kafka-data` volume. Topics are not created automatically; each service declares the topics it owns when it starts. Services in containers reach the same broker at `kafka:29092`: Kafka tells each client the address of the listener it came in on, so a client on this machine and one in a container each get an address they can reach.
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
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka broker addresses | `localhost:9092` | `localhost:9092` | not used | `localhost:9092` |
| `REDIS_HOST`, `REDIS_PORT` | Redis server | `localhost`, `6379` | not used | `localhost`, `6379` | not used |
| `REDIS_PASSWORD` | Redis password, if the server requires one | empty | not used | empty | not used |
| `PRODUCT_CACHE_TTL` | Longest time a product stays cached (ISO-8601 duration) | `PT10M` (10 minutes) | not used | not used | not used |
| `AUTH_ISSUER` | Issuer of the tokens: user-auth-service's public URL | `http://localhost:8081` | `http://localhost:8081` | `http://localhost:8081` | `http://localhost:8081` |
| `AUTH_JWKS_URI` | Where user-auth-service publishes its public keys: a service name, through Eureka | `http://user-auth-service/oauth2/jwks` | not used | `http://user-auth-service/oauth2/jwks` | `http://user-auth-service/oauth2/jwks` |
| `PRODUCT_SERVICE_URL` | Base URL of product-service: a service name, resolved through Eureka | not used | not used | `http://product-service` | `http://product-service` |
| `CART_TTL` | A cart untouched for this long is deleted (ISO-8601 duration) | not used | not used | `P30D` (30 days) | not used |
| `CART_SERVICE_URL` | Base URL of cart-service: a service name, resolved through Eureka | not used | not used | not used | `http://cart-service` |
| `EUREKA_URL` | Where the service registers and reads the registry | `http://localhost:8761/eureka/` | `http://localhost:8761/eureka/` | `http://localhost:8761/eureka/` | `http://localhost:8761/eureka/` |

The API gateway reads `EUREKA_URL`, `SERVER_PORT` (default `8000`), `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` (rate limits and ended tokens) and `KAFKA_BOOTSTRAP_SERVERS` (the `TokenRevoked` events).

- **IntelliJ IDEA:** Run > Edit Configurations > `ProductServiceApplication` (or `UserAuthServiceApplication`, `CartServiceApplication`, `OrderServiceApplication`) > Environment variables, for example `DB_USERNAME=vanmool_user;DB_PASSWORD=choose-a-password`.
- **Shell:** export the variables before running, for example `export DB_PASSWORD=choose-a-password`.

Never commit real credentials. `.env` is git-ignored for this reason.

### 4. Build and test

```
./mvnw test            # unit tests only: fast, no database needed
./mvnw verify          # unit tests, then integration tests against MySQL and Redis (Git Bash, macOS, Linux)
.\mvnw.cmd verify      # the same from Windows PowerShell or cmd
```

To build a single service: `./mvnw -pl product-service verify`, `./mvnw -pl user-auth-service verify`, `./mvnw -pl cart-service verify`, `./mvnw -pl order-service verify`, `./mvnw -pl notification-service verify`, `./mvnw -pl payment-service verify` or `./mvnw -pl service-discovery verify`. The tests run without a Eureka server: `src/test/resources/config/application.properties` turns the Eureka client off.

### 5. Run

Start the service registry first, then the services:

- **IntelliJ IDEA:** open the `vanmool` folder and run `ServiceDiscoveryApplication`, then `ApiGatewayApplication`, `ProductServiceApplication`, `UserAuthServiceApplication`, `CartServiceApplication`, `OrderServiceApplication`, `NotificationServiceApplication` or `PaymentServiceApplication`.
- **Command line:** `./mvnw -pl service-discovery spring-boot:run`, then `./mvnw -pl api-gateway spring-boot:run` and `./mvnw -pl product-service spring-boot:run` (likewise `user-auth-service`, `cart-service`, `order-service`, `notification-service`, `payment-service`)

The registry listens on `http://localhost:8761`, which is also its dashboard of registered instances. Clients use the API gateway at `http://localhost:8000`; each service can also still be called at its own port. The product service listens on `http://localhost:8080`, the user-auth service on `http://localhost:8081`, the cart service on `http://localhost:8082`, the order service on `http://localhost:8083` and the payment service on `http://localhost:8085`, so all of them can run at once. The notification service has no HTTP port: it only reads Kafka. The cart service needs Redis, tokens from the user-auth service, and the product service for prices; it starts without the other two and contacts them on the first request that needs them.

## Docker

Every service has a `Dockerfile` in its directory. To build an image:

```
docker build -t vanmool/product-service product-service
```

Likewise for `user-auth-service`, `cart-service`, `order-service`, `payment-service`, `notification-service`, `service-discovery` and `api-gateway`.

How the images are built:

- **Two stages.** The first stage compiles the service with Maven 3.9.9 and JDK 17 inside Docker, so the image does not depend on the Java or Maven installed on the machine that builds it. The second stage holds only a Java 17 runtime (Eclipse Temurin JRE) and the application; Maven, the JDK and the sources are left behind.
- **Tests run before, not inside, the build.** `./mvnw verify` runs them; the image build skips them, because the integration tests need MySQL, Redis and Kafka.
- **Dependencies cached between builds.** A BuildKit cache mount keeps Maven's downloads between builds without putting them into the image.
- **Layers.** The jar is split into layers with Spring Boot's `jarmode=tools`: libraries (about 112 MB for product-service) and the application's own code (about 80 KB). A code change rebuilds only the small layer, so a new version is quick to push and pull.
- **Not root.** The service runs as the user `vanmool`. The application files belong to root, so the service cannot change its own code.
- **Signals reach Java.** `java` is the container's main process, so `docker stop` triggers Spring's graceful shutdown: the service finishes its requests and leaves Eureka. Measured for the gateway: it exits 6 s after the signal, within Docker's default 10 s.

Settings come from environment variables, as outside Docker (see the table in Getting started), for example:

```
docker run -p 8761:8761 vanmool/service-discovery
```

### MySQL in Docker

For the containerized stack, MySQL runs in a container too (profile `app`; a plain `docker compose up -d` leaves it out):

1. Copy the last block of `.env.example` (from `MYSQL_ROOT_PASSWORD` to the end) into a file named `.env` next to `docker-compose.yml`, with passwords of your own. Docker Compose reads `.env` by itself; it is git-ignored.
2. `docker compose --profile app up -d mysql`

On its first start, with an empty `mysql-data` volume, the container runs `infrastructure/mysql/init/create-databases.sh`: it creates `product_db`, `user_db`, `order_db` and `payment_db`, each with its own user (`ProductService_user`, `UserAuthService_user`, `OrderService_user`, `PaymentService_user`) that can reach only that database. The health check turns healthy only after that, because MySQL accepts no TCP connections while the script runs (14 s on the first start here). The passwords are set only then: to change them later, use `ALTER USER`, or `docker compose down -v` to start over with empty databases.

The image is MySQL 8.4, the long-term support release (8.0 reached its end of life in April 2026). The services' tests pass against it as well as against a local MySQL 8.0, for example:

```
DB_URL=jdbc:mysql://localhost:3307/product_db DB_USERNAME=ProductService_user DB_PASSWORD=... ./mvnw -pl product-service verify
```

The container listens on `127.0.0.1:3307` for a database client on this machine (3306 is usually taken by a local MySQL); services in containers use `mysql:3306`. Flyway logs a warning that it has not been tested with MySQL 8.4 (its newest tested version is 8.1); every migration and test passes against 8.4.

### The whole stack in Docker

With the `.env` described above (passwords, and optionally `JWT_PRIVATE_KEY`, `PAYMENT_FAKE_GATEWAY_ENABLED`, `PUBLIC_URL`):

```
docker compose --profile app up -d --build
```

This builds the eight images and starts them with MySQL, Redis, Kafka and Mailpit. Stop the services started from IntelliJ first: the stack uses the same ports for the gateway and the registry.

- **One address for clients:** the API gateway at `http://127.0.0.1:8000`. The Eureka dashboard (`http://127.0.0.1:8761`) and Mailpit (`http://127.0.0.1:8025`) are also published; the services' own ports are not, so every request goes through the gateway. All of them listen on this machine's IPv4 loopback only, so use `127.0.0.1`: a client that tries `localhost`'s IPv6 address first waits for it to fail (measured on Windows: 2 s per connection from Python, 0.2 s from curl).
- **Names, not addresses:** inside the Compose network the services reach `mysql:3306`, `redis:6379`, `kafka:29092`, `mailpit:1025` and `service-discovery:8761` by name, and each other through Eureka, which hands out the containers' addresses.
- **Start order:** a service starts once what it needs is healthy (MySQL, Kafka, Redis, the registry). Every HTTP service has a health check on its Actuator health; on the first start here all eleven checked containers were healthy 48 s after `up`.
- **Restarts:** a service that stops is started again (`restart: unless-stopped`), unless it was stopped on purpose.
- **Stopping:** each service gets 30 s between the stop signal and a forced kill (`stop_grace_period`). It needs about 4–6 s to finish its requests and leave Eureka. Docker Desktop 29.3 killed containers 1.5–2 s after `docker stop` by default; a service killed before leaving Eureka stays in the registry until its lease expires (about 40 s), and callers keep picking it.
- **Memory:** each container has a limit, 512 MB (640 MB for product-service, the busiest; 384 MB for the registry and the notification service), and Java sizes itself from it: half of it for the heap (`-XX:MaxRAMPercentage=50`), the rest for the JVM itself. Without limits, each JVM would size its heap from the whole machine. Kafka's heap is 512 MB instead of the image's 1 GB.
- **Public address:** `PUBLIC_URL` (default `http://127.0.0.1:8000`) is the tokens' issuer and the base of the links the services hand out, such as the payment pages, because clients reach everything through the gateway.
- **Signing key:** with `JWT_PRIVATE_KEY` set, sessions survive a restart of the user-auth service; without it, a new key is made at every start. Generate one with `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048` and put the lines between the `BEGIN` and `END` markers on one line.
- **Payments:** `PAYMENT_FAKE_GATEWAY_ENABLED=true` turns on the fake gateway to try payments without a Razorpay or Stripe account; its webhook comes in through the gateway, like a real one's.

Useful commands:

```
docker compose --profile app ps                      # status and health of every container
docker compose --profile app logs -f order-service   # one service's log
docker compose --profile app up -d --build cart-service   # rebuild and restart one service after a code change
docker compose --profile app down                    # stop and remove the containers, keep the data
docker compose --profile app down -v                 # also delete the data (databases, carts, events)
```

Measured on a laptop (8 CPUs, Docker Desktop on Windows), 2026-10-27:

| Check | Result |
|---|---|
| `up` until every checked container is healthy | 48 s (first start, no memory limits); 68–74 s with the limits |
| A purchase through the gateway: cart, order, fake payment, webhook, Kafka | order `CONFIRMED` 0.6 s after paying; order, payment and welcome emails in Mailpit |
| 10 clients for 60 s (catalog, product, stock, cart) | 32,775 requests (546 a second), median 13–19 ms, p95 29–44 ms; 2 failed (below) |
| Memory under that load | 172–454 MB per service, 2.6 GB for the eight; with Kafka, MySQL, Redis and Mailpit 3.9 GB. No container killed or restarted |
| user-auth-service restarted | the customer's token still works afterwards; logins unavailable for 26 s (8 s to start, the rest until the gateway sees the new instance) |
| Kafka stopped while an order is placed | the order is placed; its email goes out once Kafka is back (12.8 s after starting it) |
| MySQL stopped | the database services answer 503 `SERVICE_UNAVAILABLE` after 2 s; once they are marked down (about 20 s), the gateway answers 503 at once; all work again 26 s after MySQL is back, without a restart. Before a 2 s connection timeout was set: 500 after 30 s, 504 through the gateway |

Known limit, to be improved: about one request in 15,000 failed at the gateway because product-service closed a pooled connection just as it was being reused.

## Service discovery (Eureka)

Services do not know each other's addresses. Each one registers with the registry (`service-discovery`, Netflix Eureka through Spring Cloud) when it starts, under its `spring.application.name`: `PRODUCT-SERVICE`, `USER-AUTH-SERVICE`, `CART-SERVICE`, `ORDER-SERVICE`, `PAYMENT-SERVICE`. It then sends a heartbeat every 30 seconds, and removes itself when it shuts down. The notification service has no HTTP API, so nothing needs to find it.

- **Calls by name, with client-side load balancing.** The cart service calls `http://product-service/products/{id}`; the order service `http://cart-service` and `http://product-service`; the payment service `http://order-service`. Spring Cloud LoadBalancer resolves the name to the instances the registry knows and takes them in turn (round robin), so a second instance of a service shares the work without any change to its callers. Calls go straight from service to service, not through the API gateway, which would add a hop.
- **Health decides who gets traffic.** Each service publishes `/actuator/health` (and `/actuator/info`; nothing else, because endpoints such as the environment or a heap dump would give too much away). Its status in the registry follows that health: an instance whose database is down is shown `DOWN` and gets no calls. The product service leaves Redis out of its health, because it keeps working without its cache.
- **No instance up** means a call fails at once, like a service that cannot be reached: the caller answers 503 (`PRODUCT_SERVICE_UNAVAILABLE`, `CART_SERVICE_UNAVAILABLE`, `ORDER_SERVICE_UNAVAILABLE`), never 500.
- **A crashed instance does not cost callers anything.** A service that stops normally removes itself from the registry at once. One that crashes stays listed until it misses its heartbeats, and the load balancer may still pick it meanwhile; such a call is tried once more on another instance (Spring Retry), so it still succeeds. Every call between the services is safe to repeat: reads, and stock reservations and releases, which the product service keys by the order id.
- **Timings.** Heartbeats every 10 seconds, a 30-second lease, and 10-second caches in the registry, the callers and their load balancers (Eureka's defaults are three to six times longer). Measured locally with two product-service instances (round robin: 5 calls each out of 10):

  | Event | Measured |
  |---|---|
  | A new instance starts | first call reaches it 17 s later |
  | An instance crashes | gone from the registry 64 s later; callers stop picking it after 74 s. Until then, the calls that picked it took about 1 s longer (a refused connection takes a second on Windows), and none failed (42 of 42 cart reads succeeded) |
  | Redis stops, so cart-service's health is DOWN | `DOWN` in the registry 5 s later; checkout answers 503 at once (10 to 60 ms) instead of waiting for Redis |
  | Redis is back | `UP` 32 s later; checkout works again after 35 s |
- **Without the registry**, the services still start and keep their last copy of the registry. Locally, Eureka's self-preservation is off (`EUREKA_SELF_PRESERVATION`): with only a few instances it would mostly keep instances that were simply stopped.

## API gateway

Clients use one address, `http://localhost:8000` (Spring Cloud Gateway on WebFlux). The gateway sends each request, unchanged, to the service that owns its path and to one of that service's instances (`lb://`, from the registry, round robin). It holds no business logic.

| Path | Service |
|---|---|
| `/auth/**`, `/users/**` | user-auth-service |
| `/products/**`, `/categories/**` | product-service |
| `/cart/**` | cart-service |
| `/orders/**`, `/admin/orders/**` | order-service |
| `/payments/**` (including webhooks and callbacks), `/fake-gateway/**` | payment-service |

- **Only the public API is routed.** Internal endpoints, such as the stock reservations that the order service makes with the product service (`/stock-reservations`), answer 404 from outside. The OAuth2 / OpenID Connect endpoints and the sign-in page of the user-auth service are used at its own address, which is also the issuer written into tokens.
- **Request ids.** Every request gets an `X-Request-Id`: the client's own if it sent a plain one (letters, digits, hyphens, at most 64), otherwise a new UUID. The service receives it, and the client gets it back in the response, also on errors. The gateway logs one line per request, for example `GET /cart -> cart-service 200 in 23 ms [5c3e...]`. The path is logged without its query, because a query can hold a secret such as a password reset token.
- **Errors in the services' format.** When the gateway answers itself, the body has the same fields as the services' errors (`timestamp`, `status`, `error`, `message`, `path`):
  - `404 NOT_FOUND`: no API at this path.
  - `503 SERVICE_UNAVAILABLE`: the service has no instance up, or the instance cannot be reached.
  - `504 GATEWAY_TIMEOUT`: no answer within 10 seconds.

  The messages carry no host names or exception texts.
- **Retries.** A `GET` whose instance cannot be reached is tried once more on the next instance, as the services do among themselves. A service's own error answer is passed on as it is.
- **Rate limits on signing in.** `POST /auth/login`, `/auth/signup`, `/auth/password-reset` and `/auth/password-reset/confirm` together accept at most 10 requests a minute from one client address. Over the limit, the answer is `429 Too Many Requests` and the request never reaches the user-auth service. This slows down password guessing and stops a stranger from flooding someone's mailbox with reset links (the user-auth service also sends at most 3 links per account per hour). The response headers show the remaining budget (`X-RateLimit-Remaining`, in tokens of 6 per request). Other requests, including the page a reset link opens, are not limited.
  - The counters live in Redis (a token bucket, Spring Cloud Gateway's `RedisRateLimiter`), so several gateway instances share them.
  - If Redis is down, requests are let through rather than refused, and the gateway stays healthy.
  - Behind a load balancer, the client's address must be taken from `X-Forwarded-For`, which only the load balancer may set.
- **Time limits.** 1 second to connect to a service, 10 seconds for its answer (starting a payment calls a payment gateway).
- **Ended tokens are refused.** The services check tokens themselves, with the user-auth service's public keys, so on their own they would accept a token until it expires, also after a logout. The user-auth service therefore publishes a `TokenRevoked` event (on `user-events`) for every token it ends: a logout, the other sessions of a password change, all sessions of a password reset.
  - The gateway reads these events (consumer group `api-gateway`) and keeps each ended token's id in Redis (`revoked-token:{jti}`) until the token would have expired. A request with such a token gets `401 INVALID_TOKEN` ("The token has been revoked") before it reaches any service.
  - The gateway reads only the token's id. Checking its signature stays with the services, so a made-up token with an unknown id still fails there.
  - `/auth/**` is left to the user-auth service, which keeps its own list.
  - If Redis is down, tokens are let through: they are still checked by the services, but an early logout is not seen until Redis is back.
  - Called directly, without the gateway, the services would still accept an ended token until it expires. In a deployment only the gateway is reachable from outside.

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

**Who may call what.** Reading products, categories and stock needs no token: shoppers browse before they sign in. Creating, replacing, patching and deleting products, and adjusting stock, need a token with the `SELLER` (a producer) or `ADMIN` role; without a token the answer is `401 INVALID_TOKEN`, with another role `403 FORBIDDEN`. Stock reservations need the token of a signed-in user (the order service sends the customer's). Tokens are checked here with the user-auth service's public keys, which the product service fetches from `http://user-auth-service/oauth2/jwks`, by service name through Eureka, and keeps for a few minutes; it never calls the user-auth service per request. Today any seller may change any product: tying products to the seller who listed them is planned.

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
  "images": [
    { "url": "https://example.com/images/bamboo-basket.jpg", "altText": "A round bamboo basket" },
    { "url": "https://example.com/images/bamboo-basket-weave.jpg", "altText": "The weave up close" }
  ],
  "specifications": [
    { "name": "Dimensions", "value": "30 cm across, 12 cm high" },
    { "name": "Weight", "value": "400 g" },
    { "name": "Care", "value": "Wipe with a dry cloth; keep out of the rain" }
  ],
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
| `category`, `originRegion`, `materials` | optional, at most 255 characters each |
| `images` | optional, at most 10, in display order (the first is the main image); each has a `url` (`http` or `https`, at most 500 characters) and an optional `altText` (at most 255) that describes it for screen readers |
| `specifications` | optional, at most 20, in display order; each has a `name` (at most 60) and a `value` (at most 255), both required; a name may appear only once, whatever its letter case |

A request that breaks a rule gets 400 with every failing field in the message (`"error": "VALIDATION_FAILED"`, `"message": "name: must not be blank; price: must be greater than 0"`). A failing image or specification is named with its position, for example `images[1].url`.

**Product details** (images and specifications). A response has every field of the request, plus the product's `id` and `imageUrl`: the main image, which is the first of `images`, for clients that show one picture (the cart does). Images and specifications are stored as JSON arrays in the product's own row. They are only ever read and written together with their product, so a page of products needs no further queries, and their order is simply the array's order. Products created before images could be listed kept their one image as their first image.

A new category name creates the category; an existing name reuses it. `PUT` replaces every field of an existing product. It does not create a product at a client-chosen id, because ids are generated by the database.

`PATCH` takes a JSON Patch document (`Content-Type: application/json-patch+json`), applied to the product as `GET` returns it. The patched product must pass the same rules, and the id cannot be changed (nor `imageUrl`, which always follows the first image):

```json
[{ "op": "replace", "path": "/price", "value": 550 },
 { "op": "add", "path": "/images/-", "value": { "url": "https://example.com/images/basket-in-use.jpg", "altText": "Fruit in the basket" } }]
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
| `SERVICE_UNAVAILABLE` | 503 | The service's database cannot be reached (no connection within 2 s). Temporary: try again. The user-auth, order and payment services answer the same way |
| `INTERNAL_ERROR` | 500 | An unexpected server error. The details are logged on the server, never sent to the client |

### Stock

Each product has a number of units that can still be sold, kept in its own `stock` table rather than in the product row: it changes with every order, while the product's description is cached. Stock is never part of the cached product JSON; `GET /products/{id}/stock` always reads MySQL. A product without stock has 0 units.

- **Adjustments are changes, not totals.** `POST /products/{id}/stock-adjustments` with `{ "change": 10 }` adds 10 units (a producer delivers 10 more baskets); `{ "change": -2 }` removes 2 (damaged). A change never overwrites units that orders took in the meantime. Removing more units than there are gets 409 `INSUFFICIENT_STOCK`.
- **Reservations are for orders.** The order service calls `POST /stock-reservations` when an order is placed, with the customer's token. The API gateway does not expose the reservation endpoints, so only the services can reach them:

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
- **When the response itself changes shape** (a new release adds fields), entries cached by the old version lack the new fields until they expire. Delete the `product:*` keys when deploying such a release.

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
| POST | `/auth/password-reset` | 202, a reset link is emailed if the address has an account | 400 not an email address |
| GET | `/auth/password-reset/confirm?token=...` | 200, the page the emailed link opens: a form for the new password | |
| POST | `/auth/password-reset/confirm` | 204, the password is reset and every session ends | 400 invalid new password, or link unknown, used or expired |
| GET | `/users/me` | 200, the signed-in user | 401 token missing, invalid, expired or revoked |
| PATCH | `/users/me` | 200, the profile with the fields sent changed | 400 invalid field, 401 |
| PUT | `/users/me/password` | 204, the password is changed and the user's other sessions end | 400 invalid or unchanged new password, 401, 403 current password incorrect |
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
{ "token": "eyJraWQiOi...(a JWT, shortened here)", "tokenType": "Bearer", "expiresAt": "2026-10-25T22:03:30Z" }
```

The client sends the token on later requests as `Authorization: Bearer <token>`. The token is a JWT signed with RS256: its header names the signing key (`kid`), and its payload carries the issuer, the user's id (`sub`), the token's id (`jti`), email, roles, and issue and expiry times. Tokens are valid for 10 hours (`auth.token-validity`) unless the user logs out first: logout marks the token's `jti` as revoked, and both `/auth/validate` and protected endpoints such as `/users/me` refuse it from then on.

Passwords are checked by Spring Security (a `UserDetailsService` that reads `user_db`, and the BCrypt `PasswordEncoder`). A wrong password and an unknown email get the same `401 INVALID_CREDENTIALS` answer, after the same BCrypt work, so the API does not reveal which emails have accounts.

**Profile.** `PATCH /users/me` changes only the fields it is sent; today that is `name` (not blank, at most 100 characters), for example `{ "name": "Asha Vasava" }`. The email cannot be changed here: a new address would first have to be confirmed through a link sent to it, and the email is also held in tokens and orders.

**Changing the password.** `PUT /users/me/password` takes `{ "currentPassword": "...", "newPassword": "..." }`. The current password is asked for again although the request carries a valid token, so a token left on a shared or stolen device is not enough to take over the account; a wrong one gets `403 CURRENT_PASSWORD_INCORRECT` (not 401, which would sign the user out). The new password follows the signup rules and must differ from the current one. On success the new BCrypt hash is stored, every other session of the user is revoked (the device that made the change stays signed in), and a `PasswordChanged` event makes the notification service email the user, so a change they did not make does not go unnoticed. The other services check tokens locally with the public keys; an ended session is refused there by the API gateway (see below), which learns of it from a `TokenRevoked` event.

**Forgotten password.** `POST /auth/password-reset` takes `{ "email": "..." }` and always answers `202`, whether or not the address has an account, so it cannot be used to find out who is registered. For an account, it creates a link that works once, for 30 minutes (`auth.password-reset.validity`), and the notification service emails it. At most 3 links per account are sent per hour, so nobody can flood a mailbox.

- **The token.** The link carries 32 random bytes from `SecureRandom` (Base64url, 43 characters). Only their SHA-256 hash is stored in `password_reset_token`, so a copy of the database gives nobody a usable link. A fast hash is enough here: the token is random, not something a person chose, so it cannot be guessed from its hash as a password could.
- **The link.** It opens a small page served by this service (`GET /auth/password-reset/confirm?token=...`). Opening it changes nothing, because email scanners open links on their own. The page sends `{ "token": "...", "newPassword": "..." }` to `POST /auth/password-reset/confirm`, which a web or mobile client can also call itself. `PASSWORD_RESET_LINK` points the emails at such a client instead.
- **Using it.** The link is used in one conditional `UPDATE` (two requests with the same link: one wins). In the same transaction the new BCrypt hash is stored, the account's other open links stop working, every session is revoked, and a `PasswordChanged` event sends the security notice. An unknown, used or expired link gets one answer for all three: `400 INVALID_RESET_LINK`.
- **The token in transit.** The link, with its token, travels to the notification service in the `PasswordResetRequested` event, so it is also in this service's outbox table and on the `user-events` topic until the link expires. That is accepted because the link works once and only for 30 minutes.

Roles: every new account is a `CUSTOMER`. An `ADMIN` can grant `SELLER` (for producers who list products) or `ADMIN` with `PUT /users/{id}/roles/{role}`. The first administrator is made directly in the database:

```sql
INSERT INTO user_role (user_id, role) SELECT id, 'ADMIN' FROM users WHERE email = 'admin@example.com';
```

Errors use the same format as the product service (`timestamp`, `status`, `error`, `message`, `path`). Codes: `EMAIL_ALREADY_REGISTERED` (409), `INVALID_CREDENTIALS` (401), `INVALID_TOKEN` (401, with `WWW-Authenticate: Bearer`), `FORBIDDEN` and `CURRENT_PASSWORD_INCORRECT` (403), `USER_NOT_FOUND` (404), `INVALID_RESET_LINK` (400), `VALIDATION_FAILED`, `INVALID_PARAMETER` and `MALFORMED_REQUEST` (400).

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
| `PASSWORD_RESET_LINK` | Start of the emailed password reset link; the token is appended | `${AUTH_ISSUER}/auth/password-reset/confirm?token=` (this service's own page) |
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
| GET | `/orders/{id}` | 200, the order, with its shipment and status history (order tracking) | 400 invalid id, 401, 404 |
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
  "shipment": null,
  "statusHistory": [
    { "status": "PENDING_PAYMENT", "changedAt": "2026-10-25T15:20:41.123456Z", "detail": null }
  ],
  "createdAt": "2026-10-25T15:20:41.123456Z"
}
```

**Placing an order** involves three services, with no transaction across them. Each step is arranged so that a failure leaves nothing behind:

1. The cart is read from the cart service, with the customer's own token. An empty cart is 409 `CART_EMPTY`.
2. The stock is reserved in the product service under a new order id. It is all or nothing: if any product is short, nothing is reserved (409 `INSUFFICIENT_STOCK`); a product no longer sold is 409 `PRODUCT_UNAVAILABLE`. The reservation returns each product's current name and price, and the order is placed at exactly these prices. If the product service does not answer in time, the order id is released, in case the units were taken anyway.
3. The order is saved in `order_db` as `PENDING_PAYMENT`, together with its `OrderPlaced` event (see Events). If that fails, the reservation is released again.
4. The cart is emptied. If that fails, the order still stands.

**Idempotency-Key.** A client can send `Idempotency-Key: <a unique string>` (letters, digits, `-` and `_`, up to 100), for example a UUID it creates once per checkout. Sending the same request again, after a timeout or a double click, returns the order already placed instead of placing a second one; a unique key in the database also covers two requests arriving at the same moment.

**Order states:**

```
PENDING_PAYMENT --(payment)--> CONFIRMED --> SHIPPED --> DELIVERED
       |
       +--(cancel)--> CANCELLED
```

The payment service's `PaymentSucceeded` event moves the order to `CONFIRMED` (see Payment Service API); a payment that arrives for an order that was cancelled in the meantime is logged as needing a refund. An order that is not paid within 60 minutes (`ORDER_PAYMENT_WINDOW`) is cancelled automatically and its stock goes back on sale. `SHIPPED` and `DELIVERED` are set by Vanmool's operations team (see Order tracking). Only a `PENDING_PAYMENT` order can be cancelled: the status changes in one conditional `UPDATE`, so a cancel and a payment arriving together cannot both win. The same transaction records an `OrderCancelled` event; the product service reads it and puts the reserved units back, retrying until it succeeds, so a product service that is down at that moment only delays the release. Cancelling a cancelled order again changes nothing.

### Order tracking

Producers hand their products to Vanmool's operations team, who pack and send each paid order. One order can hold products from several producers, so the team ships it as a whole. These endpoints need a token with the `ADMIN` role:

| Method | Path | Success | Errors |
|---|---|---|---|
| GET | `/admin/orders?status=CONFIRMED&page=0&size=20` | 200, a page of the orders in that status (default `CONFIRMED`: paid, waiting to be sent), oldest first | 400 unknown status, 401, 403 not an ADMIN |
| POST | `/admin/orders/{id}/ship` | 200, the order, now `SHIPPED` | 400, 401, 403, 404, 409 not `CONFIRMED`, or already shipped with other details |
| POST | `/admin/orders/{id}/deliver` | 200, the order, now `DELIVERED` | 401, 403, 404, 409 not `SHIPPED` |

`POST /admin/orders/{id}/ship` takes the carrier and, if it gives one, the tracking number (letters, digits and hyphens). A cooperative delivering nearby orders itself has none:

```json
{ "carrier": "India Post", "trackingNumber": "EE123456789IN" }
```

The customer then sees in `GET /orders/{id}` how the order was sent and every step it went through:

```json
"shipment": { "carrier": "India Post", "trackingNumber": "EE123456789IN" },
"statusHistory": [
  { "status": "PENDING_PAYMENT", "changedAt": "2026-10-26T10:00:00Z", "detail": null },
  { "status": "CONFIRMED", "changedAt": "2026-10-26T10:02:00Z", "detail": "Paid, payment 3b1f9a0e-5c2d-4e8f-9a7b-6c5d4e3f2a10" },
  { "status": "SHIPPED", "changedAt": "2026-10-27T10:00:00Z", "detail": "India Post, tracking EE123456789IN" }
]
```

Every status change adds a row to the order's history (`order_status_change`) in the same transaction as the change itself; rows are never changed. Shipping and delivering are conditional `UPDATE`s like cancelling: two people of the team clicking at the same moment ship the order once. Sending the same request again returns the order unchanged. Each change records an `OrderShipped` or `OrderDelivered` event, and the customer gets an email.

Errors use the same format as the other services. Codes: `INVALID_TOKEN` (401), `FORBIDDEN` (403), `ORDER_NOT_FOUND` (404), `CART_EMPTY`, `INSUFFICIENT_STOCK`, `PRODUCT_UNAVAILABLE` and `INVALID_ORDER_STATE` (409), `CART_SERVICE_UNAVAILABLE` and `PRODUCT_SERVICE_UNAVAILABLE` (503), `VALIDATION_FAILED`, `INVALID_PARAMETER` and `MALFORMED_REQUEST` (400).

## Payment Service API

The payment service (port 8085) takes payments for orders through a payment gateway. The customer starts a payment for one of their orders, pays on the gateway's page, and the order is confirmed when the gateway confirms the money.

| Method | Path | Auth | Success | Errors |
|---|---|---|---|---|
| POST | `/payments` | customer token | 201 a new payment link (200 if the order already has an open one) | 400, 401, 404 order not found, 409 order not payable, 503 |
| GET | `/payments/{id}` | customer token | 200 the payment and its history | 401, 404 |
| POST | `/payments/reconciliation` | `ADMIN` token | 200 a report: checked, changed, mismatches | 401, 403 |
| POST | `/payments/webhooks/{razorpay\|stripe}` | gateway signature | 200 | 400 invalid signature |
| GET | `/payments/callback/{razorpay\|stripe}` | none (checked with the gateway) | 200 the payment | 400, 404 |

`POST /payments` takes `{ "orderId": "...", "gateway": "RAZORPAY" }` (`gateway` optional: `RAZORPAY` for UPI, cards and net banking, `STRIPE` for international cards). It never takes an amount: the payment service asks the order service for the order with the customer's own token, so a customer can only pay their own order, and only what it costs. The answer holds `paymentUrl`, the gateway page where the customer pays, valid for 30 minutes.

**Gateways.** Each gateway is one implementation of a `PaymentGateway` interface (the strategy pattern), so gateway-specific code stays in its own class:

- **Razorpay:** a Payment Link per payment, with the payment's id as `reference_id`, which Razorpay accepts only once.
- **Stripe:** a Checkout Session per payment, created with the payment's id as Stripe's `Idempotency-Key`.
- **Fake gateway:** for development and tests (`PAYMENT_FAKE_GATEWAY_ENABLED=true`, never in a deployment). Its checkout page at `/fake-gateway/checkout/...` lets you pay or fail a payment without an account, and it sends a signed webhook like the real ones.

If the chosen (or default) gateway cannot create a link, the next enabled gateway is tried. A gateway is enabled when its keys are set; only test mode keys belong in development.

**One open payment per order.** Asking again for an order returns its open link, so a double click never gives two links to pay. A unique database index on open payments enforces it even for requests at the same moment.

**How a payment is confirmed.** There are three paths, and all of them change a payment at most once (a conditional `UPDATE` from `CREATED`):

1. **Webhook** (the reliable one): the gateway calls `/payments/webhooks/...` server to server. The signature is checked (Razorpay: HMAC-SHA256 of the body; Stripe: HMAC-SHA256 of timestamp and body, which also refuses old replayed events). Each gateway event id is stored, so a second delivery changes nothing.
2. **Callback**: the gateway sends the customer's browser back to `/payments/callback/...`. Its URL parameters can be changed by anyone, so they are only used to find the payment; the result is asked from the gateway.
3. **Reconciliation**: every 5 minutes, open payments older than 2 minutes are checked with their gateway (a webhook can be lost, a browser closed), and the last day's finished payments are compared with the gateway's records. The gateway is the source of truth; a disagreement, such as money taken for a payment recorded as expired, is recorded and logged for manual review instead of being changed automatically.

A paid amount that differs from the order's amount is not accepted either; it is recorded for review.

**History.** Every change of a payment's status is appended to `payment_status_change`, with where it came from (`API`, `WEBHOOK`, `CALLBACK`, `RECONCILIATION`, `ORDER_EVENT`), and never updated. `GET /payments/{id}` shows it.

**Events.** A finished payment publishes `PaymentSucceeded` or `PaymentFailed` on `payment-events` through the outbox (see Events). When an order is cancelled, the payment service closes its open payment link at the gateway, so a cancelled order cannot be paid; if the link was paid at that very moment, the payment is recorded and the order service reports the refund that is needed.

**Webhooks locally.** A gateway cannot reach `localhost`. Callbacks and reconciliation work without it; for webhooks, the service needs a public address, from a tunnel such as `cloudflared` or `ngrok`, or the deployment's URL (`PAYMENT_PUBLIC_BASE_URL`).

Errors use the same format as the other services. Codes: `ORDER_NOT_FOUND`, `PAYMENT_NOT_FOUND` (404), `ORDER_NOT_PAYABLE`, `PAYMENT_IN_PROGRESS` (409), `INVALID_SIGNATURE`, `GATEWAY_NOT_AVAILABLE`, `VALIDATION_FAILED`, `MALFORMED_REQUEST` (400), `INVALID_TOKEN` (401), `FORBIDDEN` (403), `PAYMENT_GATEWAY_UNAVAILABLE`, `ORDER_SERVICE_UNAVAILABLE` (503).

| Variable | Description | Default |
|---|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | The payment database | `jdbc:mysql://localhost:3306/payment_db`, `PaymentService_user`, empty |
| `ORDER_SERVICE_URL` | Base URL of order-service: a service name, resolved through Eureka | `http://order-service` |
| `EUREKA_URL` | Where the service registers and reads the registry | `http://localhost:8761/eureka/` |
| `AUTH_ISSUER`, `AUTH_JWKS_URI` | Token issuer and public keys, as in the cart service | `http://localhost:8081`, `http://user-auth-service/oauth2/jwks` |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka broker addresses | `localhost:9092` |
| `PAYMENT_PUBLIC_BASE_URL` | Public address for gateway callbacks and webhooks | `http://localhost:8085` |
| `PAYMENT_DEFAULT_GATEWAY` | Gateway used when the customer does not choose | `RAZORPAY` |
| `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET` | Razorpay test mode keys | empty (Razorpay off) |
| `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET` | Stripe test mode keys | empty (Stripe off) |
| `PAYMENT_FAKE_GATEWAY_ENABLED` | Turns the fake gateway on, for development only | `false` |
| `PAYMENT_FAKE_WEBHOOK_URL` | Where the fake gateway sends its webhooks | `PAYMENT_PUBLIC_BASE_URL` + `/payments/webhooks/fake` |

## Events (Kafka)

Services tell each other what happened through Kafka, when the sender does not need an answer: a signup does not wait for the welcome email. Requests that need an answer at once (a price, a stock reservation) stay REST calls.

| Topic | Owner (producer) | Event | Key | Consumers |
|---|---|---|---|---|
| `user-events` | user-auth-service | `UserRegistered`: `userId`, `name`, `email` | user id | notification-service (welcome email) |
| `user-events` | user-auth-service | `PasswordChanged`: `userId`, `name`, `email` (never a password or hash) | user id | notification-service (security notice) |
| `user-events` | user-auth-service | `PasswordResetRequested`: `userId`, `name`, `email`, `resetLink` (holds the one-time token), `expiresAt` | user id | notification-service (reset email) |
| `user-events` | user-auth-service | `TokenRevoked`: `userId`, `tokenId` (the JWT's `jti`), `expiresAt`, for a logout, the other sessions of a password change, and all sessions of a password reset | user id | api-gateway (refuses the token until it expires) |
| `order-events` | order-service | `OrderPlaced`: `orderId`, `userId`, `customerEmail`, `items` (product, name, price, quantity), `totalAmount`, `shippingAddress` | order id | notification-service (order confirmation) |
| `order-events` | order-service | `OrderCancelled`: `orderId`, `userId`, `customerEmail`, `totalAmount`, `reason` (`CUSTOMER` or `PAYMENT_TIMEOUT`) | order id | product-service (puts the stock back), payment-service (closes the open payment link), notification-service |
| `order-events` | order-service | `OrderShipped`: `orderId`, `userId`, `customerEmail`, `carrier`, `trackingNumber` (may be null) | order id | notification-service |
| `order-events` | order-service | `OrderDelivered`: `orderId`, `userId`, `customerEmail` | order id | notification-service |
| `payment-events` | payment-service | `PaymentSucceeded`: `paymentId`, `orderId`, `userId`, `customerEmail`, `amount`, `currency`, `gateway`, `gatewayPaymentId` | order id | order-service (confirms the order), notification-service (receipt) |
| `payment-events` | payment-service | `PaymentFailed`: `paymentId`, `orderId`, `customerEmail`, `amount`, `reason` (`FAILED` or `EXPIRED`) | order id | notification-service |

Every event is one JSON object with `eventId`, `eventType` and `occurredAt` first, then its own fields, for example:

```json
{ "eventId": "0b6f3c1e-8a0d-4f7e-9a51-3c2a6e8d9f10", "eventType": "UserRegistered", "occurredAt": "2026-10-25T15:38:42.512Z",
  "userId": "a8c1e6ff-556d-4dd2-a238-148b3fca351c", "name": "Asha Gamit", "email": "asha@example.com" }
```

**Transactional outbox.** A service never sends an event straight to Kafka from its business code. It writes the event into its own `outbox_event` table in the same database transaction as the change (the new account), so both are saved or neither is. A relay sends unsent rows to Kafka every second, oldest first, waits for the broker to confirm each one (`acks=all`), and marks it sent. If Kafka is down, events wait in the table and go out when it is back; nothing is lost and no event is sent for a change that was rolled back. Sent rows are deleted after 7 days.

Delivery is therefore *at least once*: an event can arrive twice (for example if the service stops right after Kafka accepted it), so every consumer must handle a repeat safely (releasing stock twice releases it once; the notification service remembers the `eventId`s it has handled). Events with the same key go to the same partition and are read in order: an order's `OrderCancelled` is never read before its `OrderPlaced`.

**When a consumer fails.** Each consumer is a consumer group of its own (`product-service`, `notification-service`), so each reads every event. If handling an event fails, it is tried again every 2 seconds, 10 times, while later events of that partition wait. If it still fails, or if the message can never work (not JSON, missing fields; these are not retried), it is published to the consumer's own dead-letter topic, for example `order-events.product-service.DLT`, with the error in its headers, and the consumer carries on. Nothing is lost: dead-letter messages can be read and replayed once the cause is fixed.

```
MSYS_NO_PATHCONV=1 docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 --topic user-events --from-beginning --property print.key=true
```

(`MSYS_NO_PATHCONV=1` is only needed in Git Bash on Windows.)

## Notification Service

The notification service has no API. It reads `user-events`, `order-events` and `payment-events` from Kafka and sends an email for each event a customer should hear about:

| Event | Email |
|---|---|
| `UserRegistered` | "Welcome to Vanmool", to the new account's address |
| `PasswordChanged` | "Your Vanmool password was changed": when (IST), that other devices were signed out, and what to do if it was not them |
| `PasswordResetRequested` | "Reset your Vanmool password": the link, until when it works (IST), and that nothing changes if the email is ignored |
| `OrderPlaced` | "Your Vanmool order 7c9e6679 is placed": each product with its quantity and line total, the total in rupees (₹1,359.98), and the delivery address |
| `OrderCancelled` | "Your Vanmool order 7c9e6679 is cancelled", saying so when the payment did not arrive in time |
| `OrderShipped` | "Your Vanmool order 7c9e6679 is on its way": the carrier and the tracking number |
| `OrderDelivered` | "Your Vanmool order 7c9e6679 is delivered" |
| `PaymentSucceeded` | "Payment received for your Vanmool order 7c9e6679": amount, order, time (IST), gateway and the gateway's payment reference |
| `PaymentFailed` | "Your Vanmool payment did not go through": no money taken, and how to try again |

- **Each email once.** Kafka can deliver an event twice. The service records the id of every event whose email went out in Redis (`notification:event:{eventId}`, kept 7 days) and skips an event it has seen. The record is written after the email was sent: if the mail server fails, nothing is recorded and the event is tried again. If Redis itself is unreachable, the email is sent anyway (one email too many is better than none).
- **Failures.** An email that cannot be sent is tried again every 2 seconds, 10 times, then the event is published to `user-events.notification-service.DLT`, `order-events.notification-service.DLT` or `payment-events.notification-service.DLT`. A message that is not a valid event goes there at once.
- **SMTP.** Locally the emails go to Mailpit: open http://localhost:8025 to read them. In a deployment the same settings point at a real SMTP service such as Amazon SES.

| Variable | Description | Default |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka broker addresses | `localhost:9092` |
| `MAIL_HOST`, `MAIL_PORT` | SMTP server | `localhost`, `1025` (Mailpit) |
| `MAIL_USERNAME`, `MAIL_PASSWORD` | SMTP login, if the server needs one | empty |
| `MAIL_SMTP_AUTH`, `MAIL_SMTP_STARTTLS` | `true` for a real SMTP service | `false` |
| `MAIL_FROM` | Sender of every email | `Vanmool <orders@vanmool.example>` |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | Redis, for the ids of handled events | `localhost`, `6379`, empty |

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
- [x] Profile management: change the name, change the password (other sessions end, security email)
- [x] Password reset through a one-time emailed link (only its hash stored, 30 minutes, at most 3 an hour)
- [ ] AWS deployment: EC2, Elastic Beanstalk, VPC, RDS
- [x] Redis: product cache
- [x] Redis: cart storage
- [x] Product search, pagination, sorting and filtering
- [x] Product details: several images (with alt text) and specifications per product
- [x] Product and stock changes need the SELLER or ADMIN role; tokens checked with keys fetched by service name
- [x] Kafka domain events through a transactional outbox: `UserRegistered`
- [x] Payment Service: Razorpay and Stripe (test mode), signed webhooks, callbacks, reconciliation, one open payment per order, unpaid orders cancelled after an hour
- [x] Cart service: carts in Redis, current prices from the product service, JWTs checked with the user-auth service's public keys
- [x] Order service: checkout from the cart with stock reservations, Idempotency-Key, order history, cancellation
- [x] Order tracking: the operations team ships and delivers orders (carrier, tracking number); each order keeps its status history
- [x] Stock and stock reservations (inside the product service)
- [x] Notification service: welcome, order, payment, cancellation, shipping and delivery emails from Kafka events, each sent once
- [x] Spring Cloud: Eureka service discovery; services call each other by name with client-side load balancing
- [x] Spring Cloud: API Gateway with routes by path, request ids and an access log; internal endpoints not exposed
- [x] Docker: an image per service; the whole stack (MySQL, Redis, Kafka, Mailpit, eight services) in Docker Compose

## Future scope

- Producer onboarding and tools for producers to manage their own listings
- Richer product search and recommendations
- Logistics integration: book shipments and receive delivery updates from the carriers' APIs instead of the operations team
- Web and mobile clients
