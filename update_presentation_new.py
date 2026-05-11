import re

with open('presentation/presentation.html', 'r') as f:
    text = f.read()

# 1. Clean up SHUTDOWN phase box so it doesn't overlap the central thread
text = text.replace('<rect x="60" y="490" width="980" height="50" rx="5" fill="#1e2330" stroke="#30363d" stroke-width="1" opacity="0.8"/>',
                    '<rect x="660" y="490" width="380" height="50" rx="5" fill="#1e2330" stroke="#30363d" stroke-width="1" opacity="0.8"/>')
text = text.replace('<text x="70" y="505" fill="#8b949e" font-size="9" font-weight="bold">SHUTDOWN PHASE</text>',
                    '<text x="670" y="505" fill="#8b949e" font-size="9" font-weight="bold">SHUTDOWN PHASE</text>')
text = text.replace('<text x="70" y="525" fill="#8b949e" font-size="7">connection drain · flush logs · deregister · release locks</text>',
                    '<text x="670" y="525" fill="#8b949e" font-size="7">connection drain · flush logs · deregister · release locks</text>')

# 2. Clean up STARTUP phase box
text = text.replace('<rect x="60" y="70" width="980" height="50" rx="5" fill="#1e2330" stroke="#30363d" stroke-width="1" opacity="0.8"/>',
                    '<rect x="60" y="70" width="400" height="50" rx="5" fill="#1e2330" stroke="#30363d" stroke-width="1" opacity="0.8"/>')

# 3. Redesign the BoB Whitelist (Slide 2)
new_bob_whitelist = """
  <!-- Slide 2: BoB Whitelist Filter Bank -->
  <g id="bobWhitelist" class="slide-layer interactive">
    <title>BoB Whitelist: Explicitly declared allowed syscalls</title>
    <!-- Profile Box around user-space (faded) -->
    <rect x="50" y="138" width="1000" height="340" rx="5" fill="none" stroke="var(--accent)" stroke-width="2" stroke-dasharray="10,5" opacity="0.3"/>
    
    <!-- Thick Gate at Kernel Boundary -->
    <rect x="50" y="580" width="1000" height="8" rx="2" fill="var(--accent)" opacity="0.8" filter="url(#glow-teal)"/>
    <text x="70" y="575" fill="var(--accent)" font-size="10" font-weight="bold">BoB Whitelist Gate</text>
    
    <!-- Cutouts/Ports for allowed syscalls -->
    <rect x="350" y="578" width="120" height="12" fill="#0d1117" /> <!-- read port -->
    <rect x="885" y="578" width="120" height="12" fill="#0d1117" /> <!-- bind port (Slide 2 allows it) -->
  </g>
"""
text = re.sub(r'<g id="hardenedWalls" class="slide-layer interactive">.*?</g>', new_bob_whitelist, text, flags=re.DOTALL)

# Slide 2 Attack crashes into Whitelist Gate
text = text.replace('points="550,145 600,145 600,200 680,200 680,260 760,260 760,340 810,340 810,590"',
                    'points="550,145 600,145 600,200 680,200 680,260 760,260 760,340 810,340 810,580"')
text = text.replace('<rect x="785" y="595" width="50" height="55"', '<rect x="785" y="585" width="50" height="55"')
text = text.replace('<line x1="810" y1="590" x2="795" y2="572" stroke="var(--attack-color)"', '<line x1="810" y1="580" x2="795" y2="562" stroke="var(--attack-color)"')
text = text.replace('<line x1="810" y1="590" x2="825" y2="572" stroke="var(--attack-color)"', '<line x1="810" y1="580" x2="825" y2="562" stroke="var(--attack-color)"')
text = text.replace('<text x="840" y="600" fill="var(--attack-color)"', '<text x="840" y="590" fill="var(--attack-color)"')
text = text.replace('<rect x="830" y="555" width="125" height="25"', '<rect x="830" y="545" width="125" height="25"')
text = text.replace('<text x="840" y="574" fill="var(--accent)"', '<text x="840" y="564" fill="var(--accent)"')


# 4. Redesign Phase Guard (Slide 3)
text = text.replace('id="startupBulkhead"', 'id="startupBulkhead" class="slide-layer interactive phase-guard"')
text = text.replace('id="shutdownGate"', 'id="shutdownGate" class="slide-layer interactive phase-guard"')

new_phase_guard = """
  <!-- Slide 3: Phase Guard at Kernel -->
  <g id="bobPhaseGuard" class="slide-layer interactive phase-guard">
    <title>Lifecycle Phase Guard: Blocks startup syscalls during runtime</title>
    <!-- Solid block filling the bind() port on the gate -->
    <rect x="885" y="578" width="120" height="12" fill="#cbd5e0" opacity="0.9" />
    <text x="895" y="575" fill="#cbd5e0" font-size="9" font-weight="bold">Phase Locked</text>
  </g>
"""
text = text.replace('<!-- ==================== ANIMATIONS ==================== -->', new_phase_guard + '\n<!-- ==================== ANIMATIONS ==================== -->')

text = text.replace('points="550,145 600,145 600,240 680,240 680,320 760,320 760,380 840,380 840,460 900,460 900,555 960,610"',
                    'points="550,145 600,145 600,240 680,240 680,320 760,320 760,380 840,380 840,460 900,460 900,555 960,578"')
text = text.replace('<line x1="960" y1="608" x2="945" y2="590"', '<line x1="960" y1="578" x2="945" y2="560"')
text = text.replace('<line x1="960" y1="608" x2="975" y2="588"', '<line x1="960" y1="578" x2="975" y2="558"')

# Move startupBulkhead up so it doesn't look like it's blocking the RUNTIME zone from the SHUTDOWN zone
text = text.replace('<line x1="60" y1="473" x2="1040" y2="473" stroke="#cbd5e0"', '<line x1="60" y1="130" x2="460" y2="130" stroke="#cbd5e0"')
text = text.replace('<text x="500" y="470"', '<text x="100" y="125"')

# Add CSS for group hover
css_addition = """
    /* Group hover for phase-guard elements */
    .phase-guard:hover {
      filter: drop-shadow(0 0 8px rgba(203, 213, 224, 0.8));
      cursor: pointer;
    }
"""
text = text.replace('</style>', css_addition + '</style>')

# 5. Slide 4 Stacktrace Tunnel
stack_viz = """
  <!-- Slide 4: Stacktrace Tunnel -->
  <g id="stacktraceTunnel" class="slide-layer interactive">
    <title>Enforced Call-Path Execution Tunnel</title>
    <!-- Glowing path around the blue thread -->
    <polyline points="410,145 410,210 470,210 470,300 410,300 410,420 370,420 370,475 370,580"
      fill="none" stroke="var(--success-color)" stroke-width="25" stroke-linecap="round" stroke-linejoin="round" opacity="0.15" filter="url(#glow-neon)"/>
    <text x="210" y="320" fill="var(--success-color)" font-size="10" font-weight="bold" opacity="0.8">Enforced Call-Path Tunnel</text>
  </g>
"""
text = text.replace('<!-- ==================== ANIMATIONS ==================== -->', stack_viz + '\n<!-- ==================== ANIMATIONS ==================== -->')

# 6. JS Updates
text = text.replace("'hardenedWalls'", "'bobWhitelist'")
text = text.replace("'startupBulkhead', 'shutdownGate'", "'startupBulkhead', 'shutdownGate', 'bobPhaseGuard'")
text = text.replace("'stacktraceEnforcement'", "'stacktraceEnforcement', 'stacktraceTunnel'")

with open('presentation/presentation.html', 'w') as f:
    f.write(text)
