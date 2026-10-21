"""
Load / resilience test script for the IMS microservices project.

Requires: pip install requests --break-system-packages
Requires: docker compose up --build   (running locally, gateway on localhost:8080)

Run each test independently — read the code before running so you can
explain exactly what it does and why, in an interview.

    python ims_load_test.py idempotency
    python ims_load_test.py concurrency
    python ims_load_test.py flash [n_orders] [starting_qty]   (default 500 orders / 100 units)
    python ims_load_test.py circuit-breaker   (see instructions in that function)

Every call through the gateway now needs a JWT (Step 11), so each scenario logs
in first: ADMIN to create a product, CUSTOMER to place orders. Credentials
default to the demo users in api-gateway's application.yml and can be overridden
with IMS_ADMIN_USER / IMS_ADMIN_PASSWORD / IMS_CUSTOMER_USER /
IMS_CUSTOMER_PASSWORD. See the README for raising RATE_LIMIT_* before `flash`.
"""

import concurrent.futures
import os
import requests
import sys
import time
import uuid

# The gateway is the only front door for traffic under test: it carries the JWT
# check and the rate limiter, so a scenario that skipped it would not be testing
# what production does.
BASE_URL = "http://localhost:8080"

# inventory-service's own port, used ONLY for reads this script asserts on (see
# get_stock) and for /reserve (see reserve_stock_direct). The gateway neither
# routes nor permits /reserve any more, and a measurement must not be able to
# fail because the rate-limit bucket the test just drained answered 429.
INVENTORY_URL = "http://localhost:8081"

# Demo users from api-gateway/src/main/resources/application.yml. They are
# env-overridable so that changing CUSTOMER_PASSWORD_HASH / ADMIN_PASSWORD_HASH
# does not mean editing this script.
ADMIN_USER = os.getenv("IMS_ADMIN_USER", "admin1")
ADMIN_PASSWORD = os.getenv("IMS_ADMIN_PASSWORD", "admin-demo-pw")
CUSTOMER_USER = os.getenv("IMS_CUSTOMER_USER", "customer1")
CUSTOMER_PASSWORD = os.getenv("IMS_CUSTOMER_PASSWORD", "customer-demo-pw")


def login(username, password):
    """
    Exchange demo credentials for an HS256 JWT at the gateway's demo
    /auth/login (AuthController). /auth/login is not a gateway route, so the
    RequestRateLimiter default-filter does not apply to it — logging in once per
    scenario costs nothing from the caller's token bucket.

    Raises on failure: every scenario below is meaningless without a token, and
    a silent 401 would look like a broken service instead of a broken login.
    """
    resp = requests.post(
        f"{BASE_URL}/auth/login",
        json={"username": username, "password": password},
        timeout=10,
    )
    if not resp.ok:
        raise RuntimeError(f"login as {username} failed: {resp.status_code} {resp.text}")
    token = resp.json().get("accessToken")
    if not token:
        raise RuntimeError(f"login as {username} returned no accessToken: {resp.text}")
    print(f"Logged in as {username} -> token acquired (ttl {resp.json().get('expiresInSeconds')}s)")
    return token


def bearer(token):
    """Authorization header, or nothing at all when called without a token."""
    return {"Authorization": f"Bearer {token}"} if token else {}


def create_test_product(sku, quantity, token=None):
    """
    Create a fresh product with a given starting stock quantity.
    POST /api/inventory/products needs ROLE_ADMIN at the gateway
    (SecurityConfig), so pass an admin token from login().
    """
    resp = requests.post(
        f"{BASE_URL}/api/inventory/products",
        json={
            "sku": sku,
            "name": "Load Test Widget",
            "description": "created by load test script",
            "price": 9.99,
            "quantity": quantity,
            "reorderThreshold": 1,
        },
        headers=bearer(token),
        timeout=5,
    )
    print(f"Created product {sku} qty={quantity} -> status {resp.status_code}")
    return resp


