import re

with open('presentation.html', 'r') as f:
    content = f.read()

# 1. Increase SVG viewBox to make room
content = content.replace('viewBox="0 0 1100 800"', 'viewBox="0 0 1100 850"')

# 2. Add the BoB Gate Layers definitions
gate_svg = """
  <!-- ============ BoB PROGRESSIVE GATE ============ -->
  <!-- Layer 1: Whitelist (Slide 2+) -->
  <g id="bobGateWhitelist" class="slide-layer interactive">
    <title>Syscall Whitelist: Blocks undeclared syscalls (e.g., execve)</title>
    <line x1="50" y1="565" x2="1050" y2="565" stroke="var(--accent)" stroke-width="3" stroke-dasharray="12,6" opacity="0.8" filter="url(#glow-teal)"/>
    <text x="70" y="562" fill="var(--accent)" font-size="8" opacity="0.8">L1: Syscall Whitelist</text>
  </g>

  <!-- Layer 2: Lifecycle Phase (Slide 3+) -->
  <g id="bobGatePhase" class="slide-layer interactive">
    <title>Lifecycle Phase Check: Blocks startup syscalls (e.g., bind) during runtime</title>
    <line x1="50" y1="577" x2="1050" y2="577" stroke="#cbd5e0" stroke-width="3" stroke-dasharray="15,8" opacity="0.8"/>
    <text x="70" y="574" fill="#cbd5e0" font-size="8" opacity="0.8">L2: Phase Guard</text>
  </g>

  <!-- Layer 3: Stack Verification (Slide 4+) -->
  <g id="bobGateStack" class="slide-layer interactive">
    <title>Call-Stack Verification: Blocks execution from unexpected origins</title>
    <line x1="50" y1="589" x2="1050" y2="589" stroke="var(--success-color)" stroke-width="3" stroke-dasharray="8,4" opacity="0.8" filter="url(#glow-neon)"/>
    <text x="70" y="586" fill="var(--success-color)" font-size="8" opacity="0.8">L3: Stack Verification</text>
  </g>

  <!-- Layer 4: Thread Scope (Slide 5+) -->
  <g id="bobGateScope" class="slide-layer interactive">
    <title>Thread Context Check: Enforces data boundaries based on user/tenant metadata</title>
    <line x1="50" y1="601" x2="1050" y2="601" stroke="var(--warning-color)" stroke-width="3" stroke-dasharray="10,5" opacity="0.8" filter="url(#glow-orange)"/>
    <text x="70" y="598" fill="var(--warning-color)" font-size="8" opacity="0.8">L4: Scope Context</text>
  </g>
"""

kernel_text = '<text x="430" y="552" fill="#ffffff" font-size="9" opacity="0.4">—— user / kernel boundary ——</text>'
content = content.replace(kernel_text, kernel_text + "\n" + gate_svg)

old_hardened = """  <g id="hardenedWalls" class="slide-layer interactive">
    <title>Application behavior profile (Bill of Behavior) enclosing allowed execution boundaries</title>
    <rect x="60" y="138" width="980" height="335" rx="5" fill="none" stroke="var(--accent)" stroke-width="3" filter="url(#glow-teal)" opacity="0.6"/>
    <line x1="550" y1="138" x2="550" y2="473" stroke="var(--accent)" stroke-width="2" opacity="0.4"/>
    <line x1="320" y1="200" x2="320" y2="473" stroke="var(--accent)" stroke-width="2" opacity="0.4"/>
    <line x1="680" y1="200" x2="680" y2="473" stroke="var(--accent)" stroke-width="2" opacity="0.4"/>
    <line x1="60" y1="300" x2="1040" y2="300" stroke="var(--accent)" stroke-width="1.5" opacity="0.3"/>
  </g>"""
content = content.replace(old_hardened, """  <g id="hardenedWalls" class="slide-layer interactive"></g>""")

content = content.replace('points="550,145 600,145 600,200 680,200 680,260 760,260 760,340 810,340 810,590"',
                          'points="550,145 600,145 600,200 680,200 680,260 760,260 760,340 810,340 810,565"')
content = content.replace('<rect x="785" y="595" width="50" height="55"', '<rect x="785" y="570" width="50" height="55"')
content = content.replace('<line x1="810" y1="590" x2="795" y2="572"', '<line x1="810" y1="565" x2="795" y2="547"')
content = content.replace('<line x1="810" y1="590" x2="825" y2="572"', '<line x1="810" y1="565" x2="825" y2="547"')
content = content.replace('<text x="840" y="600"', '<text x="840" y="575"')
content = content.replace('<rect x="830" y="555"', '<rect x="830" y="530"')
content = content.replace('<text x="840" y="574"', '<text x="840" y="549"')

