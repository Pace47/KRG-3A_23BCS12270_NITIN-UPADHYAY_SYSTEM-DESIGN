# Scaling & Resilience — Food Delivery Platform

## 1. Load Balancing Strategy

- Edge: Use cloud load balancers (AWS ALB/Google Cloud LB) for TLS termination and HTTP routing.
- API Gateway: horizontally scaled, behind ALB with health checks.
- Service-level LB: Kubernetes Service (ClusterIP) + Ingress + sidecar service mesh (Istio) for advanced traffic control.
- Sticky sessions: avoid sticky sessions for core APIs; keep services stateless and store session state in Redis.

## 2. Horizontal vs Vertical Scaling

- Prefer horizontal scaling for microservices: easier fault isolation and autoscaling by CPU/RPS/custom metrics.
- Vertical scaling reserved for databases where single-node resources matter (increase instance class for leader DB), but long-term rely on read-replicas and sharding.
- Rationale: horizontal scaling supports multi-AZ distribution and rapid reaction to traffic spikes.

## 3. Caching Strategy

- CDN (CloudFront) for static assets and images.
- Redis (in-memory) for:
  - Hot menu data and restaurant metadata TTL=30–300s.
  - Token bucket counters for rate limiting.
  - Geospatial caches (tile-based / S2 cells) for nearby courier lookup (TTL short, e.g., 2–10s).
  - Session and ephemeral locks (assignments) with short TTL.
- Cache invalidation: use pub/sub events (menu.update -> invalidate keys across instances). For robust invalidation, maintain versioned keys (menu:v123).

## 4. Database scaling

- OLTP (Postgres)
  - Primary in multi-AZ, read replicas for read-heavy endpoints (catalog, analytics queries via replicas).
  - Use connection pool (PgBouncer) to avoid overload.
  - Partition large tables (`orders`) by time (monthly) or by restaurant_id for write distribution.
  - Sharding strategy: shard by restaurant_id for write locality; for user-centric queries also build user-order indexes in a separate table or use co-located shards.

- NoSQL (Cassandra)
  - Event store and time-series (partner locations, telemetry) for high write throughput and eventual consistency.

- Analytical store
  - Kafka -> Stream processing -> OLAP (Redshift/BigQuery) for dashboards.

## 5. Failure Handling

- Transient external failures: retry with exponential backoff + jitter (max 3 attempts). For idempotent operations use safe retries.
- Circuit Breaker: wrap calls to PSPs, Maps API to fail fast and avoid overloading.
- Bulkhead: isolate resource pools between critical flows (order placement) and non-critical (analytics).
- Fallbacks: when search backend (ElasticSearch) is down, return cached lists or degrade to minimal static list.

## 6. Observability & Incident Response

- Distributed Tracing: OpenTelemetry across services with 1 request = 1 trace id.
- Metrics: Prometheus + Grafana to track latencies, error rates, assignment queue lengths.
- Alerting: SLO-based alerts for P95 latency and error budget burn.

## 7. One Real Bottleneck & Solution

- Bottleneck: Delivery-area matching (finding nearest available courier) becomes a hot path under surge—many orders in small region cause high QPS to geospatial queries and frequent locks.

- Why it's real: naive nearest-neighbour PostGIS queries at high QPS are slow and DB-bound; frequent assignment requires fast responses (<200 ms) and temporary locks to avoid double-assign.

- Solution (multi-pronged):
  1. Geospatial tiling + Redis cache: maintain in Redis a mapping of S2/tile -> available partner IDs updated every 1–2s from partner heartbeats. Querying Redis is O(1) and returns candidate list.
  2. In-memory candidate scoring: AssignmentService has a local LRU cache of recent candidates and uses precomputed ETA heuristics (distance * avg_speed + partner load).
  3. Short-lived distributed locks: use Redis SETNX with small TTL to reserve partner while confirming assignment with partner (avoid DB row locks).
  4. Worker pool and async confirmation: assignment returns quick tentative accept request to courier; if timeout occurs, fallback to next candidate—no blocking DB transactions.
  5. Partition assignment service by region to limit blast radius; each instance responsible for a set of S2 cell ranges.

- Result: reduces DB and PostGIS load, lowers latency to <150 ms for candidate selection and assignment under surge.

## 8. Disaster Recovery & Backups

- RPO/RTO: aim RPO=minutes for transactional DB by using WAL shipping and cross-region replicas; RTO within 30–60 minutes for failover with automated failover procedures (manual verification for payment leader election).
- Backups: periodic base backups + incremental WAL backups to object store.

## 9. Multi-region Deployment

- Deploy API Gateways in each region; user traffic routed to nearest region.
- Global data: user profile stored in primary region with async replication to other regions; orders stay in local region for compliance and latency.
- Cross-region design: minimize cross-region synchronous calls; rely on async replication for eventual consistency where acceptable.

---
End of scaling & resilience