def get_stock(sku):
    """
    Read current stock for a sku straight from inventory-service on :8081,
    deliberately NOT through the gateway. This is the script's measuring
    instrument, not traffic under test: it must not need a token, and it must
    not be able to come back 429 because the scenario just drained the caller's
    rate-limit bucket — that would turn a passing system into a failed
    assertion. Returns None if the read itself failed.
    """
    try:
        resp = requests.get(f"{INVENTORY_URL}/api/inventory/products/{sku}", timeout=10)
        return resp.json().get("quantity") if resp.ok else None
    except requests.exceptions.RequestException:
        return None


def place_order(sku, qty, token=None, idempotency_key=None, timeout=10):
    """
    Place an order via the public order API. Needs any authenticated user
    (ROLE_CUSTOMER is enough); "unitPrice" is sent but ignored — since Step 12
    inventory-service prices each line itself.

    NOTE: OrderRequest still has NO idempotencyKey field; the optional
    Idempotency-Key *header* is the way to make a retry return the first order
    instead of creating a second (OrderController). Left unset by the scenarios
    below, because each of them wants N genuinely distinct orders — to test
    idempotency of the stock decrement itself, see reserve_stock_direct().
    """
    payload = {
        "customerEmail": "loadtest@example.com",
        "items": [{"sku": sku, "quantity": qty, "unitPrice": 9.99}],
    }
    headers = bearer(token)
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key
    start = time.time()
    try:
        resp = requests.post(
            f"{BASE_URL}/api/orders", json=payload, headers=headers, timeout=timeout
        )
        elapsed = time.time() - start
        return resp.status_code, elapsed, resp.json() if resp.content else {}
    except requests.exceptions.RequestException as e:
        elapsed = time.time() - start
        return None, elapsed, {"error": str(e)}


def reserve_stock_direct(sku, qty, idempotency_key):
    """
    Calls inventory-service's /api/inventory/reserve endpoint on :8081 DIRECTLY,
    bypassing both order-service and the gateway. This is the only way to
    control the idempotencyKey from outside, and it's the actual field
    ProductServiceImpl checks against the stock_reservations table (see
    reserveStock() in ProductServiceImpl.java).

    Going round the gateway is deliberate, not a shortcut: since Step 11 the
    gateway does not route /api/inventory/reserve and SecurityConfig denyAll()s
    it, because it is the internal saga endpoint order-service calls over Feign.
    Hitting :8081 is exactly what order-service does; no token is involved
    because inventory-service has no security of its own.
    """
    payload = {"sku": sku, "quantity": qty, "idempotencyKey": idempotency_key}
    try:
        resp = requests.post(f"{INVENTORY_URL}/api/inventory/reserve", json=payload, timeout=10)
        return resp.status_code, resp.json() if resp.content else {}
    except requests.exceptions.RequestException as e:
        return None, {"error": str(e)}


def classify(status, body):
    """
    Where did one request actually end up? HTTP status is checked before the
    response body, because the gateway's own answers (429 from the rate
    limiter, 401/403 from the JWT filter) never reach order-service and so carry
    no order status at all. Only once a request got through is the body's
    `status` the answer - POST /api/orders returns 201 for CONFIRMED, REJECTED
    and FAILED alike, since "we received your order and it was declined" is not
    an HTTP error.
    """
    if status is None:
        return "transport_error"
    if status == 429:
        return "rate_limited"
    if status in (401, 403):
        return "auth_rejected"
    outcome = body.get("status")
    return outcome if outcome in ("CONFIRMED", "REJECTED", "FAILED", "PENDING") else "other"


def percentile(values, pct):
    """Nearest-rank percentile; good enough for a few hundred samples."""
    if not values:
        return None
    ordered = sorted(values)
    index = min(len(ordered) - 1, int(round((pct / 100.0) * len(ordered) + 0.5)) - 1)
    return ordered[index]


