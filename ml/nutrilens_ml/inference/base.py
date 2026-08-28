"""The inference contract every recognition backend implements.

Nothing above this module knows whether recognition is done by a neural
network, a classical algorithm or a remote service. Swapping the engine is a
configuration change, not a rewrite.
"""

from __future__ import annotations

from abc import ABC, abstractmethod

from ..domain import Detection
from ..preprocessing.image import PreparedImage


class InferenceError(RuntimeError):
    """Raised when an engine cannot produce a result for a valid image."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


class FoodRecognizer(ABC):
    """Detects and classifies food regions in a prepared image."""

    @property
    @abstractmethod
    def name(self) -> str:
        """Stable identifier recorded on every prediction for auditability."""

    @property
    @abstractmethod
    def model_version(self) -> str:
        """Version of the weights/rules in use, recorded alongside predictions."""

    @abstractmethod
    def recognize(self, image: PreparedImage) -> list[Detection]:
        """Return zero or more detections, highest confidence first."""

    def warmup(self) -> None:
        """Optional hook to pay one-off initialisation cost outside a request."""
        return None

    def close(self) -> None:
        """Optional hook to release native resources."""
        return None
