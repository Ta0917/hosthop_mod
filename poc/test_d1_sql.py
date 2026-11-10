"""SQLite tests of candidate SQL, not proof of deployed D1 semantics."""
import concurrent.futures
from pathlib import Path
import sqlite3
import tempfile
import time
import unittest

import lease_model

SCHEMA = (Path(__file__).parent / "d1" / "schema.sql").read_text(encoding="utf-8")
CHECKPOINT_SCHEMA = (Path(__file__).parent / "d1" / "checkpoint_migration.sql").read_text(encoding="utf-8")
CHECKPOINT_DEPENDENCIES = (Path(__file__).parent / "d1" / "checkpoint_dependencies_migration.sql").read_text(encoding="utf-8")
MAINTENANCE_SCHEMA = (Path(__file__).parent / "d1" / "maintenance_migration.sql").read_text(encoding="utf-8")
CHECKPOINT = (Path(__file__).parent / "d1" / "checkpoint.sql").read_text(encoding="utf-8")
CHECKPOINT_DELETE = (Path(__file__).parent / "d1" / "checkpoint_delete.sql").read_text(encoding="utf-8")
CHECKPOINT_PRUNE = (Path(__file__).parent / "d1" / "checkpoint_prune.sql").read_text(encoding="utf-8")
CHECKPOINT_PRUNE_DELETE = (Path(__file__).parent / "d1" / "checkpoint_prune_delete.sql").read_text(encoding="utf-8")
COMMIT = (Path(__file__).parent / "d1" / "commit.sql").read_text(encoding="utf-8")
ACQUIRE = (Path(__file__).parent / "d1" / "acquire.sql").read_text(encoding="utf-8")
ACQUIRE_STATUS = (Path(__file__).parent / "d1" / "acquire_status.sql").read_text(encoding="utf-8")
RENEW = (Path(__file__).parent / "d1" / "renew.sql").read_text(encoding="utf-8")
RELEASE = (Path(__file__).parent / "d1" / "release.sql").read_text(encoding="utf-8")


