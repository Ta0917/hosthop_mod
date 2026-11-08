"""Local SQLite protocol experiment, not a network service or production lock."""
import sqlite3
from contextlib import contextmanager


@contextmanager
def connection(db):
    c = sqlite3.connect(db, timeout=10)
    try:
        with c:
            yield c
    finally:
        c.close()


def initialize(db):
    with connection(db) as c:
        c.execute("CREATE TABLE IF NOT EXISTS world (id INTEGER PRIMARY KEY CHECK(id=1), epoch INTEGER NOT NULL, owner TEXT, expires INTEGER NOT NULL, generation INTEGER NOT NULL, digest TEXT)")
        c.execute("INSERT OR IGNORE INTO world VALUES (1, 0, NULL, 0, 0, NULL)")


def acquire(db, owner, now, duration):
    if duration <= 0 or not owner:
        raise ValueError("owner and positive duration required")
    with connection(db) as c:
        row = c.execute("UPDATE world SET epoch=epoch+1, owner=?, expires=? WHERE id=1 AND expires<=? RETURNING epoch, generation", (owner, now + duration, now)).fetchone()
        return row


def renew(db, owner, epoch, now, duration):
    if duration <= 0:
        raise ValueError("positive duration required")
    with connection(db) as c:
        return c.execute("UPDATE world SET expires=? WHERE id=1 AND owner=? AND epoch=? AND expires>? RETURNING epoch", (now + duration, owner, epoch, now)).fetchone() is not None


def commit(db, owner, epoch, now, expected_generation, digest):
    # The caller must already have durably uploaded and independently verified
    # the immutable object. This model does not perform or verify that step.
    if not digest:
        raise ValueError("digest required")
    with connection(db) as c:
        return c.execute("UPDATE world SET generation=generation+1, digest=? WHERE id=1 AND owner=? AND epoch=? AND expires>? AND generation=? RETURNING generation", (digest, owner, epoch, now, expected_generation)).fetchone()


def state(db):
    with connection(db) as c:
        return c.execute("SELECT epoch, owner, expires, generation, digest FROM world WHERE id=1").fetchone()
