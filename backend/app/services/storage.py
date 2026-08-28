"""Object storage abstraction.

Business logic stores and retrieves images through :class:`ObjectStorage` and
never learns where the bytes live. Moving from local disk to S3, GCS or Azure
Blob is a new implementation of this one interface.
"""

from __future__ import annotations

import hashlib
import uuid
from abc import ABC, abstractmethod
from dataclasses import dataclass
from datetime import date
from pathlib import Path

from ..core.errors import ServiceUnavailableError

CONTENT_TYPE_EXTENSIONS = {
    "image/jpeg": ".jpg",
    "image/png": ".png",
    "image/webp": ".webp",
}


@dataclass(frozen=True, slots=True)
class StoredObject:
    key: str
    backend: str
    byte_size: int
    content_type: str
    content_sha256: str


class ObjectStorage(ABC):
    @property
    @abstractmethod
    def backend_name(self) -> str: ...

    @abstractmethod
    def put(self, data: bytes, *, content_type: str, owner_id: uuid.UUID) -> StoredObject: ...

    @abstractmethod
    def get(self, key: str) -> bytes: ...

    @abstractmethod
    def delete(self, key: str) -> None: ...

    @abstractmethod
    def exists(self, key: str) -> bool: ...

    @staticmethod
    def build_key(owner_id: uuid.UUID, content_type: str, digest: str) -> str:
        """Deterministic, non-guessable, date-partitioned key.

        Partitioning by date keeps directory listings and lifecycle rules
        manageable; including the content digest makes a repeated upload of the
        same bytes land on the same key instead of accumulating copies.
        """
        extension = CONTENT_TYPE_EXTENSIONS.get(content_type, ".bin")
        today = date.today()
        return f"{owner_id}/{today:%Y/%m/%d}/{digest[:32]}{extension}"


class LocalObjectStorage(ObjectStorage):
    """Filesystem-backed storage for development and single-node deployments."""

    def __init__(self, root: Path) -> None:
        self._root = Path(root)
        self._root.mkdir(parents=True, exist_ok=True)

    @property
    def backend_name(self) -> str:
        return "local"

    def _resolve(self, key: str) -> Path:
        """Resolve a key inside the storage root, rejecting traversal.

        Keys are generated server-side, but this is the boundary where a
        crafted key would become a filesystem path, so it is enforced here
        rather than trusted upstream.
        """
        candidate = (self._root / key).resolve()
        root = self._root.resolve()
        if not candidate.is_relative_to(root):
            raise ValueError("Storage key escapes the storage root")
        return candidate

    def put(self, data: bytes, *, content_type: str, owner_id: uuid.UUID) -> StoredObject:
        digest = hashlib.sha256(data).hexdigest()
        key = self.build_key(owner_id, content_type, digest)
        path = self._resolve(key)
        path.parent.mkdir(parents=True, exist_ok=True)

        # Atomic publish: a reader never observes a partially written object.
        temporary = path.with_suffix(path.suffix + ".partial")
        temporary.write_bytes(data)
        temporary.replace(path)

        return StoredObject(
            key=key,
            backend=self.backend_name,
            byte_size=len(data),
            content_type=content_type,
            content_sha256=digest,
        )

    def get(self, key: str) -> bytes:
        path = self._resolve(key)
        if not path.is_file():
            raise ServiceUnavailableError("The stored image is no longer available.")
        return path.read_bytes()

    def delete(self, key: str) -> None:
        self._resolve(key).unlink(missing_ok=True)

    def exists(self, key: str) -> bool:
        return self._resolve(key).is_file()
