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

Today the catalog stores name, description, price, image and category. Origin, producer, materials and story are planned additions to the product model.

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
| `user-auth-service` | Signup, login, password hashing, JWT, roles | MySQL | Planned |
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
| Maven (wrapper included) | Build | In use |
| Spring Security, JWT, BCrypt, OAuth2 | Authentication and authorization | Planned |
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
└── product-service/     product catalog service
    ├── pom.xml
    └── src/
        ├── main/java/com/vanmool/productservice/
        │   ├── controllers/    REST endpoints
        │   ├── services/       business logic (ProductService and its database implementation)
        │   ├── repositories/   Spring Data JPA repositories
        │   ├── models/         JPA entities
        │   ├── dtos/           request and response objects
        │   └── exceptions/     custom exceptions and the global exception handler
        ├── main/resources/
        │   ├── application.properties
        │   └── db/migration/   Flyway migration scripts (V1__..., V2__...)
        └── test/
```

Each service has its own `pom.xml` with the Spring Boot parent. The root `pom.xml` only lists the services as modules, so one command builds all of them.

## Getting started

### Prerequisites

- JDK 17 or newer
- MySQL 8
- Maven is not required; the repository includes the Maven wrapper.

### 1. Create the database

Run as a MySQL administrator, using your own user name and password:

```sql
CREATE DATABASE product_db;
CREATE USER 'vanmool_user'@'localhost' IDENTIFIED BY 'choose-a-password';
GRANT ALL PRIVILEGES ON product_db.* TO 'vanmool_user'@'localhost';
```

The tables are created by Flyway, not by hand and not by Hibernate. On startup Flyway applies every script in `product-service/src/main/resources/db/migration` that the database has not seen yet, and records it in the `flyway_schema_history` table. Hibernate then only checks that the tables match the entities (`spring.jpa.hibernate.ddl-auto=validate`) and stops the application if they do not.

A schema change is always a new script with the next version number, named `V<version>__<description>.sql` (for example `V1__create_category_and_product_tables.sql`). A script that has already been applied is never edited: Flyway stores a checksum of each script and refuses to start if an applied one changes.

### 2. Set the environment variables

The product service reads its database connection from the environment. `.env.example` lists the variables:

| Variable | Description | Default |
|---|---|---|
| `DB_URL` | JDBC URL of the product database | `jdbc:mysql://localhost:3306/product_db` |
| `DB_USERNAME` | Database user | `ProductService_user` |
| `DB_PASSWORD` | Database password | empty |

- **IntelliJ IDEA:** Run > Edit Configurations > `ProductServiceApplication` > Environment variables, for example `DB_USERNAME=vanmool_user;DB_PASSWORD=choose-a-password`.
- **Shell:** export the variables before running, for example `export DB_PASSWORD=choose-a-password`.

Never commit real credentials. `.env` is git-ignored for this reason.

### 3. Build and test

```
./mvnw verify          # Git Bash, macOS, Linux
.\mvnw.cmd verify      # Windows PowerShell or cmd
```

To build a single service: `./mvnw -pl product-service verify`.

### 4. Run

- **IntelliJ IDEA:** open the `vanmool` folder and run `ProductServiceApplication`.
- **Command line:** `./mvnw -pl product-service spring-boot:run`

The product service listens on `http://localhost:8080`.

## Product Service API

| Method | Path | Success | Errors |
|---|---|---|---|
| GET | `/products` | 200, list of products | |
| GET | `/products/{id}` | 200, the product | 400 invalid id, 404 product not found |
| POST | `/products` | 201, the created product | 400 invalid request body |
| PUT | `/products/{id}` | 200, the replaced product | 400 invalid id or request body, 404 product not found |
| PATCH | `/products/{id}` | 200, the patched product | 400 invalid id, patch or patched product, 404 product not found |
| DELETE | `/products/{id}` | 204, no body | 400 invalid id, 404 product not found |

Product ids are UUIDs generated by the service, for example `/products/3f2c8a9e-5b1d-4c7a-9e2f-8d6b1a4c0e57`. An id that is not a valid UUID returns 400.

Request body for `POST` and `PUT`:

```json
{
  "name": "Handcrafted Bamboo Basket",
  "price": 450,
  "description": "Woven by hand in Dangs, Gujarat",
  "imageUrl": "https://example.com/images/bamboo-basket.jpg",
  "category": "Traditional Crafts"
}
```

Request bodies are validated before anything is saved:

| Field | Rules |
|---|---|
| `name` | required, not blank, at most 255 characters |
| `price` | required, greater than 0, at most 8 digits before and 2 after the decimal point |
| `description` | optional, at most 2000 characters |
| `imageUrl`, `category` | optional, at most 255 characters each |

A request that breaks a rule gets 400 with every failing field, for example:

```json
{ "message": "name: must not be blank; price: must be greater than 0", "status": "Failure" }
```

A new category name creates the category; an existing name reuses it. `PUT` replaces every field of an existing product. It does not create a product at a client-chosen id, because ids are generated by the database.

`PATCH` takes a JSON Patch document (`Content-Type: application/json-patch+json`), applied to the product as `GET` returns it. The patched product must pass the same rules, and the id cannot be changed:

```json
[{ "op": "replace", "path": "/price", "value": 550 }]
```

`DELETE` is a soft delete: the row stays in the database with its `deleted` flag set, and the product no longer appears in any response. Other services will refer to products by id (an order keeps the ids of the products it contains), so a product row is never physically removed.

Errors are returned as:

```json
{ "message": "Product with id 3f2c8a9e-5b1d-4c7a-9e2f-8d6b1a4c0e57 not found", "status": "Failure" }
```

## Testing

The product service currently has one Spring Boot test that starts the full application context, so it needs a running MySQL. Unit tests for the service layer and MockMvc controller tests with JSON assertions are the next part of the roadmap.

## Roadmap

- [x] REST API for products with correct HTTP methods and status codes
- [x] Centralized exception handling with `@RestControllerAdvice`
- [x] Third-party API integration with `RestTemplate`: the first version of the service read and wrote products through the public FakeStore API, behind the same `ProductService` interface, before the database implementation replaced it
- [x] Persistence with Spring Data JPA, Hibernate and MySQL
- [x] Database settings read from environment variables
- [x] Request validation with Bean Validation
- [x] UUID identifiers; shared entity fields through a `@MappedSuperclass`
- [x] Database migrations with Flyway
- [ ] Fetch types, query methods and query tuning
- [ ] Unit tests, MockMvc controller tests, JSON assertions, test doubles
- [ ] User Auth Service: signup and login with BCrypt, JWT, Spring Security, OAuth2
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