content = content.replace('<line x1="810" y1="590" x2="795" y2="572" stroke="var(--attack-color)" stroke-width="3" opacity="0.9"/>',
                          '<line x1="810" y1="565" x2="795" y2="547" stroke="var(--attack-color)" stroke-width="3" opacity="0.9"/>')
content = content.replace('<line x1="810" y1="590" x2="825" y2="572" stroke="var(--attack-color)" stroke-width="3" opacity="0.9"/>',
                          '<line x1="810" y1="565" x2="825" y2="547" stroke="var(--attack-color)" stroke-width="3" opacity="0.9"/>')

content = content.replace('points="550,145 600,145 600,240 680,240 680,320 760,320 760,380 840,380 840,460 900,460 900,555 960,610"',
                          'points="550,145 600,145 600,240 680,240 680,320 760,320 760,380 840,380 840,460 900,460 900,555 960,577"')
content = content.replace('<line x1="960" y1="608" x2="945" y2="590"', '<line x1="960" y1="577" x2="945" y2="559"')
content = content.replace('<line x1="960" y1="608" x2="975" y2="588"', '<line x1="960" y1="577" x2="975" y2="557"')

planned_stack = """
    <!-- Planned Future Stack -->
    <g class="interactive">
      <title>The planned remainder of the call stack that was hijacked mid-execution</title>
      <polyline
        points="370,610 370,640 330,640 330,300"
        fill="none" stroke="var(--success-color)" stroke-width="2" stroke-dasharray="4,4" opacity="0.3"
      />
      <text x="335" y="450" fill="var(--success-color)" font-size="8" opacity="0.4">planned return</text>
    </g>
"""
content = content.replace('<text x="515" y="228" fill="var(--success-color)" font-size="8">Service</text>', 
                          '<text x="515" y="228" fill="var(--success-color)" font-size="8">Service</text>' + planned_stack)

content = content.replace('490,590 410,610"', '490,590 410,589"')
content = content.replace('<rect x="350" y="600" width="120" height="58"', '<rect x="350" y="589" width="120" height="58"')
content = content.replace('<line x1="410" y1="610" x2="395" y2="592"', '<line x1="410" y1="589" x2="395" y2="571"')
content = content.replace('<line x1="410" y1="610" x2="425" y2="592"', '<line x1="410" y1="589" x2="425" y2="571"')

content = content.replace('<text x="880" y="708" fill="var(--success-color)" font-size="10" text-anchor="middle">eBPF Stack Unwinder</text>',
                          '<text x="880" y="705" fill="var(--success-color)" font-size="10" text-anchor="middle">eBPF Stack Unwinder</text>')
content = content.replace('<text x="880" y="692" fill="var(--text-secondary)" font-size="9" text-anchor="middle">verifies runtime call stack</text>',
                          '<text x="880" y="718" fill="var(--text-secondary)" font-size="9" text-anchor="middle">verifies runtime call stack</text>')

content = content.replace('y="690"', 'y="710"')
content = content.replace('y="708"', 'y="728"')
content = content.replace('y="722"', 'y="742"')
content = content.replace('y="712"', 'y="732"')
content = content.replace('y="745"', 'y="765"')
content = content.replace('y="705"', 'y="725"')
content = content.replace('y="718"', 'y="738"')

content = content.replace('370,420 370,475 370,555"', '370,420 370,475 370,610"')
content = content.replace('380,420 380,545"', '380,420 380,601"')

old_scope_gate = """    <g class="interactive">
      <title>Kernel checkpoint evaluating dynamic thread context</title>
      <rect x="370" y="545" width="80" height="30" rx="4" fill="var(--warning-color)" opacity="0.9" filter="url(#glow-orange)"/>
      <text x="410" y="561" fill="#0d1117" font-size="8" font-weight="bold" text-anchor="middle">SCOPE</text>
      <text x="410" y="572" fill="#0d1117" font-size="7" text-anchor="middle">GATE</text>
    </g>"""
content = content.replace(old_scope_gate, "")

content = content.replace('<line x1="410" y1="575" x2="410" y2="680"', '<line x1="410" y1="601" x2="410" y2="680"')
content = content.replace('<line x1="410" y1="545" x2="380" y2="520"', '<line x1="380" y1="601" x2="365" y2="583"')
content = content.replace('<line x1="410" y1="545" x2="440" y2="520"', '<line x1="380" y1="601" x2="395" y2="583"')
content = content.replace('<text x="445" y="515"', '<text x="400" y="590"')

