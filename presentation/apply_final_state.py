import re

with open('presentation/presentation.html', 'r') as f:
    html = f.read()

# 1. SHUTDOWN PHASE box
html = html.replace(
    '<rect x="50" y="490" width="1000" height="50" rx="6" fill="#151b29" stroke="var(--border-color)" stroke-width="1.5"/>\n    <text x="70" y="512"',
    '<rect x="50" y="490" width="1000" height="40" rx="6" fill="#151b29" stroke="var(--border-color)" stroke-width="1.5"/>\n    <text x="70" y="508"'
)
html = html.replace('text x="70" y="528"', 'text x="70" y="522"')

# 2. KERNEL-SPACE box
html = html.replace(
    '<rect x="50" y="560" width="1000" height="200"',
    '<rect x="50" y="570" width="1000" height="190"'
)
html = html.replace('text x="70" y="583"', 'text x="70" y="593"')

# 3. Boundary -> Enforcement Lines + Badges
boundary_str = '''  <!-- Boundary -->
  <line x1="50" y1="555" x2="1050" y2="555" stroke="#ffffff" stroke-width="2.5" stroke-dasharray="10,4" opacity="0.4"/>
  <text x="430" y="552" fill="#ffffff" font-size="9" opacity="0.4">—— user / kernel boundary ——</text>'''

new_boundary = '''  <!-- ═══════════════════════════════════════════════════════════════════ -->
  <!-- ENFORCEMENT LINES (Boundary Zone)                                   -->
  <!-- ═══════════════════════════════════════════════════════════════════ -->

  <g id="lineWhitelist_active" class="slide-layer interactive">
    <line x1="50" y1="564" x2="1050" y2="564" stroke="var(--accent)" stroke-width="3" stroke-dasharray="12,5" filter="url(#glow-teal)"/>
    <text x="65" y="560" fill="var(--accent)" font-size="9" font-weight="bold">BoB Whitelist</text>
  </g>
  <g id="lineWhitelist_dim" class="slide-layer">
    <line x1="50" y1="564" x2="1050" y2="564" stroke="#4a5568" stroke-width="2" stroke-dasharray="12,5" opacity="0.4"/>
    <text x="65" y="560" fill="#4a5568" font-size="8" opacity="0.6">BoB Whitelist</text>
  </g>

  <g id="linePhase_active" class="slide-layer interactive">
    <line x1="50" y1="556" x2="1050" y2="556" stroke="#e2e8f0" stroke-width="3" stroke-dasharray="12,5" filter="url(#glow-blue)"/>
    <text x="65" y="552" fill="#e2e8f0" font-size="9" font-weight="bold">Phase Lock</text>
  </g>
  <g id="linePhase_dim" class="slide-layer">
    <line x1="50" y1="556" x2="1050" y2="556" stroke="#4a5568" stroke-width="2" stroke-dasharray="12,5" opacity="0.4"/>
    <text x="65" y="552" fill="#4a5568" font-size="8" opacity="0.6">Phase Lock</text>
  </g>

  <g id="lineStack_active" class="slide-layer interactive">
    <line x1="50" y1="548" x2="1050" y2="548" stroke="var(--success-color)" stroke-width="3" stroke-dasharray="12,5" filter="url(#glow-neon)"/>
    <text x="65" y="544" fill="var(--success-color)" font-size="9" font-weight="bold">Stack Gate</text>
  </g>
  <g id="lineStack_dim" class="slide-layer">
    <line x1="50" y1="548" x2="1050" y2="548" stroke="#4a5568" stroke-width="2" stroke-dasharray="12,5" opacity="0.4"/>
    <text x="65" y="544" fill="#4a5568" font-size="8" opacity="0.6">Stack Gate</text>
  </g>

  <g id="lineScope_active" class="slide-layer interactive">
    <line x1="50" y1="540" x2="1050" y2="540" stroke="var(--warning-color)" stroke-width="3" stroke-dasharray="12,5" filter="url(#glow-orange)"/>
    <text x="65" y="536" fill="var(--warning-color)" font-size="9" font-weight="bold">Scope Gate</text>
  </g>
  <g id="lineScope_dim" class="slide-layer">
    <line x1="50" y1="540" x2="1050" y2="540" stroke="#4a5568" stroke-width="2" stroke-dasharray="12,5" opacity="0.4"/>
    <text x="65" y="536" fill="#4a5568" font-size="8" opacity="0.6">Scope Gate</text>
  </g>

  <!-- BADGES -->
  <g id="badgeExecveWhitelist" class="slide-layer interactive">
    <rect x="750" y="540" width="122" height="44" rx="4" fill="var(--accent)" opacity="0.92" filter="url(#glow-teal)"/>
    <text x="811" y="556" fill="#0d1117" font-size="9" font-weight="bold" text-anchor="middle">✗ NOT WHITELISTED</text>
    <text x="811" y="569" fill="#0d1117" font-size="7" text-anchor="middle">BoB: execve ∉ profile</text>
    <text x="811" y="580" fill="#0d1117" font-size="7" text-anchor="middle">eBPF → SIGKILL</text>
  </g>

  <g id="badgeMprotectPhase" class="slide-layer interactive">
    <rect x="900" y="540" width="122" height="44" rx="4" fill="#e2e8f0" opacity="0.92" filter="url(#glow-blue)"/>
    <text x="961" y="556" fill="#0d1117" font-size="9" font-weight="bold" text-anchor="middle">⊘ PHASE-LOCKED</text>
    <text x="961" y="569" fill="#0d1117" font-size="7" text-anchor="middle">mprotect dropped</text>
    <text x="961" y="580" fill="#0d1117" font-size="7" text-anchor="middle">after k8s readiness</text>
  </g>

  <g id="badgeReadStack" class="slide-layer interactive">
    <rect x="355" y="540" width="110" height="44" rx="4" fill="var(--success-color)" opacity="0.92" filter="url(#glow-neon)"/>
    <text x="410" y="556" fill="#0d1117" font-size="9" font-weight="bold" text-anchor="middle">✗ BAD STACK</text>
    <text x="410" y="569" fill="#0d1117" font-size="7" text-anchor="middle">ROP chain detected</text>
    <text x="410" y="580" fill="#0d1117" font-size="7" text-anchor="middle">IP sequence mismatch</text>
  </g>

  <g id="badgeReadScope" class="slide-layer interactive">
    <rect x="340" y="540" width="140" height="44" rx="4" fill="var(--warning-color)" opacity="0.92" filter="url(#glow-orange)"/>
    <text x="410" y="556" fill="#0d1117" font-size="9" font-weight="bold" text-anchor="middle">⊘ SCOPE DENIED</text>
    <text x="410" y="569" fill="#0d1117" font-size="7" text-anchor="middle">/etc/passwd ∉ /public/</text>
    <text x="410" y="580" fill="#0d1117" font-size="7" text-anchor="middle">eBPF LSM thread-ctx map</text>
  </g>'''

