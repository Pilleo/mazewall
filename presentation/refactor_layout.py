import re

with open('presentation.html', 'r') as f:
    content = f.read()

# 1. FIX THE LIFECYCLE BOXES OVERLAPPING RUNTIME THREADS
# Currently: 
# RUNTIME is y=138 to 473 (height 335)
# SHUTDOWN is y=490 to 540
# Kernel boundary is y=555
# If we just move SHUTDOWN to the right side!
# Let's make SHUTDOWN: x=660, width=380. RUNTIME is still full width, but the blue thread is at x=370, so it misses the SHUTDOWN box!
content = content.replace('<rect x="60" y="490" width="980" height="50"', '<rect x="660" y="490" width="380" height="50"')
content = content.replace('<text x="70" y="505" fill="#8b949e"', '<text x="670" y="505" fill="#8b949e"')
content = content.replace('<text x="70" y="525" fill="#8b949e"', '<text x="670" y="525" fill="#8b949e"')

# Same for STARTUP PHASE box?
# STARTUP is y=70 to 120. Blue thread starts at Ingress at 550,135 (below startup). So it doesn't cross it. 
# But let's make STARTUP x=60, width=380.
content = content.replace('<rect x="60" y="70" width="980" height="50"', '<rect x="60" y="70" width="400" height="50"')

# Now the user-space has clear areas.

# 2. GROUP THE LIFECYCLE LINES AND MAKE THEM THE SAME COLOR
# User: "two lifecycle line look thic and unpenetrable, yet call is going through just fine. And there is the thind dashed line for the same thing. They at least must be of the same color."
# The blue thread goes: 370,420 -> 370,475 -> 370,555. It crosses y=473 (startupBulkhead).
# Let's make the blue thread STOP crossing startupBulkhead.
# The blue thread shouldn't cross the bulkhead.
# Wait, the bulkhead is between STARTUP and RUNTIME?
# No, "startupBulkhead" is at y=473, which is the bottom of the RUNTIME box.
# Ah! "startupBulkhead" is at y=473 because I misunderstood the flow?
# In original, startupBulkhead: <line x1="60" y1="473" x2="1040" y2="473" ... />
# The user's text for slide 3 says: "The readiness probe acts as a phase-seal."
# STARTUP is at top. RUNTIME is in middle. SHUTDOWN is at bottom.
# So the transition from STARTUP -> RUNTIME happens at y=138.
# Wait. `startupBulkhead` was at `y=473`. That's between RUNTIME and SHUTDOWN! 
# Let's fix `startupBulkhead` to be at `y=130` (between STARTUP and RUNTIME).
content = content.replace('<line x1="60" y1="473" x2="1040" y2="473" stroke="#cbd5e0" stroke-width="4"',
                          '<line x1="60" y1="130" x2="1040" y2="130" stroke="#cbd5e0" stroke-width="4"')
content = content.replace('<text x="500" y="470" fill="#cbd5e0"', '<text x="500" y="127" fill="#cbd5e0"')

# Wait, if startupBulkhead is at y=130, then the attack on bind() which happens in RUNTIME should be blocked by something else.
# The redAttackSlide3 originates from the maze, goes to execve, but the line to bind() goes:
# 840,460 -> 900,460 -> 900,555 -> 960,610 (bind box).
# We can put a "Phase Guard Gate" at the kernel boundary for bind().
# Or, if BoB is the gate at the boundary...

# Let's redefine the slide layers entirely to match the user's explicit instructions:
# - Slide 2 (Whitelist): "BoB Whitelist Filter Bank" at y=580.
# - Slide 3 (Phase): Hover one, all must be highlighted. Same color.
# - Slide 4 (Stacktrace): Draw a glowing path for the expected stack.
# - Slide 5 (Scope): Scope gate.

# Let's just create a completely fresh set of layers and delete the old ones.
import os
os.system('git checkout presentation.html') # ensure we are clean

with open('presentation.html', 'r') as f:
    text = f.read()

# 1. Fix SHUTDOWN box to not overlap kernel traffic
text = text.replace('<rect x="60" y="490" width="980" height="50"', '<rect x="660" y="490" width="380" height="50"')
text = text.replace('<text x="70" y="505" fill="#8b949e"', '<text x="670" y="505" fill="#8b949e"')
text = text.replace('<text x="70" y="525" fill="#8b949e"', '<text x="670" y="525" fill="#8b949e"')
text = text.replace('<rect x="60" y="70" width="980" height="50"', '<rect x="60" y="70" width="400" height="50"')

# 2. Fix the original hardenedWalls to be the Profile + the Gate!
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
    <!-- The blue thread goes through x=370, which is in the read port -->
  </g>