def test_idempotency(n_requests=10):
    """
    Fires N concurrent reservation requests against inventory-service's
    /reserve endpoint directly, all using the SAME idempotencyKey. Per
    ProductServiceImpl.reserveStock: the first request to actually commit
    writes a row in stock_reservations keyed by idempotencyKey; every other
    request (concurrent or sequential) finds that row and replays its result
    instead of decrementing stock again.

    Expected if idempotency works: stock drops by exactly `order_qty`,
    regardless of n_requests.
    Expected if broken: stock drops by order_qty * n_requests.
    """
    sku = f"IDEMP-{uuid.uuid4().hex[:6]}"
    starting_qty = 100
    order_qty = 5
    key = str(uuid.uuid4())

    create_test_product(sku, starting_qty, token=login(ADMIN_USER, ADMIN_PASSWORD))

    print(f"\nFiring {n_requests} concurrent /reserve calls, same idempotencyKey={key}")
    with concurrent.futures.ThreadPoolExecutor(max_workers=n_requests) as ex:
        futures = [ex.submit(reserve_stock_direct, sku, order_qty, key) for _ in range(n_requests)]
        results = [f.result() for f in futures]

    statuses = [r[0] for r in results]
    print(f"Status codes: {statuses}")

    remaining = get_stock(sku)
    expected_if_correct = starting_qty - order_qty
    expected_if_broken = starting_qty - (order_qty * n_requests)

    print(f"Remaining stock: {remaining}")
    print(f"Expected if idempotency works: {expected_if_correct}")
    print(f"Expected if idempotency is broken: {expected_if_broken}")


def test_concurrency(n_requests=20, starting_qty=10, order_qty=1):
    """
    Fires N concurrent orders for the SAME sku with LIMITED stock and
    DIFFERENT idempotency keys (this is a genuine race, not a duplicate).
    With optimistic locking working correctly, exactly `starting_qty`
    orders should succeed and the rest should fail with insufficient stock
    — never oversold.
    """
    sku = f"CONC-{uuid.uuid4().hex[:6]}"
    create_test_product(sku, starting_qty, token=login(ADMIN_USER, ADMIN_PASSWORD))
    customer_token = login(CUSTOMER_USER, CUSTOMER_PASSWORD)

    print(f"\nFiring {n_requests} concurrent orders against {starting_qty} units of stock")
    with concurrent.futures.ThreadPoolExecutor(max_workers=n_requests) as ex:
        futures = [
            ex.submit(place_order, sku, order_qty, customer_token) for _ in range(n_requests)
        ]
        results = [f.result() for f in futures]

    # Classify on HTTP status first and only then on the body: a 429 from the
    # rate limiter or a 403 from the gateway carries no "status" field at all,
    # and the old "not in (REJECTED, FAILED)" test counted those as successes -
    # i.e. as sales that never happened.
    outcomes = [classify(status, body) for status, _, body in results]
    succeeded = outcomes.count("CONFIRMED")
    rejected = outcomes.count("REJECTED")
    failed = outcomes.count("FAILED")

    print(f"Succeeded: {succeeded} (should be <= {starting_qty})")
    print(f"Rejected (business, insufficient stock): {rejected}")
    print(f"Failed (infra error): {failed}")
    for name in ("rate_limited", "auth_rejected", "transport_error", "other"):
        if outcomes.count(name):
            print(f"{name}: {outcomes.count(name)}")

    remaining = get_stock(sku)
    print(f"Remaining stock: {remaining} (should be {starting_qty - succeeded * order_qty}, never negative)")


