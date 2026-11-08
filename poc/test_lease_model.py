import concurrent.futures
import pathlib
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


if __name__ == "__main__":
    unittest.main()