"""
# Replace old hardenedWalls
text = re.sub(r'<g id="hardenedWalls".*?</g>', new_bob_whitelist, text, flags=re.DOTALL)

# Slide 2 Attack (execve) should crash into the Whitelist Gate (y=580)
text = text.replace('points="550,145 600,145 600,200 680,200 680,260 760,260 760,340 810,340 810,590"',
                    'points="550,145 600,145 600,200 680,200 680,260 760,260 760,340 810,340 810,580"')
text = text.replace('<rect x="785" y="595" width="50" height="55"', '<rect x="785" y="585" width="50" height="55"')
text = text.replace('<line x1="810" y1="590" x2="795" y2="572"', '<line x1="810" y1="580" x2="795" y2="562"')
text = text.replace('<line x1="810" y1="590" x2="825" y2="572"', '<line x1="810" y1="580" x2="825" y2="562"')
text = text.replace('<text x="840" y="600"', '<text x="840" y="590"')
text = text.replace('<rect x="830" y="555"', '<rect x="830" y="545"')
text = text.replace('<text x="840" y="574"', '<text x="840" y="564"')
text = text.replace('<line x1="810" y1="590" x2="795" y2="572" stroke="var(--attack-color)"', '<line x1="810" y1="580" x2="795" y2="562" stroke="var(--attack-color)"')
text = text.replace('<line x1="810" y1="590" x2="825" y2="572" stroke="var(--attack-color)"', '<line x1="810" y1="580" x2="825" y2="562" stroke="var(--attack-color)"')


# 3. Fix Phase Gate (Slide 3)
# Make startupBulkhead and shutdownGate use the same class and color.
text = text.replace('id="startupBulkhead"', 'id="startupBulkhead" class="slide-layer interactive phase-guard"')
text = text.replace('id="shutdownGate"', 'id="shutdownGate" class="slide-layer interactive phase-guard"')

# Let's add a "Phase Guard" block at the bind() port
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

# Slide 3 Attack crashes into Phase Guard at y=578
text = text.replace('points="550,145 600,145 600,240 680,240 680,320 760,320 760,380 840,380 840,460 900,460 900,555 960,610"',
                    'points="550,145 600,145 600,240 680,240 680,320 760,320 760,380 840,380 840,460 900,460 900,555 960,578"')
text = text.replace('<line x1="960" y1="608" x2="945" y2="590"', '<line x1="960" y1="578" x2="945" y2="560"')
text = text.replace('<line x1="960" y1="608" x2="975" y2="588"', '<line x1="960" y1="578" x2="975" y2="558"')

# Also, move startupBulkhead to y=130
text = text.replace('<line x1="60" y1="473" x2="1040" y2="473" stroke="#cbd5e0"', '<line x1="60" y1="130" x2="460" y2="130" stroke="#cbd5e0"')
text = text.replace('<text x="500" y="470"', '<text x="100" y="125"')


# 4. Slide 4 Stacktrace Visualization
# Draw a glowing green outline path around the expected stack
stack_viz = """
  <!-- Slide 4: Stacktrace Tunnel -->
  <g id="stacktraceTunnel" class="slide-layer interactive">
    <title>Enforced Call-Path Execution Tunnel</title>
    <!-- Glowing path around the blue thread -->
    <polyline points="410,145 410,210 470,210 470,300 410,300 410,420 370,420 370,475 370,610"
      fill="none" stroke="var(--success-color)" stroke-width="15" stroke-linecap="round" stroke-linejoin="round" opacity="0.15" filter="url(#glow-neon)"/>
    <text x="240" y="450" fill="var(--success-color)" font-size="10" font-weight="bold">Enforced Call-Path Tunnel</text>
  </g>
"""
text = text.replace('id="stacktraceEnforcement"', 'id="stacktraceEnforcement" class="slide-layer interactive" style="display:none"')
text = text.replace('<!-- ==================== ANIMATIONS ==================== -->', stack_viz + '\n<!-- ==================== ANIMATIONS ==================== -->')

# Slide 4 attack crashes because it's OUTSIDE the tunnel.
# Red attack diverges at 490,590. Crashes at y=610. Let's make it crash at y=610 (the syscall itself rejects it because it checks stack).
# Actually, the user liked the expected vs actual stack boxes. I will just enable `stacktraceEnforcement` again.
text = text.replace('style="display:none"', '')


# 5. Slide 5 Scope Visualization
# We already have `threadScope` layer with `SCOPE GATE`. Let's just make sure it's obvious.

# JS show arrays update
text = text.replace("'hardenedWalls'", "'bobWhitelist'")
text = text.replace("'startupBulkhead', 'shutdownGate'", "'startupBulkhead', 'shutdownGate', 'bobPhaseGuard'")
text = text.replace("'stacktraceEnforcement'", "'stacktraceEnforcement', 'stacktraceTunnel'")

with open('presentation.html', 'w') as f:
    f.write(text)

