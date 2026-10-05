import importlib.util
from pathlib import Path
import unittest
import copy

spec=importlib.util.spec_from_file_location('runtime_candidate',Path(__file__).parents[2]/'scripts/verify-runtime-candidate.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class RuntimeCandidateTest(unittest.TestCase):
    def fixture(self):
        manifest={'pid':123,'start_ticks':456,'tokens':{'/fixture/A':['Fixture.leaf','Fixture.routeA']}}
        rows=[{'schema_version':1,'session_id':'s','kind':'event','event_id':'s:0:1','event':{'filename':'/fixture/A','host_pid':123,'process_start_ticks':456}},
              {'schema_version':1,'session_id':'s','kind':'enrichment','event_id':'s:0:1','evidence':{'attribution':'event_correlated','frames':[{'function_name':'Fixture.leaf'},{'function_name':'Fixture.routeA'}]}}]
        return manifest,rows
    def test_correct_route_and_all_unavailable(self):
        m,r=self.fixture();self.assertEqual(module.verify(m,r)['useful'],1)
        r[1]['evidence']={'attribution':'unavailable','frames':[]}
        self.assertEqual(module.verify(m,r)['useful'],0)
    def test_wrong_order_generation_duplicate_and_missing(self):
        m,r=self.fixture()
        variants=[]
        v=copy.deepcopy(r);v[1]['evidence']['frames'][1]['function_name']='Fixture.routeB';variants.append(v)
        v=copy.deepcopy(r);v[0]['event']['process_start_ticks']=457;variants.append(v)
        variants.extend([r+r,r[:1],r[1:]])
        for v in variants:
            with self.assertRaises(ValueError):module.verify(m,v)
    def test_correlated_native_only_stack_is_not_a_wrong_java_route(self):
        m,r=self.fixture();r[1]['evidence']['frames']=[{'function_name':'libc.openat'}]
        result=module.verify(m,r)
        self.assertEqual(result['useful'],0)
        self.assertEqual(result['application_unavailable'],1)

if __name__=='__main__':unittest.main()
