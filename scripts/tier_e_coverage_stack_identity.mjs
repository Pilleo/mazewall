/** Normalize only process-local identities; preserve every frame and its method/BCI. */
export function normalizeCoverageStacks(stacks) {
  const hidden = new Map();
  const loaders = new Map();
  const prefixCounts = new Map();
  const result = {};
  for (const key of Object.keys(stacks).sort((a, b) => Number(a) - Number(b))) {
    result[key] = stacks[key].map(fields => {
      if (fields.length < 3 || (fields.length - 3) % 6 !== 0) throw new Error('Malformed full-stack record');
      const normalized = [...fields];
      for (let offset = 3; offset < fields.length; offset += 6) {
        const loader = fields[offset];
        if (/^loader-\d+$/.test(loader)) {
          if (!loaders.has(loader)) loaders.set(loader, `loader-local-${loaders.size}`);
          normalized[offset] = loaders.get(loader);
        }
        const name = fields[offset + 1];
        const match = /^(.*)[./]0x[0-9a-fA-F]+$/.exec(name);
        if (match) {
          const identity = `${loader}:${name}`;
          if (!hidden.has(identity)) {
            const prefix = `${normalized[offset]}:${match[1]}`;
            const ordinal = prefixCounts.get(prefix) ?? 0;
            prefixCounts.set(prefix, ordinal + 1);
            hidden.set(identity, `${match[1]}#hidden-local-${ordinal}`);
          }
          normalized[offset + 1] = hidden.get(identity);
        }
      }
      return normalized;
    }).sort((a, b) => JSON.stringify(a).localeCompare(JSON.stringify(b)));
  }
  return result;
}
