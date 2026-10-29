import unittest
import measure


def fixture(mode="reader-thread", fork=0):
    row = {"schema": 1, "mode": mode, "pid": 100 + fork, "warmups": 20,
            "java": "synthetic", "os": "synthetic", "arch": "synthetic",
            "samples": list(range(1, 101)), "medianNs": 50, "p95Ns": 95,
            "setupNs": 1, "drainNs": 1, "retirement": "physical",
            "resourcesBefore": {"pid": 100 + fork, "rssKiB": 1, "threads": 1},
            "resourcesAfter": {"pid": 100 + fork, "rssKiB": 1, "threads": 1},
            "pressure": {"accepted": 4, "rejected": 1, "rejectionNs": 1},
            "artifacts": {"synthetic-unit-fixture.jar": "a" * 64}, "scope": "synthetic validator fixture only"}
    if mode.endswith("direct"):
        row.update(retirement="not-applicable", pressure=None)
    if mode == "outline-portal":
        row["childResources"] = {"pid": 900 + fork, "rssKiB": 1, "threads": 1}
    return row


def inventory():
    return [fixture(mode, index * 3 + fork) for index, mode in enumerate(sorted(measure.MODES - {"spring-http"})) for fork in range(3)]


class MeasurementsTest(unittest.TestCase):
    def test_duplicate_fields_cannot_override_evidence(self):
        with self.assertRaises(ValueError):
            measure.decode('{"pid":1,"pid":2}')

    def test_exact_three_forks_are_validated_without_pooling_pressure(self):
        measure.validate(inventory())

    def test_corruption_and_missing_physical_evidence_reject(self):
        for change in [lambda row: row["samples"].pop(),
                       lambda row: row["samples"].__setitem__(0, 0),
                       lambda row: row.update(medianNs=999),
                       lambda row: row.update(p95Ns=999),
                       lambda row: row.update(warmups=0),
                       lambda row: row.update(retirement="cancelled"),
                       lambda row: row.update(pressure=None),
                       lambda row: row.update(artifacts={}),
                       lambda row: row.pop("java"),
                       lambda row: row["resourcesAfter"].update(threads=0)]:
            rows = inventory()
            change(next(row for row in rows if row["mode"] == "reader-thread"))
            with self.assertRaises(ValueError):
                measure.validate(rows)
        with self.assertRaises(ValueError):
            measure.validate([fixture()])
        with self.assertRaises(ValueError):
            measure.validate([fixture()] * 3)


if __name__ == "__main__":
    unittest.main()
