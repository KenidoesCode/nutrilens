"""Repository base.

Repositories never commit. The request-scoped transaction owns the commit, so
a handler can compose several repositories and still get all-or-nothing
semantics.
"""

from __future__ import annotations

from sqlalchemy.orm import Session


class Repository:
    def __init__(self, session: Session) -> None:
        self._session = session

    @property
    def session(self) -> Session:
        return self._session

    def flush(self) -> None:
        """Push pending changes so server-side defaults and ids materialise."""
        self._session.flush()
