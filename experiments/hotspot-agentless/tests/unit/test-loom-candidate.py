import importlib.util
from pathlib import Path
import unittest
import copy

spec=importlib.util.spec_from_file_location('loom_candidate',Path(__file__).parents[2]/'scripts/verify-loom-candidate.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class LoomCandidateTest(unittest.TestCase):
    def fixture(self):
        oracle={'vt-7-3-A':{'logical_id':7,'route':['Fixture.leaf','Fixture.routeA']}}
        rows=[{'kind':'event','event_id':'s:0:1','event':{'filename':'/fixture/vt-7-3-A','host_tid':42,'thread_identity':{'scope':'mounted_virtual','logical_id':7,'object_lease':True,'mount_capture':1,'mount_decode':1,'parent_available':False}}},
              {'kind':'enrichment','event_id':'s:0:1','evidence':{'attribution':'event_correlated','frames':[{'function_name':'Fixture.leaf'},{'function_name':'Fixture.routeA'}]}}]
        return oracle,rows
    def test_mounted_and_carrier_only(self):
        m,r=self.fixture();self.assertEqual(module.verify(m,r)['logical_observed'],1)
        del r[0]['event']['thread_identity'];self.assertEqual(module.verify(m,r)['logical_observed'],0)
    def test_swapped_mount_oracle_parent_wrong_route_and_missing(self):
        m,r=self.fixture();variants=[]
        for field,value in [('logical_id',8),('mount_decode',2),('mount_capture',0),('object_lease',False),('source','oracle'),('parent_available',True)]:
            v=copy.deepcopy(r);v[0]['event']['thread_identity'][field]=value;variants.append(v)
        v=copy.deepcopy(r);v[1]['evidence']['frames'][1]['function_name']='Fixture.routeB';variants.append(v)
        variants.extend([r[:1],r+r,[]])
        for v in variants:
            with self.assertRaises(ValueError):module.verify(m,v)

if __name__=='__main__':unittest.main()
