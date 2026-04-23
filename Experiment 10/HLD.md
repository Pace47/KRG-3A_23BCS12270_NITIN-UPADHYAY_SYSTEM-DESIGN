# High-Level Design — Food Delivery Platform

## Overview
This document describes the high-level design of a production-grade food delivery platform (akin to Swiggy/Zomato). It focuses on components, interactions, requirements and trade-offs justified for real-world operation at scale.

## 1. Functional Requirements (detailed)

- User-facing
  - User registration & authentication (email, phone + OTP, social login).
  - Browse restaurants by location, cuisine, ratings, price bracket.
  - View restaurant menus, dynamic item availability, modifiers (size, add-ons).
  - Search by dish, restaurant, filters (vegetarian/vegan, delivery time, offers).
  - Cart management: add/update/remove items, apply promo codes.
  - Checkout: apply wallet/gift card, choose payment method (card, UPI, netbanking, COD).
  - Real-time order tracking: order status and live courier location.
  - Ratings & reviews: rate restaurants, delivery partner, item-level feedback.

- Restaurant-facing
  - Restaurant onboarding & KYC, menu management (CRUD), opening hours.
  - Order acceptance / preparation status updates.
  - Analytics dashboard: sales, top items, cancellations.

- Delivery-facing
  - Courier app for availability, assignment acceptance, pickup/drop updates, navigation link.
  - Online/offline status, earnings dashboard.

- Admin/Operator
  - Manage promotions, platform fees, refunds, dispute resolution.
  - Real-time monitoring & alerts (SLA breaches, surge zones).

## 2. Non-Functional Requirements

- Latency
  - API P95 latency: <= 200 ms for read endpoints (search, menu). P99 <= 500 ms.
  - Critical write paths (place order): P95 <= 500 ms.

- Availability
  - 99.99% for user-facing APIs across regions (SLO). Partial degradations allowed for analytics.

- Scalability
  - Support 10M daily active users, 500k concurrent orders peak, 100k active couriers.
  - Elastic scale across traffic spikes (lunch/dinner).

- Consistency & Durability
  - Orders and payments must be strongly consistent and durable.

- Security
  - PCI-DSS for card handling; tokenized payments via third-party providers.
  - Encrypt PII at rest & in transit; role-based access controls for admin.

## 3. System Architecture Diagram (description for draw.io)

Diagram layout (left-to-right):

- Clients (leftmost): Mobile Apps (User, Restaurant, Courier), Web UI.
- Edge Layer: CDN (for assets), WAF, and TLS termination -> API Gateway (regional).
- API Layer: API Gateway routes to microservices (Auth, Catalog, Order, Payment, Delivery, Notification, Pricing & Promotions, Analytics).
- Data Plane: message broker (Kafka) underpins async flows; Redis (caching / pub-sub), real-time gateway (WebSocket / MQTT) for live tracking.
- Storage Layer: OLTP RDBMS (Postgres) for core transactional data with read-replicas; NoSQL (Cassandra) for event store and high-volume time-series; S3-like object store for images/receipts.
- Platform infra: Kubernetes cluster for services, Service Mesh (Istio) for observability & traffic control, Prometheus + Grafana for metrics, ELK for logs.
- External Integrations: Payment Gateway, Maps/Geocoding Provider, SMS/Email provider.

Draw connectors:
- Clients -> CDN/WAF -> API Gateway -> services (Auth/Catalog/Order/etc.).
- Order service <-> Payment service synchronous call during placement (with idempotency key); Order service publishes to Kafka topics (order.created).
- Delivery service subscribes to order.created, triggers assignment engine and notifies courier app via real-time gateway.
- Analytics pipeline consumes Kafka topics and writes to OLAP (e.g., Redshift/BigQuery).

Label cross-cutting concerns: Monitoring, Tracing (OpenTelemetry), Circuit Breakers.

## 4. High-Level Components

- API Gateway: routing, auth token validation, rate-limiting, basic request validation.
- Auth Service: OAuth2/JWT issuance, session management, identity provider integration.
- Catalog Service: restaurants, menus, availability, search index (ElasticSearch).
- Pricing & Promotions Service: computes dynamic pricing, delivery fees, promotions, vouchers.
- Order Service: transactional core for order lifecycle, persistent in RDBMS; orchestrates payments, kitchen/restaurant flows.
- Payment Service: integrates with PSPs, handles refunds, stores minimal payment metadata and tokens.
- Delivery Service: courier registry, assignment engine, route integration, ETA calculations.
- Real-time Gateway: WebSocket/MQTT cluster for push updates, tracking updates.
- Notification Service: email/SMS/push orchestration.
- Cache: Redis for hot reads (menus, sessions, token bucket rate-limiting), geospatial quick lookups.
- Message Broker: Kafka for async, durable event streams connecting services.
- Analytics Pipeline: stream processing (Flink/Kafka Streams) → OLAP store.

## 5. Data Flow: Order Placement (step-by-step)

1. User places order → Mobile client calls `POST /v1/orders` with cart and `Idempotency-Key`.
2. API Gateway authenticates request, forwards to Order Service.
3. Order Service validates items by calling Catalog Service (cache-first) to confirm availability & compute preparation time.
4. Order Service computes price via Pricing Service (synchronous call / cached rules), applies promotions via Promotions Service.
5. Order Service reserves stock/slot by writing a transactional row in Orders DB (ACID). A payment intent is created via Payment Service.
6. Payment executed (synchronous or asynchronous depending on method). Use Idempotency-Key to avoid double-charging.
7. On payment success, Order Service transitions order to `CONFIRMED`, persists event and publishes `order.confirmed` to Kafka.
8. Restaurant receives push notification (Restaurant App) and updates prep status.
9. Delivery Service consumes `order.confirmed`, runs assignment algorithm (nearby couriers) and publishes assignment event.
10. Courier receives assignment; pickup and delivery lifecycle events are updated back to Order Service and published to Kafka.
11. Real-time Gateway relays status & courier location to user app.
12. After delivery, final payment settlement & commission calculation performed asynchronously; analytics records events.

## 6. CAP Theorem Decisions (service-by-service)

- Order Service: prioritize Consistency + Partition tolerance (CP). Rationale: order correctness, payment linkage, and idempotency matter more than immediate availability.
- Catalog/Search: favor Availability + Partition tolerance (AP) with eventual consistency. Rationale: temporary stale menu data is acceptable to preserve high read availability.
- Delivery/Assignment: lean towards AP for responsiveness (assign quickly even under partitions) but with compensating reconciliation.

Trade-offs:
- Choosing CP for orders increases complexity for failover and requires leader election & multi-AZ RDBMS; but prevents double-booking and payment-order mismatch.

---
End of HLD
