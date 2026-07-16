import re

with open("pr_78_v3.html", "r") as f:
    content = f.read()

# Look for Jules' replies
comments = re.findall(r'<div class="comment-body markdown-body js-comment-body soft-wrap user-select-contain d-block">(.*?)</div>', content, re.DOTALL)
for comment in comments:
    if "VERDICT:" in comment:
        print("--- REVIEW VERDICT FOUND ---")
        text = re.sub('<[^<]+?>', '', comment).strip()
        print(text)
