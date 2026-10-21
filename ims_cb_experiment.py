"""
Controlled circuit-breaker experiment — measures latency ONLY while actuator
reports inventoryService state is CIRCUIT_OPEN (not HALF_OPEN).

Usage: python ims_cb_experiment.py --feign-cb true --run 1
"""

import argparse
import json
import os
import sys
import time

import requests

GATEWAY = "http://localhost:8080"
ORDER_HEALTH = "http://localhost:8082/actuator/health"
CB_NAME = "inventoryService"
SKU = "CB-VERIFY01"
ORDER_BODY = {
    "customerEmail": "cb-experiment@example.com",
    "items": [{"sku": SKU, "quantity": 1, "unitPrice": 9.99}],
}

# Since Step 11 the gateway requires a JWT on /api/orders. Without one every
# trigger_failure() would be a 401 that never reaches order-service, so the
# breaker would never trip and the experiment would just time out after its
# 180s warmup. Same demo users and env overrides as ims_load_test.py; the login
# helper is copied rather than shared, like the DTO contracts between services.
CUSTOMER_USER = os.getenv("IMS_CUSTOMER_USER", "customer1")
CUSTOMER_PASSWORD = os.getenv("IMS_CUSTOMER_PASSWORD", "customer-demo-pw")

_token = None


def login():
    """Fetch a CUSTOMER token from the gateway's demo /auth/login."""
    resp = requests.post(
        f"{GATEWAY}/auth/login",
        json={"username": CUSTOMER_USER, "password": CUSTOMER_PASSWORD},
        timeout=10,
    )
    if not resp.ok:
        raise RuntimeError(f"login as {CUSTOMER_USER} failed: {resp.status_code} {resp.text}")
    return resp.json()["accessToken"]


def get_cb_detail():
    try:
        r = requests.get(ORDER_HEALTH, timeout=5)
        r.raise_for_status()
        return r.json().get("components", {}).get("circuitBreakers", {}).get("details", {}).get(CB_NAME, {})
    except Exception as e:
        return {"status": f"ERROR:{e}"}


def cb_state(detail):
    return (detail.get("status") or detail.get("details", {}).get("state") or "UNKNOWN").upper()


def is_open(detail):
    s = cb_state(detail)
    return "OPEN" in s and "HALF" not in s


def is_half_open(detail):
    return "HALF" in cb_state(detail)


def get_all_cb_states():
    try:
        r = requests.get(ORDER_HEALTH, timeout=5)
        details = r.json().get("components", {}).get("circuitBreakers", {}).get("details", {})
        return {k: cb_state(v) for k, v in details.items()}
    except Exception as e:
        return {"error": str(e)}


def trigger_failure():
    try:
        resp = requests.post(
            f"{GATEWAY}/api/orders",
            json=ORDER_BODY,
            headers={"Authorization": f"Bearer {_token}"},
            timeout=30,
        )
        # A 429 never reaches order-service, so it is neither a breaker trip nor
        # a failure of the thing being measured. Surface it instead of letting
        # it masquerade as a request that was tried: a run full of
        # RATE_LIMITED is a mis-run, not a result.
        if resp.status_code == 429:
            return "RATE_LIMITED", 429
        body = resp.json() if resp.content else {}
        return body.get("status"), resp.status_code
    except requests.RequestException:
        return "HTTP_ERROR", None


def wait_until_open(max_wait_sec=180):
    """Warmup until strictly OPEN (not HALF_OPEN)."""
    warmup = 0
    for _ in range(max_wait_sec):
        detail = get_cb_detail()
        if is_open(detail):
            return True, warmup, cb_state(detail), get_all_cb_states()
        trigger_failure()
        warmup += 1
        time.sleep(1)
    return False, warmup, cb_state(get_cb_detail()), get_all_cb_states()


def fire_while_open(n=10):
    """Fire n requests; only count those where CB was OPEN at start of request."""
    latencies = []
    order_statuses = []
    cb_states_during = []
    skipped_half_open = 0

    while len(latencies) < n:
        detail = get_cb_detail()
        state = cb_state(detail)
        if is_half_open(detail):
            skipped_half_open += 1
            trigger_failure()  # push back to OPEN
            time.sleep(0.3)
            continue
        if not is_open(detail):
            # lost OPEN state — stop early
            break
        start = time.time()
        status, http_code = trigger_failure()
        elapsed = round(time.time() - start, 4)
        latencies.append(elapsed)
        order_statuses.append(status)
        cb_states_during.append(state)
        time.sleep(0.15)

    return latencies, order_statuses, cb_states_during, skipped_half_open


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--feign-cb", choices=["true", "false"], required=True)
    parser.add_argument("--run", type=int, required=True)
    args = parser.parse_args()

    global _token
    _token = login()

    opened, warmups, open_state, states_at_open = wait_until_open()
    if not opened:
        out = {
            "feign_cb": args.feign_cb,
            "run": args.run,
            "error": "never reached CIRCUIT_OPEN",
            "warmup_attempts": warmups,
            "last_cb_state": open_state,
            "all_cb_states": states_at_open,
        }
        print(json.dumps(out, indent=2))
        sys.exit(1)

    latencies, order_statuses, cb_during, skipped = fire_while_open(10)
    result = {
        "feign_cb": args.feign_cb,
        "run": args.run,
        "warmup_requests_to_open": warmups,
        "cb_state_at_measurement_start": open_state,
        "all_cb_states_at_open": states_at_open,
        "all_cb_states_after": get_all_cb_states(),
        "skipped_half_open_attempts": skipped,
        "requests_measured": len(latencies),
        "open_state_latencies_sec": latencies,
        "order_statuses": order_statuses,
        "avg_latency_sec": round(sum(latencies) / len(latencies), 4) if latencies else None,
        "min_latency_sec": min(latencies) if latencies else None,
        "max_latency_sec": max(latencies) if latencies else None,
    }
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
