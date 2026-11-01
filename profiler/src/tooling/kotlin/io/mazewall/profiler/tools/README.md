# Review operation-document changes

```bash
./gradlew :profiler:policyDiff \
  -PoldOperationDocument=/absolute/path/old-operation-document.json \
  -PnewOperationDocument=/absolute/path/new-operation-document.json
```

`PolicyDiff <old> <new>` is a development-only CLI, not a published profiler API or
an approval service. Each input is strict UTF-8 and limited to2MiB before decoding.
The existing typed operation/canonical-policy decoder rejects unknown versions,
fields, duplicate keys, invalid syscalls and invalid binding/coverage shapes.

It compares canonical fields: operation/adapter/implementation/resource identity,
runtime/scope/default action, syscall maps (including action data), argument flags,
filesystem enforcement kind and read/write resources, diagnostics, missing coverage
and reviewed-exception reasons. Arrays are shown as whole old/new canonical values;
they are not guessed to be permission subsets. All emitted JSON strings are escaped.
This is a document comparison, not a proof that actual deployments are equivalent,
or that one policy is universally safer. Changes still require trusted review.

See the independent [document-agent workflow](../../../../../../../../demos/koog-document-agent/README.md#profile-compare-review-enforce)
for an actual named-workload profile. A generated deployment measurement is not a
syscall profile; a complete measured trace is not approval or future-path coverage.
