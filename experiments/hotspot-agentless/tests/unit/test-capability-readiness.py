import importlib.util
from pathlib import Path
import tempfile
import unittest
spec=importlib.util.spec_from_file_location('runner',Path(__file__).parents[2]/'scripts/run-capability-candidates.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
class ReadinessTest(unittest.TestCase):
    def test_partial_or_unrelated_records_do_not_release_work(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'capture'
            path.write_text('{"kind":"collector_attached"}')
            self.assertFalse(module.attached(path))
            path.write_text('{"kind":"event"}\n')
            self.assertFalse(module.attached(path))
            path.write_text('{"kind":"collector_attached"}\n')
            self.assertTrue(module.attached(path))
if __name__=='__main__':unittest.main()
