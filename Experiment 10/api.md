# API Design — Food Delivery Platform

## Versioning strategy

- Use URI versioning: `/v1/...` for first stable contract. Version increments occur when breaking changes are introduced.
- Maintain backward compatibility: add new fields optionally; deprecate old endpoints with migration window.

## Auth

- All APIs require `Authorization: Bearer <JWT>` except public endpoints.
- Use short-lived access tokens (15m) + refresh tokens (rotating).

## Idempotency

- All mutating requests that can be retried (order creation, payment) must accept `Idempotency-Key` header.
- Server stores idempotency record (key, request-hash, response) for TTL (24h) for safe retry.

## Rate limiting

- Global per-IP and per-user quotas. Default: 100 requests/min per user; 1000 requests/min per IP during surge windows tuned by AB tests.
- Use token bucket implemented in Redis with local cache of tokens for low-latency checks.
- Strict limits on payment/order creation endpoints (e.g., 10 order attempts/hour per user).

## Endpoints (representative)

### 1) Search restaurants

- GET /v1/restaurants?lat={lat}&lng={lng}&cuisine={}&q={}&limit=20&offset=0
- 200 OK
- Response (excerpt):
  {
    "items": [
      {"id":"r_1","name":"The Spice","rating":4.3,"eta_min":20,"eta_max":30}
    ],
    "count": 123
  }
- Errors: 400 Bad Request (invalid coords), 429 Rate limit

### 2) Get Menu

- GET /v1/restaurants/{restaurant_id}/menu
- Response: JSON with sections, items, modifiers
- Cache: CDN + Redis for 60s; menu updates invalidate caches via pub/sub

### 3) Cart (client-side object; optional server side)

- POST /v1/cart
  - Body: {userId, items: [{menuItemId, qty, modifiers}], coupon}
  - Response: cart preview with price breakdown

### 4) Create Order

- POST /v1/orders
- Headers: `Authorization`, `Idempotency-Key` (required)
- Body (example):
  {
    "user_id":"u_1",
    "restaurant_id":"r_1",
    "items":[{"menu_item_id":"m_1","quantity":2}],
    "address_id":"addr_1",
    "payment_method":"CARD",
    "tip": 200
  }
- Success response (201 Created):
  {
    "order_id":"o_123",
    "status":"CONFIRMED",
    "estimated_pickup_minutes":12,
    "estimated_delivery_minutes":25
  }
- Status codes: 201 Created, 400 Bad Request (validation), 402 Payment Required, 409 Conflict (idempotency collision), 429 Rate limit, 500 Internal Error
- Idempotency handling: server returns same `order_id` & response for repeated requests with same `Idempotency-Key` and identical payload.

### 5) Payment webhooks

- POST /v1/payments/webhook
- External PSP calls this; validate signature. Map PSP event to internal payment status, update order/payment records.
- Respond 200 OK quickly; do minimal processing and enqueue detailed reconciliation job.

### 6) Order status / Tracking

- GET /v1/orders/{order_id}
- GET /v1/orders/{order_id}/tracking (real-time via WebSocket preferred)
- WebSocket endpoint: wss://api.example.com/v1/realtime?token=<ws-token>
  - Subscribe to `order:<order_id>` channel for live status and courier lat/lng.

### 7) Delivery partner endpoints

- POST /v1/partners/{partner_id}/availability
- POST /v1/partners/{partner_id}/location {lat,lng,timestamp}
- Partner location updates are rate-limited (e.g., 1s) and pushed to Redis pub/sub and real-time gateway.

## Error handling and status codes (summary)

- 200 OK: GET success
- 201 Created: resource created (order)
- 202 Accepted: async processing initiated (webhook accepted)
- 400 Bad Request: invalid input
- 401 Unauthorized: missing/invalid token
- 403 Forbidden: operation forbidden
- 404 Not Found: resource missing
- 409 Conflict: duplicate idempotency key with conflicting payload
- 422 Unprocessable Entity: business rule violation (restaurant closed)
- 429 Too Many Requests: rate limiting
- 500 Internal Server Error: unexpected failure

## Payload examples

- Order response (example):
  {
    "order_id":"o_123",
    "status":"PREPARING",
    "items":[{"name":"Paneer Tikka","qty":1,"price":24900}],
    "pricing": {"subtotal":24900,"tax":1245,"delivery":3000,"total":29145}
  }

## Security considerations

- For payments, never log full card details. Use PSP tokens.
- Webhooks validated using HMAC signatures.

---
End of API spec
