#!/usr/bin/env python3
import re

with open('presentation/presentation.html', 'r') as f:
    html = f.read()

# 1. Adjust Shutdown Zone box (y=490, height=40 instead of 50)
html = re.sub(
    r'<rect x="50" y="490" width="1000" height="50" rx="6" fill="#151b29" stroke="var\(--border-color\)" stroke-width="1\.5"/>\s*<text x="70" y="512" fill="#6c63ff" font-size="10" font-weight="bold">SHUTDOWN PHASE</text>\s*<text x="70" y="528" fill="var\(--text-secondary\)" font-size="9">connection drain · flush logs · deregister · release locks</text>',
    '<rect x="50" y="490" width="1000" height="40" rx="6" fill="#151b29" stroke="var(--border-color)" stroke-width="1.5"/>\n    <text x="70" y="508" fill="#6c63ff" font-size="10" font-weight="bold">SHUTDOWN PHASE</text>\n    <text x="70" y="522" fill="var(--text-secondary)" font-size="9">connection drain · flush logs · deregister · release locks</text>',
    html
)

# 2. Adjust Kernel Space box (y=570, height=190 instead of y=560, height=200)
html = re.sub(
    r'<rect x="50" y="560" width="1000" height="200" rx="6" fill="#0f2038" stroke="var\(--border-color\)" stroke-width="2"/>\s*<text x="70" y="583" fill="var\(--text-secondary\)" font-size="11" font-weight="bold">KERNEL-SPACE \(Linux OS \+ eBPF\)</text>',
    '<rect x="50" y="570" width="1000" height="190" rx="6" fill="#0f2038" stroke="var(--border-color)" stroke-width="2"/>\n  <text x="70" y="593" fill="var(--text-secondary)" font-size="11" font-weight="bold">KERNEL-SPACE (Linux OS + eBPF)</text>',
    html
)

# 3. Update the lines to be in the gap (y=540, 548, 556, 564)
lines_replacement = '''
  <!-- ═══════════════════════════════════════════════════════════════════ -->
  <!-- ENFORCEMENT LINES (Boundary Zone)                                   -->
  <!-- ═══════════════════════════════════════════════════════════════════ -->

  <!-- LINE 1: BoB Whitelist (Teal) -->
  <g id="lineWhitelist_active" class="slide-layer interactive">
    <line x1="50" y1="564" x2="1050" y2="564" stroke="var(--accent)" stroke-width="3" stroke-dasharray="12,5" filter="url(#glow-teal)"/>
    <text x="65" y="560" fill="var(--accent)" font-size="9" font-weight="bold">BoB Whitelist</text>
  </g>
  <g id="lineWhitelist_dim" class="slide-layer">
    <line x1="50" y1="564" x2="1050" y2="564" stroke="#4a5568" stroke-width="2" stroke-dasharray="12,5" opacity="0.4"/>
    <text x="65" y="560" fill="#4a5568" font-size="8" opacity="0.6">BoB Whitelist</text>
  </g>

  <!-- LINE 2: Lifecycle Phase Lock (Grey) -->
  <g id="linePhase_active" class="slide-layer interactive">
    <line x1="50" y1="556" x2="1050" y2="556" stroke="#e2e8f0" stroke-width="3" stroke-dasharray="12,5" filter="url(#glow-blue)"/>
    <text x="65" y="552" fill="#e2e8f0" font-size="9" font-weight="bold">Phase Lock</text>
  </g>
  <g id="linePhase_dim" class="slide-layer">
    <line x1="50" y1="556" x2="1050" y2="556" stroke="#4a5568" stroke-width="2" stroke-dasharray="12,5" opacity="0.4"/>
    <text x="65" y="552" fill="#4a5568" font-size="8" opacity="0.6">Phase Lock</text>
  </g>

  <!-- LINE 3: Stacktrace (Green) -->
  <g id="lineStack_active" class="slide-layer interactive">
    <line x1="50" y1="548" x2="1050" y2="548" stroke="var(--success-color)" stroke-width="3" stroke-dasharray="12,5" filter="url(#glow-neon)"/>
    <text x="65" y="544" fill="var(--success-color)" font-size="9" font-weight="bold">Stack Gate</text>
  </g>
  <g id="lineStack_dim" class="slide-layer">
    <line x1="50" y1="548" x2="1050" y2="548" stroke="#4a5568" stroke-width="2" stroke-dasharray="12,5" opacity="0.4"/>
    <text x="65" y="544" fill="#4a5568" font-size="8" opacity="0.6">Stack Gate</text>
  </g>

  <!-- LINE 4: Thread Scope (Orange) -->
  <g id="lineScope_active" class="slide-layer interactive">
    <line x1="50" y1="540" x2="1050" y2="540" stroke="var(--warning-color)" stroke-width="3" stroke-dasharray="12,5" filter="url(#glow-orange)"/>
    <text x="65" y="536" fill="var(--warning-color)" font-size="9" font-weight="bold">Scope Gate</text>
  </g>
  <g id="lineScope_dim" class="slide-layer">
    <line x1="50" y1="540" x2="1050" y2="540" stroke="#4a5568" stroke-width="2" stroke-dasharray="12,5" opacity="0.4"/>
    <text x="65" y="536" fill="#4a5568" font-size="8" opacity="0.6">Scope Gate</text>
  </g>
'''

html = re.sub(r'  <!-- ═══════════════════════════════════════════════════════════════════ -->.*?<!-- Syscall Boxes -->', lines_replacement + '\n  <!-- Syscall Boxes -->', html, flags=re.DOTALL)

