import re

with open('presentation/presentation.html', 'r') as f:
    html = f.read()

# 1. Add "6: Full Defense" button
nav_html = """  <button onclick="goToSlide(0)" id="btn0">1: Naked App</button>
  <button onclick="goToSlide(1)" id="btn1">2: General BoB</button>
  <button onclick="goToSlide(2)" id="btn2">3: Lifecycle</button>
  <button onclick="goToSlide(3)" id="btn3">4: Stacktrace</button>
  <button onclick="goToSlide(4)" id="btn4">5: Thread Scope</button>
  <button onclick="goToSlide(5)" id="btn5">6: Full Defense</button>"""
html = re.sub(r'  <button onclick="goToSlide\(0\).*?5: Thread Scope</button>', nav_html, html, flags=re.DOTALL)

# 2. Add slide 6 indicator dot
dots_html = """    <div class="dot" id="dot0"></div>
    <div class="dot" id="dot1"></div>
    <div class="dot" id="dot2"></div>
    <div class="dot" id="dot3"></div>
    <div class="dot" id="dot4"></div>
    <div class="dot" id="dot5"></div>"""
html = re.sub(r'    <div class="dot" id="dot0"></div>\n    <div class="dot" id="dot1"></div>\n    <div class="dot" id="dot2"></div>\n    <div class="dot" id="dot3"></div>\n    <div class="dot" id="dot4"></div>', dots_html, html)

# 3. Modify Stack Gate to use Red (var(--attack-color)) and glow-red
html = html.replace(
    '<line x1="50" y1="548" x2="1050" y2="548.1" stroke="var(--success-color)" stroke-width="3" stroke-dasharray="12,5" filter="url(#glow-neon)"/>',
    '<line x1="50" y1="548" x2="1050" y2="548.1" stroke="var(--attack-color)" stroke-width="3" stroke-dasharray="12,5" filter="url(#glow-red)"/>'
)
html = html.replace(
    '<text x="65" y="544" fill="var(--success-color)" font-size="9" font-weight="bold">Stack Gate</text>',
    '<text x="65" y="544" fill="var(--attack-color)" font-size="9" font-weight="bold">Stack Gate</text>'
)

# 4. Modify Badge Stack to Red and adjust position to x=450 (center)
html = html.replace(
    '<rect x="355" y="540" width="110" height="44" rx="4" fill="var(--success-color)" opacity="0.92" filter="url(#glow-neon)"/>\n    <text x="410" y="556" fill="#0d1117"',
    '<rect x="395" y="540" width="110" height="44" rx="4" fill="var(--attack-color)" opacity="0.92" filter="url(#glow-red)"/>\n    <text x="450" y="556" fill="#0d1117"'
)
html = html.replace('text x="410" y="569"', 'text x="450" y="569"')
html = html.replace('text x="410" y="580"', 'text x="450" y="580"')

# 5. Modify Badge Scope position to x=340 (center) -> rect x=270, text x=340
html = html.replace(
    '<rect x="340" y="540" width="140" height="44" rx="4" fill="var(--warning-color)" opacity="0.92" filter="url(#glow-orange)"/>\n    <text x="410" y="556" fill="#0d1117"',
    '<rect x="270" y="540" width="140" height="44" rx="4" fill="var(--warning-color)" opacity="0.92" filter="url(#glow-orange)"/>\n    <text x="340" y="556" fill="#0d1117"'
)

# 6. Create DIM versions of badges and insert them after the active badges
badge_execve_dim = '''
  <g id="badgeExecveWhitelist_dim" class="slide-layer">
    <rect x="750" y="540" width="122" height="44" rx="4" fill="var(--accent)" opacity="0.15"/>
    <text x="811" y="556" fill="var(--accent)" font-size="9" font-weight="bold" text-anchor="middle" opacity="0.5">✗ NOT WHITELISTED</text>
    <text x="811" y="569" fill="var(--accent)" font-size="7" text-anchor="middle" opacity="0.5">BoB: execve ∉ profile</text>
    <text x="811" y="580" fill="var(--accent)" font-size="7" text-anchor="middle" opacity="0.5">eBPF → SIGKILL</text>
  </g>'''

badge_mprotect_dim = '''
  <g id="badgeMprotectPhase_dim" class="slide-layer">
    <rect x="900" y="540" width="122" height="44" rx="4" fill="#e2e8f0" opacity="0.15"/>
    <text x="961" y="556" fill="#e2e8f0" font-size="9" font-weight="bold" text-anchor="middle" opacity="0.5">⊘ PHASE-LOCKED</text>
    <text x="961" y="569" fill="#e2e8f0" font-size="7" text-anchor="middle" opacity="0.5">mprotect dropped</text>
    <text x="961" y="580" fill="#e2e8f0" font-size="7" text-anchor="middle" opacity="0.5">after k8s readiness</text>
  </g>'''