html = html.replace(boundary_str, new_boundary)

# 4. Syscall Boxes & Attack Paths Adjustments
# Syscalls
html = html.replace('y="610" width="110"', 'y="620" width="110"')
html = html.replace('y="610" width="100"', 'y="620" width="100"')
html = html.replace('y="610" width="120"', 'y="620" width="120"')
html = html.replace('y="634"', 'y="644"')

# Blue thread
html = html.replace('370,555 370,610', '370,540 370,620')
html = html.replace('370,610 365,600 375,600', '370,620 365,610 375,610')
html = html.replace('x="380" y="595"', 'x="380" y="605"')

# Slide 1 Red Attack
html = html.replace('810,590"', '810,540 810,620"')
html = html.replace('810,610 805,598 815,598', '810,620 805,608 815,608')
html = html.replace('cy="590"', 'cy="600"')
html = html.replace('y="655" fill="var(--attack-color)"', 'y="665" fill="var(--attack-color)"')

# Slide 2 Red Attack -> stop at 540
html = re.sub(r'id="redAttackSlide2".*?810,340 810,590"', 'id="redAttackSlide2" class="slide-layer interactive">\n    <title>Malicious execution path intercepted by General BoB kernel whitelist</title>\n    <polyline\n      points="550,145 600,145 600,200 680,200 680,260 760,260 760,340 810,340 810,540"', html, flags=re.DOTALL)

# Slide 3 Red Attack -> stop at 540
html = re.sub(r'id="redAttackSlide3".*?900,555 960,610"', 'id="redAttackSlide3" class="slide-layer interactive">\n    <title>Malicious library load attempt blocked by Startup Phase sealing</title>\n    <polyline\n      points="550,145 600,145 600,240 680,240 680,320 760,320 760,380 840,380 840,460 961,460 961,540"', html, flags=re.DOTALL)

