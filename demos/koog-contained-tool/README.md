# Bounded generated text transformer

The advertised upstream Koog registry contains only `uppercase_ascii`. Its
serializable argument is `TextArguments(text)`. It uppercases ASCII `a`–`z`
deterministically, preserving other Unicode characters and UTF-8 byte length.
Neither a path nor an operation/policy selector is part of the argument schema.

```kotlin
PortalRuntime.open(TextTools.configuration(cacheDirectory)).use { runtime ->
    val registry = TextTools.registry(runtime)
    val tool = registry.getTool<PortalToolAdapter<TextArguments, String>>()
    val result = tool.execute(TextArguments("aBc éß🙂")) // "ABC éß🙂"
}
```

Call this from a coroutine. Keep the runtime alive for the complete owned
operation and cleanup; `use` closes its worker groups. Host JSON parsing,
validation, generated codecs and the service mapping run outside containment.
Only the generated service call executes in the protected child.

## Bounds and packaging

- Arguments and results: **64 KiB strict UTF-8 text**, checked on the host and
  in the first-party worker. Invalid Unicode and oversized text reject.
- Requests and responses: independently **128 KiB Portal wire payloads**,
  including encoded data overhead. Capabilities are disabled for this service.
- One stateful worker, eight logical admissions, finite startup/call/close
  budgets. These are example settings, not measured production sizing.
- Static required worker baseline; resource profiles are not permission policies.
- `service-api` has no Koog dependency. `portalWorker` declares its own service
  API and Portal worker dependency. The normal host JAR embeds a format2 verified
  ZIP as opaque data, rather than exposing guest classes on the host classpath.

The actual codegen plugin produces all stubs, dispatchers and metadata. Do not
edit generated output. The temporary source fixture exports the real plugin's
JAR classpath; coordinator-owned production include/plugin resolution and runtime
publication are still pending. See the [packaged consumer](../../tests/consumers/koog-portal/README.md).

`koogNativeFixtures=true` adds a separate denied-resource API/guest **for tests
only**. It is absent from production metadata/ZIPs and is never added to the
advertised registry, even in the native fixture. No remote model account or
credential configuration is needed for the deterministic probes.