badge_read_stack_dim = '''
  <g id="badgeReadStack_dim" class="slide-layer">
    <rect x="395" y="540" width="110" height="44" rx="4" fill="var(--attack-color)" opacity="0.15"/>
    <text x="450" y="556" fill="var(--attack-color)" font-size="9" font-weight="bold" text-anchor="middle" opacity="0.5">✗ BAD STACK</text>
    <text x="450" y="569" fill="var(--attack-color)" font-size="7" text-anchor="middle" opacity="0.5">ROP chain detected</text>
    <text x="450" y="580" fill="var(--attack-color)" font-size="7" text-anchor="middle" opacity="0.5">IP sequence mismatch</text>
  </g>'''

badge_read_scope_dim = '''
  <g id="badgeReadScope_dim" class="slide-layer">
    <rect x="270" y="540" width="140" height="44" rx="4" fill="var(--warning-color)" opacity="0.15"/>
    <text x="340" y="556" fill="var(--warning-color)" font-size="9" font-weight="bold" text-anchor="middle" opacity="0.5">⊘ SCOPE DENIED</text>
    <text x="340" y="569" fill="var(--warning-color)" font-size="7" text-anchor="middle" opacity="0.5">/etc/passwd ∉ /public/</text>
    <text x="340" y="580" fill="var(--warning-color)" font-size="7" text-anchor="middle" opacity="0.5">eBPF LSM thread-ctx map</text>
  </g>'''

html = html.replace('  <g id="badgeMprotectPhase"', badge_execve_dim + '\n  <g id="badgeMprotectPhase"')
html = html.replace('  <g id="badgeReadStack"', badge_mprotect_dim + '\n  <g id="badgeReadStack"')
html = html.replace('  <g id="badgeReadScope"', badge_read_stack_dim + '\n  <g id="badgeReadScope"')
html = html.replace('  <!-- ============ SLIDE 1: Naked App ============ -->', badge_read_scope_dim + '\n  <!-- ============ SLIDE 1: Naked App ============ -->')

# 7. Modify Slide 4 Attack path to land at x=450
html = html.replace('600,510 550,520 480,530 410,540', '600,510 550,520 500,530 450,540')

# 8. Simplify Slide 5 (remove green dashed line) and modify path to land at x=340
thread_scope_old = """  <!-- ============ SLIDE 5: Thread Scope ============ -->
  <g id="threadScope" class="slide-layer">
    <!-- Valid Stack (Green dashed) -->
    <g class="interactive">
      <title>Legitimate application thread executing expected logic within intended boundaries</title>
      <polyline
        points="550,145 550,180 510,180 510,240 470,240 470,300 430,300 430,360 400,360 400,420 370,420 370,475 370,540 370,620"
        fill="none" stroke="var(--success-color)" stroke-width="2" stroke-dasharray="6,3" opacity="0.5"
      />
    </g>
    <text x="415" y="168" fill="var(--success-color)" font-size="8" opacity="0.7">valid stacktrace ✓</text>

    <!-- Trojan Thread -->
    <g class="interactive">
      <title>Malicious execution path passing stacktrace validation due to Confused Deputy vulnerability</title>
      <polyline
        points="550,145 550,180 510,180 510,240 470,240 470,300 430,300 430,360 400,360 400,420 380,420 380,540"
        fill="none" stroke="var(--thread-color)" stroke-width="4.5" stroke-linecap="round" stroke-linejoin="round" opacity="0.8"
        class="path-draw"
      />
      <!-- Red inside to indicate malicious data -->
      <polyline
        points="550,145 550,180 510,180 510,240 470,240 470,300 430,300 430,360 400,360 400,420 380,420 380,540"
        fill="none" stroke="var(--attack-color)" stroke-width="2" stroke-dasharray="6,2" opacity="0.9"
      />
    </g>"""

thread_scope_new = """  <!-- ============ SLIDE 5: Thread Scope ============ -->
  <g id="threadScope" class="slide-layer">
    <!-- Trojan Thread -->
    <g class="interactive">
      <title>Malicious execution path passing stacktrace validation due to Confused Deputy vulnerability</title>
      <polyline
        points="550,145 550,180 510,180 510,240 470,240 470,300 430,300 430,360 400,360 400,420 340,420 340,540"
        fill="none" stroke="var(--thread-color)" stroke-width="4.5" stroke-linecap="round" stroke-linejoin="round" opacity="0.8"
        class="path-draw"
      />
      <!-- Red inside to indicate malicious data -->
      <polyline
        points="550,145 550,180 510,180 510,240 470,240 470,300 430,300 430,360 400,360 400,420 340,420 340,540"
        fill="none" stroke="var(--attack-color)" stroke-width="2" stroke-dasharray="6,2" opacity="0.9"
      />
    </g>"""