class D1SqlTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(dir=Path(__file__).parent)
        self.addCleanup(self.tmp.cleanup)
        self.db = str(Path(self.tmp.name) / "db.sqlite")
        with lease_model.connection(self.db) as c:
            c.executescript(SCHEMA)
            c.executescript(CHECKPOINT_SCHEMA)
            c.executescript(CHECKPOINT_DEPENDENCIES)
            c.executescript(MAINTENANCE_SCHEMA)
            c.execute("INSERT INTO worlds (id, owner, lease_id, epoch, expires) VALUES (?, ?, ?, ?, ?)", ("w", "a", "lease-a", 1, int(time.time()) + 300))

    def attempt(self, operation_id, digest="hash-a", owner="a", lease_id="lease-a", epoch=1, generation=0):
        params = ("w", operation_id, owner, lease_id, epoch, generation, digest, "file-id", 42)
        with lease_model.connection(self.db) as c:
            result = c.execute(COMMIT, params).fetchone()
            if result:
                return result[0]
            old = c.execute("SELECT owner, lease_id, epoch, previous_generation, digest, object_id, object_size, result_generation FROM committed_operations WHERE world_id=? AND operation_id=?", ("w", operation_id)).fetchone()
            if old is None:
                return None
            if old[:7] != (owner, lease_id, epoch, generation, digest, "file-id", 42):
                raise ValueError("operation ID conflict")
            return old[7]

    def test_retries_and_conflicting_id(self):
        self.assertEqual(1, self.attempt("op"))
        self.assertEqual(1, self.attempt("op"))
        with self.assertRaises(ValueError):
            self.attempt("op", digest="other")

    def test_concurrent_new_commits_only_one_succeeds(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
            results = list(pool.map(lambda i: self.attempt("op-" + str(i)), range(12)))
        self.assertEqual(1, sum(result is not None for result in results))

    def test_concurrent_retries_only_advance_once(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
            results = list(pool.map(lambda _: self.attempt("same"), range(12)))
        self.assertEqual([1] * 12, results)
        with lease_model.connection(self.db) as c:
            self.assertEqual((1, "hash-a"), c.execute("SELECT generation, digest FROM worlds WHERE id='w'").fetchone())
            self.assertEqual(1, c.execute("SELECT count(*) FROM committed_operations").fetchone()[0])

    def test_stale_or_expired_host_cannot_commit(self):
        self.assertIsNone(self.attempt("wrong-epoch", epoch=2))
        self.assertIsNone(self.attempt("wrong-lease", lease_id="other"))
        with lease_model.connection(self.db) as c:
            c.execute("UPDATE worlds SET expires=unixepoch('now') WHERE id='w'")
        self.assertIsNone(self.attempt("expired"))
        with lease_model.connection(self.db) as c:
            self.assertEqual((0, None), c.execute("SELECT generation, digest FROM worlds WHERE id='w'").fetchone())

    def test_concurrent_acquisition_and_fenced_renewal(self):
        with lease_model.connection(self.db) as c:
            c.execute("UPDATE worlds SET owner=NULL, lease_id=NULL, expires=0 WHERE id='w'")

        def acquire(i):
            with lease_model.connection(self.db) as c:
                return c.execute(ACQUIRE, ("w", f"host-{i}", f"lease-{i}", 30)).fetchone()

        with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
            results = list(pool.map(acquire, range(12)))
        self.assertEqual(1, sum(row is not None for row in results))
        with lease_model.connection(self.db) as c:
            owner, lease_id, epoch = c.execute("SELECT owner, lease_id, epoch FROM worlds WHERE id='w'").fetchone()
            wrong = ("w", owner, "wrong", epoch, 30)
            self.assertIsNone(c.execute(RENEW, wrong).fetchone())
            valid = ("w", owner, lease_id, epoch, 30)
            self.assertEqual(epoch, c.execute(RENEW, valid).fetchone()[0])
            c.execute("UPDATE worlds SET expires=unixepoch('now') WHERE id='w'")
            self.assertIsNone(c.execute(RENEW, valid).fetchone())

    def test_renewal_never_shortens_existing_lease(self):
        with lease_model.connection(self.db) as c:
            expiry = c.execute("SELECT expires FROM worlds WHERE id='w'").fetchone()[0]
            renewed = c.execute(RENEW, ("w", "a", "lease-a", 1, 1)).fetchone()
            self.assertEqual(expiry, renewed[2])

    def test_acquisition_retry_does_not_reacquire_an_expired_grant(self):
        with lease_model.connection(self.db) as c:
            c.execute("UPDATE worlds SET owner=NULL, lease_id=NULL, expires=0 WHERE id='w'")
            request = ("w", "host", "unique-lease", 30)
            first = c.execute(ACQUIRE, request).fetchone()
            self.assertEqual(2, first[0])
            self.assertEqual(30, first[2] - first[3])
            self.assertIsNone(c.execute(ACQUIRE, request).fetchone())
            status = c.execute(ACQUIRE_STATUS, ("w", "unique-lease")).fetchone()
            self.assertEqual(("host", 30, 2, 0), status[:4])
            self.assertEqual(1, status[5])
            self.assertGreater(status[4], status[6])
            c.execute("UPDATE worlds SET expires=unixepoch('now') WHERE id='w'")
            self.assertIsNone(c.execute(ACQUIRE, request).fetchone())
            self.assertEqual(0, c.execute(ACQUIRE_STATUS, ("w", "unique-lease")).fetchone()[5])
            self.assertIsNone(c.execute(ACQUIRE, ("w", "host", "fresh-lease", 30)).fetchone())
            self.assertEqual(0, c.execute(ACQUIRE_STATUS, ("w", "unique-lease")).fetchone()[5])
            with self.assertRaises(sqlite3.IntegrityError):
                c.execute("INSERT INTO lease_grants VALUES (?, ?, ?, ?, ?, ?, ?)",
                          ("w", "forged", "host", 3, 0, int(time.time()) + 30, 30))

    def test_old_host_cannot_renew_or_commit_after_takeover(self):
        self.assertEqual(1, self.attempt("final-op"))
        with lease_model.connection(self.db) as c:
            self.assertIsNotNone(c.execute(RELEASE, ("w", "a", "lease-a", 1, "final-op", 1)).fetchone())
            new_lease = c.execute(ACQUIRE, ("w", "host-b", "lease-b", 30)).fetchone()
            self.assertEqual(2, new_lease[0])
            self.assertIsNone(c.execute(RENEW, ("w", "a", "lease-a", 1, 30)).fetchone())
        self.assertIsNone(self.attempt("stale-operation", owner="a", lease_id="lease-a", epoch=1))
        with lease_model.connection(self.db) as c:
            self.assertEqual(("host-b", "lease-b", 2, 1), c.execute("SELECT owner,lease_id,epoch,generation FROM worlds WHERE id='w'").fetchone())

    def test_acquisition_update_failure_rolls_back_grant(self):
        with lease_model.connection(self.db) as c:
            c.execute("UPDATE worlds SET owner=NULL, lease_id=NULL, expires=0 WHERE id='w'")
            c.execute("CREATE TRIGGER reject_lease BEFORE UPDATE ON worlds BEGIN SELECT RAISE(ABORT, 'injected lease failure'); END")
        with self.assertRaises(sqlite3.IntegrityError):
            with lease_model.connection(self.db) as c:
                c.execute(ACQUIRE, ("w", "host", "unique-lease", 30)).fetchone()
        with lease_model.connection(self.db) as c:
            self.assertEqual(0, c.execute("SELECT count(*) FROM lease_grants").fetchone()[0])
            self.assertEqual((1, 0), c.execute("SELECT epoch, expires FROM worlds WHERE id='w'").fetchone())

    def test_acquire_rejects_empty_ids_and_invalid_durations(self):
        with lease_model.connection(self.db) as c:
            c.execute("UPDATE worlds SET owner=NULL, lease_id=NULL, expires=0 WHERE id='w'")
            for owner, lease, seconds in (("", "l", 30), ("a", "", 30), ("a", "l", 0), ("a", "l", 301), ("a", "l", 1.5)):
                self.assertIsNone(c.execute(ACQUIRE, ("w", owner, lease, seconds)).fetchone())
            self.assertEqual(1, c.execute("SELECT epoch FROM worlds WHERE id='w'").fetchone()[0])

    def test_trigger_failure_rolls_back_operation_record(self):
        with lease_model.connection(self.db) as c:
            c.execute("CREATE TRIGGER reject_update BEFORE UPDATE ON worlds BEGIN SELECT RAISE(ABORT, 'injected'); END")
        with self.assertRaises(sqlite3.IntegrityError):
            self.attempt("op")
        with lease_model.connection(self.db) as c:
            self.assertEqual(0, c.execute("SELECT count(*) FROM committed_operations").fetchone()[0])
            self.assertEqual((0, None), c.execute("SELECT generation, digest FROM worlds WHERE id='w'").fetchone())

    def test_full_checkpoint_prune_fences_old_clients_and_live_dependencies(self):
        digest = "a" * 64
        with lease_model.connection(self.db) as c:
            def publish(checkpoint, kind, base="", managed=1, lease="lease-a"):
                return c.execute(CHECKPOINT, ("w", checkpoint, "a", lease, 1, 0, digest,
                                              "object-" + checkpoint, 42, kind, managed, base)).fetchone()

            def prune(checkpoint):
                c.execute(CHECKPOINT_PRUNE_DELETE, ("w", checkpoint, "object-" + checkpoint, digest, 42, 4, "a"))
                return c.execute("SELECT kind FROM checkpoint_deletions WHERE checkpoint_id=?", (checkpoint,)).fetchone()

            self.assertIsNotNone(publish("legacy", "full", managed=0))
            self.assertIsNotNone(publish("legacy-delta", "delta", managed=0))
            for number in range(6):
                self.assertIsNotNone(publish(f"full-{number}", "full"))
            self.assertIsNone(prune("full-1"))  # Current lease may still adopt it.
            c.execute("UPDATE worlds SET lease_id='lease-b' WHERE id='w'")
            self.assertIsNone(prune("full-1"))  # A legacy delta in the original lease may depend on it.
            c.execute(CHECKPOINT_DELETE, ("w", "legacy-delta", "object-legacy-delta", digest, 42))
            self.assertIsNotNone(publish("tracked-delta", "delta", "object-full-0", lease="lease-b"))
            self.assertIsNone(prune("legacy"))
            self.assertIsNone(prune("full-5"))  # Four most recent fulls are retained.
            self.assertIsNone(prune("full-0"))
            self.assertEqual([], c.execute(CHECKPOINT_PRUNE, ("w", "a", 0)).fetchall())
            self.assertEqual(["full-1"], [row[0] for row in c.execute(CHECKPOINT_PRUNE, ("w", "a", 1))])
            self.assertEqual(("full",), prune("full-1"))
            self.assertIsNone(publish("too-late", "delta", "object-full-1", lease="lease-b"))
            c.execute(CHECKPOINT_DELETE, ("w", "tracked-delta", "object-tracked-delta", digest, 42))
            self.assertEqual(("full",), prune("full-0"))


if __name__ == "__main__":
    unittest.main()
