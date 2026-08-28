"""Fixed-window rate limiting.

Two backends behind one interface: Redis when a URL is configured (correct
across processes), in-memory otherwise (correct for a single process, which is
what local development and the test suite are).
"""

from __future__ import annotations

import threading
import time
from abc import ABC, abstractmethod
from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class RateLimitResult:
    allowed: bool
    remaining: int
    retry_after_seconds: int


class RateLimiter(ABC):
    @abstractmethod
    def check(self, key: str, *, limit: int, window_seconds: int) -> RateLimitResult: ...


class InMemoryRateLimiter(RateLimiter):
    """Process-local limiter.

    Deliberately not shared across workers -- with several processes each gets
    its own budget. That is documented rather than hidden, and Redis is the
    answer for any deployment that runs more than one.
    """

    def __init__(self) -> None:
        self._counters: dict[str, tuple[int, float]] = {}
        self._lock = threading.Lock()

    def check(self, key: str, *, limit: int, window_seconds: int) -> RateLimitResult:
        now = time.monotonic()
        with self._lock:
            count, window_start = self._counters.get(key, (0, now))
            if now - window_start >= window_seconds:
                count, window_start = 0, now
            count += 1
            self._counters[key] = (count, window_start)

            if count > limit:
                retry_after = max(1, int(window_seconds - (now - window_start)))
                return RateLimitResult(False, 0, retry_after)
            return RateLimitResult(True, max(0, limit - count), 0)

    def reset(self) -> None:
        with self._lock:
            self._counters.clear()


class RedisRateLimiter(RateLimiter):
    """Shared limiter for multi-process deployments.

    Fails open: if Redis is unreachable the request proceeds. A rate limiter
    outage must not become a full outage, and the trade-off is stated here so
    it is a decision rather than an accident.
    """

    def __init__(self, client) -> None:
        self._client = client

    def check(self, key: str, *, limit: int, window_seconds: int) -> RateLimitResult:
        namespaced = f"nutrilens:ratelimit:{key}"
        try:
            pipeline = self._client.pipeline()
            pipeline.incr(namespaced, 1)
            pipeline.ttl(namespaced)
            count, ttl = pipeline.execute()
            count = int(count)
            if ttl is None or ttl < 0:
                self._client.expire(namespaced, window_seconds)
                ttl = window_seconds
        except Exception:  # noqa: BLE001 - see class docstring
            return RateLimitResult(True, limit, 0)

        if count > limit:
            return RateLimitResult(False, 0, max(1, int(ttl)))
        return RateLimitResult(True, max(0, limit - count), 0)