# Slide 4 Red Attack -> stop at 540
html = re.sub(r'id="stacktraceEnforcement".*?650,475 600,510 550,520 480,530 410,555"', 'id="stacktraceEnforcement" class="slide-layer">\n    <!-- Intended path (Green) -->\n    <g class="interactive">\n      <title>Legitimate application thread executing expected logic within intended boundaries</title>\n      <polyline\n        points="550,145 550,180 510,180 510,240 470,240 470,300 430,300 430,360 400,360 400,420 370,420 370,475 370,540 370,620"\n        fill="none" stroke="var(--success-color)" stroke-width="2.5" stroke-dasharray="8,4" filter="url(#glow-neon)" opacity="0.8"\n      />\n    </g>\n    <text x="555" y="168" fill="var(--success-color)" font-size="8">Controller</text>\n    <text x="515" y="228" fill="var(--success-color)" font-size="8">Service</text>\n    <text x="475" y="288" fill="var(--success-color)" font-size="8">FileUtil</text>\n    \n    <g class="interactive">\n      <title>The call stack statically expected by the behavioral profile (BoB)</title>\n      <rect x="20" y="660" width="310" height="40" rx="4" fill="#1a202c" stroke="var(--success-color)" stroke-width="1.5"/>\n      <text x="175" y="678" fill="var(--success-color)" font-size="10" text-anchor="middle">Expected call stack:</text>\n      <text x="175" y="692" fill="var(--success-color)" font-size="9" text-anchor="middle" opacity="0.8">Controller→Service→FileUtil→read()</text>\n    </g>\n\n    <!-- Attack path (Red) -->\n    <g class="interactive">\n      <title>Malicious execution path bypassing intended application boundaries to achieve RCE</title>\n      <polyline\n        points="550,145 600,180 650,220 700,260 750,300 780,340 780,380 750,420 700,450 650,475 600,510 550,520 480,530 410,540"', html, flags=re.DOTALL)

# Slide 5 Red Attack -> stop at 540
html = html.replace('380,610', '380,540')
html = html.replace('370,555', '370,540 370,620')
html = html.replace('380,606 380,660', '380,584 380,660')
html = html.replace('380,606 380,650', '380,584 380,650')

# 5. Remove redundant crash logic in Slide 4
html = re.sub(r'<rect x="350" y="600".*?DENIED ✕</text>', '', html, flags=re.DOTALL)

# 6. Remove redundant crash logic in Slide 5
html = re.sub(r'<!-- Shatter -->.*?DENIED ✕</text>', '', html, flags=re.DOTALL)

# 7. Update JS arrays
html = html.replace(
    "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'blueThread', 'hardenedWalls', 'execveDenied', 'redAttackSlide2', 'syscalls', 'legend']",
    "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'blueThread', 'lineWhitelist_active', 'badgeExecveWhitelist', 'redAttackSlide2', 'syscalls', 'legend']"
)
html = html.replace(
    "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'blueThread', 'hardenedWalls', 'execveDenied', 'startupBulkhead', 'shutdownGate', 'redAttackSlide3', 'syscalls', 'legend']",
    "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'blueThread', 'lineWhitelist_dim', 'linePhase_active', 'badgeExecveWhitelist', 'badgeMprotectPhase', 'startupBulkhead', 'shutdownGate', 'redAttackSlide3', 'syscalls', 'legend']"
)
html = html.replace(
    "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'hardenedWalls', 'execveDenied', 'startupBulkhead', 'shutdownGate', 'stacktraceEnforcement', 'syscalls', 'legend']",
    "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'lineWhitelist_dim', 'linePhase_dim', 'lineStack_active', 'badgeExecveWhitelist', 'badgeMprotectPhase', 'badgeReadStack', 'startupBulkhead', 'shutdownGate', 'stacktraceEnforcement', 'syscalls', 'legend']"
)
html = html.replace(
    "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'hardenedWalls', 'execveDenied', 'startupBulkhead', 'shutdownGate', 'threadScope', 'syscalls', 'legend']",
    "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'lineWhitelist_dim', 'linePhase_dim', 'lineStack_dim', 'lineScope_active', 'badgeExecveWhitelist', 'badgeMprotectPhase', 'badgeReadScope', 'startupBulkhead', 'shutdownGate', 'threadScope', 'syscalls', 'legend']"
)

html = html.replace(
    "'redAttackSlide1', 'syscalls', 'legend', 'hardenedWalls', 'execveDenied',",
    "'redAttackSlide1', 'syscalls', 'legend', 'badgeExecveWhitelist', 'badgeMprotectPhase', 'badgeReadStack', 'badgeReadScope',\n    'lineWhitelist_active', 'lineWhitelist_dim', 'linePhase_active', 'linePhase_dim', 'lineStack_active', 'lineStack_dim', 'lineScope_active', 'lineScope_dim',"
)

with open('presentation/presentation.html', 'w') as f:
    f.write(html)
print("Applied final robust layout!")