content = content.replace('<polyline points="410,575 410,610"', '<polyline points="370,601 370,610"')
content = content.replace('<text x="420" y="600" fill="var(--thread-color)" font-size="8">✓ valid: /public/doc.pdf</text>',
                          '<text x="380" y="620" fill="var(--thread-color)" font-size="8">✓ valid: /public/doc.pdf</text>')

content = content.replace('<rect x="280" y="680"', '<rect x="280" y="710"')
content = content.replace('<text x="295" y="700"', '<text x="295" y="730"')
content = content.replace('<text x="295" y="715"', '<text x="295" y="745"')
content = content.replace('<text x="295" y="728"', '<text x="295" y="758"')

content = content.replace('<rect x="560" y="680"', '<rect x="560" y="710"')
content = content.replace('<text x="575" y="698"', '<text x="575" y="728"')
content = content.replace('<text x="575" y="713"', '<text x="575" y="743"')
content = content.replace('<text x="575" y="726"', '<text x="575" y="756"')
content = content.replace('<text x="575" y="739"', '<text x="575" y="769"')

content = content.replace('<rect x="580" y="740"', '<rect x="580" y="780"')
content = content.replace('y1="758" x2="630" y2="758"', 'y1="798" x2="630" y2="798"')
content = content.replace('y="762"', 'y="802"')
content = content.replace('y1="758" x2="720" y2="758"', 'y1="798" x2="720" y2="798"')
content = content.replace('y1="758" x2="810" y2="758"', 'y1="798" x2="810" y2="798"')
content = content.replace('y1="758" x2="890" y2="758"', 'y1="798" x2="890" y2="798"')
content = content.replace('y="752"', 'y="792"')
content = content.replace('y1="778" x2="630" y2="778"', 'y1="818" x2="630" y2="818"')
content = content.replace('y="778" fill="var(--text-secondary)"', 'y="822" fill="var(--text-secondary)"')
content = content.replace('y1="778" x2="810" y2="778"', 'y1="818" x2="810" y2="818"')
content = content.replace('y="782"', 'y="822"')

content = content.replace("show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'blueThread', 'hardenedWalls', 'execveDenied', 'redAttackSlide2', 'syscalls', 'legend']",
                          "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'blueThread', 'bobGateWhitelist', 'execveDenied', 'redAttackSlide2', 'syscalls', 'legend']")

content = content.replace("show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'blueThread', 'hardenedWalls', 'execveDenied', 'startupBulkhead', 'shutdownGate', 'redAttackSlide3', 'syscalls', 'legend']",
                          "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'blueThread', 'bobGateWhitelist', 'bobGatePhase', 'execveDenied', 'startupBulkhead', 'shutdownGate', 'redAttackSlide3', 'syscalls', 'legend']")

content = content.replace("show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'hardenedWalls', 'execveDenied', 'startupBulkhead', 'shutdownGate', 'stacktraceEnforcement', 'syscalls', 'legend']",
                          "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'bobGateWhitelist', 'bobGatePhase', 'bobGateStack', 'execveDenied', 'startupBulkhead', 'shutdownGate', 'stacktraceEnforcement', 'syscalls', 'legend']")

content = content.replace("show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'hardenedWalls', 'execveDenied', 'startupBulkhead', 'shutdownGate', 'threadScope', 'syscalls', 'legend']",
                          "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'bobGateWhitelist', 'bobGatePhase', 'bobGateStack', 'bobGateScope', 'execveDenied', 'startupBulkhead', 'shutdownGate', 'threadScope', 'syscalls', 'legend']")

native_note = "<br><br><em style=\\\"font-size: 0.85em; opacity: 0.8;\\\">Note: Reliable kernel-space stack unwinding requires frame pointers (-fno-omit-frame-pointer), making this currently viable for native apps (C/C++, Rust, Go, GraalVM) but not standard JIT-compiled JVM/Node apps.</em>"
content = content.replace('neutralizing code-path hijacking even when the syscall itself is whitelisted.</li>', 'neutralizing code-path hijacking even when the syscall itself is whitelisted.</li>' + native_note)

content = content.replace("'stacktraceEnforcement', 'threadScope'", "'stacktraceEnforcement', 'threadScope', 'bobGateWhitelist', 'bobGatePhase', 'bobGateStack', 'bobGateScope'")

with open('presentation.html', 'w') as f:
    f.write(content)
