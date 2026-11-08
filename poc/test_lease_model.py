import concurrent.futures
import pathlib
import sqlite3
import tempfile
import unittest

import lease_model as model


class LeaseModelTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(dir=pathlib.Path(__file__).parent)
        self.addCleanup(self.tmp.cleanup)
        self.db = str(pathlib.Path(self.tmp.name) / "model.db")
        model.initialize(self.db)

    def test_simultaneous_acquisition_has_one_winner(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
            results = list(pool.map(lambda n: model.acquire(self.db, str(n), 100, 10), range(16)))
        self.assertEqual(1, sum(result is not None for result in results))

    def test_expired_host_cannot_publish_or_renew(self):
        epoch, generation = model.acquire(self.db, "a", 100, 10)
        new_epoch, _ = model.acquire(self.db, "b", 110, 10)
        self.assertGreater(new_epoch, epoch)
        self.assertIsNone(model.commit(self.db, "a", epoch, 111, generation, "old"))
        self.assertFalse(model.renew(self.db, "a", epoch, 111, 10))
        self.assertEqual((1,), model.commit(self.db, "b", new_epoch, 111, generation, "new"))
        self.assertEqual("new", model.state(self.db)[4])

    def test_compare_generation_and_expiry(self):
        epoch, generation = model.acquire(self.db, "a", 100, 10)
        self.assertEqual((1,), model.commit(self.db, "a", epoch, 101, generation, "first"))
        self.assertIsNone(model.commit(self.db, "a", epoch, 102, generation, "stale"))
        self.assertIsNone(model.commit(self.db, "a", epoch, 110, 1, "expired"))
        self.assertEqual((1, "first"), model.state(self.db)[3:])

    def test_lost_response_retry_remains_idempotent_after_later_commit(self):
        epoch, generation = model.acquire(self.db, "a", 100, 10)
        self.assertEqual((1,), model.commit_once(self.db, "op-a", "a", epoch, 101, generation, "first"))
        new_epoch, generation = model.acquire(self.db, "b", 110, 10)
        self.assertEqual((2,), model.commit_once(self.db, "op-b", "b", new_epoch, 111, generation, "second"))
        self.assertEqual((1,), model.commit_once(self.db, "op-a", "a", epoch, 112, 0, "first"))
        self.assertEqual((2, "second"), model.state(self.db)[3:])

    def test_operation_id_cannot_be_reused_for_different_request(self):
        epoch, generation = model.acquire(self.db, "a", 100, 10)
        self.assertEqual((1,), model.commit_once(self.db, "op", "a", epoch, 101, generation, "first"))
        with self.assertRaises(ValueError):
            model.commit_once(self.db, "op", "a", epoch, 102, generation, "other")
        self.assertEqual((1, "first"), model.state(self.db)[3:])

    def test_concurrent_retries_return_same_result(self):
        epoch, generation = model.acquire(self.db, "a", 100, 10)
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(lambda _: model.commit_once(self.db, "op", "a", epoch, 101, generation, "first"), range(8)))
        self.assertEqual([(1,)] * 8, results)
        self.assertEqual((1, "first"), model.state(self.db)[3:])

    def test_failure_recording_operation_rolls_back_generation(self):
        epoch, generation = model.acquire(self.db, "a", 100, 10)
        with model.connection(self.db) as c:
            c.execute("CREATE TRIGGER reject_commit BEFORE INSERT ON committed_operation BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
        with self.assertRaises(sqlite3.IntegrityError):
            model.commit_once(self.db, "op", "a", epoch, 101, generation, "first")
        self.assertEqual((0, None), model.state(self.db)[3:])
        with model.connection(self.db) as c:
            c.execute("DROP TRIGGER reject_commit")
        self.assertEqual((1,), model.commit_once(self.db, "op", "a", epoch, 102, generation, "first"))


if __name__ == "__main__":
    unittest.main()