def test_flash(n_orders=500, starting_qty=100, order_qty=1):
    """
    FLASH SALE: 100 units of stock, 500 customers hitting "buy" at the same
    instant, one unit each. This is test_concurrency turned up until the
    interesting failure modes appear - the point is not that 400 orders are
    rejected (they should be), it is that the system never sells unit 101.

    The invariant asserted at the end is `CONFIRMED + final stock ==
    starting_qty`: every unit is either sold exactly once or still on the shelf.
    That is deliberately stronger than "exactly 100 CONFIRMED" and it stays true
    no matter how many requests the rate limiter turned away, because a throttled
    request never touched stock. The second assertion is that stock never went
    negative - the thing @Version optimistic locking on Product exists to
    prevent.

    Raise the gateway's limits before running this (see README), or ~440 of the
    500 orders come back 429 and the race is never really run: the limiter's
    bucket is keyed on the user id, so all 500 orders share one bucket.
    """
    sku = f"FLASH-{uuid.uuid4().hex[:6]}"
    created = create_test_product(sku, starting_qty, token=login(ADMIN_USER, ADMIN_PASSWORD))
    if created.status_code != 201:
        print(f"ABORT: could not create {sku} ({created.status_code} {created.text})")
        sys.exit(1)

    # One token for all threads: a real flash sale is many customers, but one
    # customer is what makes the rate limiter's per-principal bucket visible,
    # and it keeps the test to a single login.
    customer_token = login(CUSTOMER_USER, CUSTOMER_PASSWORD)

    print(
        f"\nFLASH: firing {n_orders} concurrent orders of qty {order_qty} "
        f"against {starting_qty} units of {sku}"
    )
    workers = min(n_orders, 500)
    start = time.time()
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as ex:
        futures = [
            # 30s rather than the usual 10s: under 500-way contention a request
            # can spend a long time in inventory-service's optimistic-lock retry
            # loop, and a client timeout would be scored as a system failure.
            ex.submit(place_order, sku, order_qty, customer_token, None, 30)
            for _ in range(n_orders)
        ]
        results = [f.result() for f in futures]
    wall = time.time() - start

    outcomes = [classify(status, body) for status, _, body in results]
    latencies = [elapsed for _, elapsed, _ in results]
    confirmed = outcomes.count("CONFIRMED")
    rejected = outcomes.count("REJECTED")
    failed = outcomes.count("FAILED")
    rate_limited = outcomes.count("rate_limited")

    print(f"\n--- {n_orders} orders in {wall:.1f}s ({n_orders / wall:.0f} req/s offered) ---")
    print(f"CONFIRMED      : {confirmed}")
    print(f"REJECTED       : {rejected}")
    print(f"FAILED         : {failed}")
    print(f"429 rate limited: {rate_limited}")
    for name in ("auth_rejected", "transport_error", "other", "PENDING"):
        if outcomes.count(name):
            print(f"{name:<16}: {outcomes.count(name)}")
    print(f"total accounted : {len(outcomes)}")

    # Never let a bucket stay a bare number. "other" means the request came back
    # with neither an order status nor a status code this script expects - an
    # unhandled 500 from order-service, say - which is exactly the kind of thing
    # a load test exists to surface, so print what it actually was.
    for name in ("other", "transport_error"):
        unexplained = [
            (status, body) for status, _, body in results if classify(status, body) == name
        ]
        for status, body in unexplained[:5]:
            detail = body.get("error") or body.get("message") or body
            print(f"  {name} sample: HTTP {status} {str(detail)[:200]}")
        if len(unexplained) > 5:
            print(f"  ... and {len(unexplained) - 5} more {name}")
    print(
        f"latency        : avg {sum(latencies) / len(latencies):.3f}s "
        f"p95 {percentile(latencies, 95):.3f}s max {max(latencies):.3f}s"
    )

    final_stock = get_stock(sku)
    print(f"final stock     : {final_stock} (started at {starting_qty})")

    # --- assertions --------------------------------------------------------
    passed = True
    if final_stock is None:
        print("FAIL: could not read final stock from inventory-service")
        passed = False
    else:
        sold = confirmed * order_qty
        if sold + final_stock == starting_qty:
            print(f"PASS: CONFIRMED*qty + final stock == {starting_qty} ({sold} + {final_stock})")
        else:
            print(
                f"FAIL: CONFIRMED*qty + final stock == {sold + final_stock}, expected {starting_qty} "
                f"- stock was lost or sold twice"
            )
            passed = False
        if final_stock >= 0:
            print("PASS: stock never went negative (no oversell)")
        else:
            print(f"FAIL: stock is {final_stock} - oversold")
            passed = False

    # --- diagnosis ---------------------------------------------------------
    if rate_limited:
        print(
            f"\nNOTE: {rate_limited} of {n_orders} orders were throttled at the gateway and never "
            "reached order-service, so the race was only partly exercised. Raise "
            "RATE_LIMIT_REPLENISH_RATE / RATE_LIMIT_BURST_CAPACITY and recreate api-gateway "
            "(see README) for a full run."
        )
    if failed:
        reasons = {}
        for status, _, body in results:
            if classify(status, body) == "FAILED":
                reason = body.get("failureReason") or "(none reported)"
                reasons[reason] = reasons.get(reason, 0) + 1
        print(f"\n{failed} orders ended FAILED. Distinct failureReason values:")
        for reason, count in sorted(reasons.items(), key=lambda kv: -kv[1]):
            print(f"  {count:>4} x {reason}")
        print(
            "\nWhy FAILED rather than REJECTED: REJECTED means inventory-service answered "
            "'not enough stock', which is a business decision, while FAILED means order-service "
            "never got a usable answer at all. Under this much contention the usual cause is "
            "inventory-service losing the @Version race three times for one request, giving up with "
            "ConcurrentStockUpdateException -> HTTP 503, which the adapter's fallback turns into "
            "InventoryServiceUnavailableException. The other possible cause is the inventoryService "
            "circuit breaker opening once enough of those 503s land, after which further orders fail "
            "fast without being tried; notPermittedCalls > 0 or state OPEN under "
            "http://localhost:8082/actuator/health is how you tell the two apart. "
            "Either way the saga releases whatever it reserved, so the stock invariant above holds."
        )

    if not passed:
        sys.exit(1)


