import test from 'node:test';
import assert from 'node:assert/strict';
import { normalizeCoverageStacks } from '../tier_e_coverage_stack_identity.mjs';

const stack = (loader, name, bci = '10', descriptor = '()V') =>
  ['257', '0', 'COMPLETE', loader, name, 'invoke', descriptor, bci, 'JAVA'];

test('independent JVM addresses and loader tags are comparable', () => {
  assert.deepEqual(normalizeCoverageStacks({0: [stack('loader-3', 'Owner$$Lambda.0xaaaa')]}),
    normalizeCoverageStacks({0: [stack('loader-7', 'Owner$$Lambda.0xbbbb')]}));
});
test('distinct hidden classes within a run remain distinct', () => {
  const result = normalizeCoverageStacks({0: [stack('loader-3', 'Owner$$Lambda.0xaaaa')],
    1: [stack('loader-3', 'Owner$$Lambda.0xbbbb')]});
  assert.notDeepEqual(result[0], result[1]);
});
test('method descriptors bytecode positions and regular class identity remain significant', () => {
  for (const other of [stack('loader-3', 'Owner', '11'), stack('loader-3', 'Owner', '10', '(I)V'), stack('loader-3', 'Other')]) {
    assert.notDeepEqual(normalizeCoverageStacks({0: [stack('loader-3', 'Owner')]}), normalizeCoverageStacks({0: [other]}));
  }
});
test('malformed records cannot become passing comparisons', () => {
  assert.throws(() => normalizeCoverageStacks({0: [['257']]}));
});