# 4. Move Syscall Boxes down by 10px (from y=610 to y=620, text from 634 to 644)
html = html.replace('y="610" width="110"', 'y="620" width="110"')
html = html.replace('y="610" width="100"', 'y="620" width="100"')
html = html.replace('y="610" width="120"', 'y="620" width="120"')
html = html.replace('y="634"', 'y="644"')

# 5. Move Badges up to y=540
# badgeExecveWhitelist
html = re.sub(r'<rect x="750" y="562"', '<rect x="750" y="540"', html)
html = re.sub(r'<text x="811" y="578"', '<text x="811" y="556"', html)
html = re.sub(r'<text x="811" y="591"', '<text x="811" y="569"', html)
html = re.sub(r'<text x="811" y="602"', '<text x="811" y="580"', html)

# badgeMprotectPhase
html = re.sub(r'<rect x="900" y="562"', '<rect x="900" y="540"', html)
html = re.sub(r'<text x="961" y="578"', '<text x="961" y="556"', html)
html = re.sub(r'<text x="961" y="591"', '<text x="961" y="569"', html)
html = re.sub(r'<text x="961" y="602"', '<text x="961" y="580"', html)

# badgeReadStack
html = re.sub(r'<rect x="355" y="562"', '<rect x="355" y="540"', html)
html = re.sub(r'<text x="410" y="578"', '<text x="410" y="556"', html)
html = re.sub(r'<text x="410" y="591"', '<text x="410" y="569"', html)
html = re.sub(r'<text x="410" y="602"', '<text x="410" y="580"', html)

# badgeReadScope
html = re.sub(r'<rect x="340" y="562"', '<rect x="340" y="540"', html)
html = re.sub(r'<text x="410" y="578" fill="#0d1117" font-size="9" font-weight="bold" text-anchor="middle">⊘ SCOPE DENIED</text>', '<text x="410" y="556" fill="#0d1117" font-size="9" font-weight="bold" text-anchor="middle">⊘ SCOPE DENIED</text>', html)
html = re.sub(r'<text x="410" y="591" fill="#0d1117" font-size="7" text-anchor="middle">/etc/passwd ∉ /public/</text>', '<text x="410" y="569" fill="#0d1117" font-size="7" text-anchor="middle">/etc/passwd ∉ /public/</text>', html)
html = re.sub(r'<text x="410" y="602" fill="#0d1117" font-size="7" text-anchor="middle">eBPF LSM thread-ctx map</text>', '<text x="410" y="580" fill="#0d1117" font-size="7" text-anchor="middle">eBPF LSM thread-ctx map</text>', html)

# 6. Adjust Attack Paths to crash at y=540 instead of y=562
# Blue thread (legit) -> reaches y=620 now instead of 610
html = html.replace('370,555 370,610', '370,540 370,620')
html = html.replace('370,610 365,600 375,600', '370,620 365,610 375,610')
html = html.replace('x="380" y="595"', 'x="380" y="605"')

# Slide 1 Red Attack -> execve SUCCESS (y=620)
html = html.replace('810,580', '810,540 810,620')
html = html.replace('810,610 805,598 815,598', '810,620 805,608 815,608')
html = html.replace('cx="810" cy="590"', 'cx="810" cy="600"')
html = html.replace('y="655" fill="var(--attack-color)"', 'y="665" fill="var(--attack-color)"')

# Slide 2 Red Attack -> crashes at y=540
html = html.replace('810,340 810,562', '810,340 810,540')

# Slide 3 Red Attack -> crashes at y=540 (x=961)
html = html.replace('840,460 961,460 961,562', '840,460 961,460 961,540')
html = html.replace('x1="961" y1="562" x2="946" y2="544"', 'x1="961" y1="540" x2="946" y2="522"')
html = html.replace('x1="961" y1="562" x2="976" y2="544"', 'x1="961" y1="540" x2="976" y2="522"')
html = html.replace('x="870" y="540"', 'x="870" y="518"')
html = html.replace('x="960" y="555"', 'x="960" y="533"')

# Slide 4 Red Attack -> crashes at y=540 (x=410)
html = html.replace('550,540 480,560 410,562', '550,520 480,530 410,540')
html = html.replace('x1="410" y1="562" x2="395" y2="544"', 'x1="410" y1="540" x2="395" y2="522"')
html = html.replace('x1="410" y1="562" x2="425" y2="544"', 'x1="410" y1="540" x2="425" y2="522"')
html = html.replace('x="450" y="542"', 'x="450" y="520"')

# Slide 4 Valid Green Stacktrace -> reaches y=620
html = html.replace('370,475 370,555 370,610', '370,475 370,540 370,620')

# Slide 5 Trojan Thread -> crashes at y=540 (x=380)
html = html.replace('400,420 380,420 380,562', '400,420 380,420 380,540')
html = html.replace('x1="380" y1="562" x2="363" y2="544"', 'x1="380" y1="540" x2="363" y2="522"')
html = html.replace('x1="380" y1="562" x2="397" y2="544"', 'x1="380" y1="540" x2="397" y2="522"')
html = html.replace('x="415" y="540"', 'x="415" y="518"')

# Slide 5 Context map and valid path
html = html.replace('380,606 380,660', '380,584 380,660')
html = html.replace('380,606 380,650', '380,584 380,650')
html = html.replace('393" y="638"', '393" y="618"')

with open('presentation/presentation.html', 'w') as f:
    f.write(html)

print("Done: Adjusted layout so lines are OUTSIDE the kernel box, in the boundary gap.")
