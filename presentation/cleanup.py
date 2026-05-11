#!/usr/bin/env python3
import re

with open('presentation/presentation.html', 'r') as f:
    html = f.read()

# Remove redundant crash lines and text from redAttackSlide3
html = re.sub(r'<line x1="961" y1="540" x2="946" y2="522" stroke="#ff6b6b" stroke-width="3" class="pulse-block"/>\s*<line x1="961" y1="540" x2="976" y2="522" stroke="#ff6b6b" stroke-width="3" class="pulse-block"/>\s*<!-- Denial label right at the gate, in kernel space -->\s*<rect x="870" y="518" width="185" height="22" rx="3" fill="#0f2038" stroke="var\(--attack-color\)" stroke-width="1\.5"/>\s*<text x="960" y="533" fill="var\(--attack-color\)" font-size="9" font-weight="bold" text-anchor="middle">mprotect\(\) ⊘ PHASE-DENIED</text>', '', html)

# Remove redundant crash lines and text from stacktraceEnforcement (Slide 4)
html = re.sub(r'<!-- Red attack crashes into read\(\) box -->\s*<line x1="410" y1="540" x2="395" y2="522" stroke="#ff6b6b" stroke-width="3" class="pulse-block"/>\s*<line x1="410" y1="540" x2="425" y2="522" stroke="#ff6b6b" stroke-width="3" class="pulse-block"/>\s*<text x="450" y="520" fill="var\(--attack-color\)" font-size="10" font-weight="bold">DENIED ✕</text>', '', html)

# Remove redundant crash lines and text from threadScope (Slide 5)
html = re.sub(r'<!-- Attack crashes INTO the scope gate -->\s*<line x1="380" y1="540" x2="363" y2="522" stroke="var\(--attack-color\)" stroke-width="3" class="pulse-block"/>\s*<line x1="380" y1="540" x2="397" y2="522" stroke="var\(--attack-color\)" stroke-width="3" class="pulse-block"/>\s*<text x="415" y="518" fill="var\(--attack-color\)" font-size="10" font-weight="bold">DENIED ✕</text>', '', html)

with open('presentation/presentation.html', 'w') as f:
    f.write(html)
print("Removed redundant DENIED texts and crash vectors")