html = html.replace(thread_scope_old, thread_scope_new)

# 9. Modify slides arrays
html = html.replace(
    "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'blueThread', 'lineWhitelist_dim', 'linePhase_active', 'badgeExecveWhitelist', 'badgeMprotectPhase', 'startupBulkhead', 'shutdownGate', 'redAttackSlide3', 'syscalls', 'legend']",
    "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'blueThread', 'lineWhitelist_dim', 'linePhase_active', 'badgeExecveWhitelist_dim', 'badgeMprotectPhase', 'startupBulkhead', 'shutdownGate', 'redAttackSlide3', 'syscalls', 'legend']"
)
html = html.replace(
    "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'lineWhitelist_dim', 'linePhase_dim', 'lineStack_active', 'badgeExecveWhitelist', 'badgeMprotectPhase', 'badgeReadStack', 'startupBulkhead', 'shutdownGate', 'stacktraceEnforcement', 'syscalls', 'legend']",
    "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'lineWhitelist_dim', 'linePhase_dim', 'lineStack_active', 'badgeExecveWhitelist_dim', 'badgeMprotectPhase_dim', 'badgeReadStack', 'startupBulkhead', 'shutdownGate', 'stacktraceEnforcement', 'syscalls', 'legend']"
)
html = html.replace(
    "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'lineWhitelist_dim', 'linePhase_dim', 'lineStack_dim', 'lineScope_active', 'badgeExecveWhitelist', 'badgeMprotectPhase', 'badgeReadScope', 'startupBulkhead', 'shutdownGate', 'threadScope', 'syscalls', 'legend']",
    "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'lineWhitelist_dim', 'linePhase_dim', 'lineStack_dim', 'lineScope_active', 'badgeExecveWhitelist_dim', 'badgeMprotectPhase_dim', 'badgeReadStack_dim', 'badgeReadScope', 'startupBulkhead', 'shutdownGate', 'threadScope', 'syscalls', 'legend']"
)

# 10. Add Slide 6 object
slide_6 = """  {
    title: "6. Defense in Depth — Complete Profiling",
    context: `
      <div class="context-title">Developer Context:</div>
      <ul>
        <li><strong>Holistic Security:</strong> A truly restricted workload relies on multiple overlapping layers of enforcement mapping the exact expected behavior of the application at every dimension.</li>
        <li><strong>Whitelist</strong> stops unauthorized binaries. <strong>Phase Lock</strong> drops capabilities post-initialization.</li>
        <li><strong>Stack Gate</strong> catches memory exploits. <strong>Scope Gate</strong> bounds data access.</li>
        <li><strong>Result:</strong> All attack vectors hit an impenetrable wall. The workload is strictly confined to its behavioral bill.</li>
      </ul>
    `,
    show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'lineWhitelist_dim', 'linePhase_dim', 'lineStack_dim', 'lineScope_dim', 'badgeExecveWhitelist_dim', 'badgeMprotectPhase_dim', 'badgeReadStack_dim', 'badgeReadScope_dim', 'redAttackSlide2', 'redAttackSlide3', 'stacktraceEnforcement', 'threadScope', 'startupBulkhead', 'shutdownGate', 'syscalls', 'legend']
  }
];"""
html = html.replace("  }\n];", slide_6)

# 11. Add slide 6 text update logic
html = html.replace(
    """  // Highlight correct dot
  for(let i=0; i<5; i++){
    document.getElementById('dot'+i).style.opacity = '0.3';
    document.getElementById('dot'+i).style.transform = 'scale(1)';
  }""",
    """  // Highlight correct dot
  for(let i=0; i<6; i++){
    document.getElementById('dot'+i).style.opacity = '0.3';
    document.getElementById('dot'+i).style.transform = 'scale(1)';
  }"""
)
html = html.replace(
    """  for(let i=0; i<5; i++){
    let btn = document.getElementById('btn'+i);
    if(i === index) {""",
    """  for(let i=0; i<6; i++){
    let btn = document.getElementById('btn'+i);
    if(i === index) {"""
)
html = html.replace("if(currentSlide < 4)", "if(currentSlide < 5)")

with open('presentation/presentation.html', 'w') as f:
    f.write(html)
print("Updated presentation to add Slide 6 and dim badges!")