def test_circuit_breaker(n_requests=15):
    """
    MANUAL STEPS REQUIRED before running this:
      1. docker compose up --build   (let everything start)
      2. docker compose stop inventory-service
      3. Then run: python ims_load_test.py circuit-breaker

    This fires repeated orders while inventory-service is down and times
    each response. Early requests should be SLOW (waiting through retry +
    timeout budget before failing). After the circuit breaker trips
    (CLOSED -> OPEN), later requests should fail FAST because the breaker
    short-circuits the call instead of waiting on a dead dependency.

    That latency drop, timestamped and logged, IS your real number —
    e.g. "average response time dropped from 2.1s to 40ms once the circuit
    breaker opened." Check actuator/health on order-service to confirm the
    state transition: http://localhost:8082/actuator/health
    """
    sku = f"CB-{uuid.uuid4().hex[:6]}"
    print("NOTE: this assumes the product/sku already exists or inventory-service")
    print("was up when it was created — if inventory-service is down, product")
    print("creation itself will fail. Create the product BEFORE stopping the service.")

    for i in range(n_requests):
        status, elapsed, body = place_order(sku, 1)
        print(f"Request {i+1}: status={status} elapsed={elapsed:.3f}s body={body}")
        time.sleep(0.2)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    test = sys.argv[1]
    if test == "idempotency":
        test_idempotency()
    elif test == "concurrency":
        test_concurrency()
    elif test == "flash":
        # optional positional overrides: python ims_load_test.py flash 500 100
        n_orders = int(sys.argv[2]) if len(sys.argv) > 2 else 500
        starting_qty = int(sys.argv[3]) if len(sys.argv) > 3 else 100
        test_flash(n_orders=n_orders, starting_qty=starting_qty)
    elif test == "circuit-breaker":
        test_circuit_breaker()
    else:
        print(f"Unknown test: {test}")
        print(__doc__)