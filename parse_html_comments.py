import re
from html import unescape

with open("pr_78_v3.html", "r") as f:
    content = f.read()

comments = re.findall(r'<div class="comment-body markdown-body js-comment-body soft-wrap user-select-contain d-block">(.*?)</div>', content, re.DOTALL)
for i, comment in enumerate(comments):
    text = re.sub('<[^<]+?>', '', comment).strip()
    print(f"--- Comment {i+1} ---")
    print(unescape(text))
