import os
import re
import glob

rootDir = "/home/leanid/Documents/code/java/jseccomp"
docsDir = os.path.join(rootDir, "docs", "internals")
backlogDir = os.path.join(docsDir, "backlog")
target_map_file = os.path.join(docsDir, "architectural_map.md")

def parse_yaml_frontmatter(file_path):
    with open(file_path, "r", encoding="utf-8") as f:
        content = f.read()
    
    # Match YAML block at top of the file
    match = re.match(r"^---\s*\n(.*?)\n---\s*\n", content, re.DOTALL)
    if not match:
        return None
    
    yaml_text = match.group(1)
    metadata = {}
    
    # Simple key-value parser for simple YAML metadata
    for line in yaml_text.split("\n"):
        if ":" in line:
            key, val = line.split(":", 1)
            key = key.strip()
            val = val.strip().strip('"').strip("'")
            if val.startswith("[") and val.endswith("]"):
                # Parse list of strings
                items = [item.strip().strip('"').strip("'") for item in val[1:-1].split(",")]
                metadata[key] = [i for i in items if i]
            else:
                metadata[key] = val
    return metadata

def generate_mermaid():
    # Scan all design docs in internals directory
    design_docs = glob.glob(os.path.join(docsDir, "*.md"))
    # Scan all open backlog issues
    backlog_issues = glob.glob(os.path.join(backlogDir, "issue-*.md"))
    
    nodes = []
    edges = []
    clicks = []
    
    # 1. Process Design Docs
    for doc in design_docs:
        filename = os.path.basename(doc)
        if filename == "architectural_map.md" or filename == "README.md":
            continue
            
        meta = parse_yaml_frontmatter(doc)
        if not meta or "title" not in meta:
            continue
            
        doc_id = filename.replace(".md", "").replace("-", "_")
        nodes.append(f'    {doc_id}["📄 Design: {meta["title"]}"]')
        clicks.append(f'    click {doc_id} "{filename}"')
        
        # Link files/classes mapped to this design doc
        if "target_files" in meta:
            targets = meta["target_files"]
            if isinstance(targets, str):
                targets = [targets]
            for target in targets:
                # Get a clean class/file name
                clean_target = os.path.basename(target)
                target_id = clean_target.replace(".", "_").replace("-", "_")
                nodes.append(f'    {target_id}["💻 Source: {clean_target}"]')
                edges.append(f'    {target_id} -->|Governed by| {doc_id}')
                
    # 2. Process Open Backlog Issues
    for issue in backlog_issues:
        filename = os.path.basename(issue)
        meta = parse_yaml_frontmatter(issue)
        if not meta or "title" not in meta:
            continue
            
        # Only map open issues
        if meta.get("status") != "open":
            continue
            
        issue_id = filename.replace(".md", "").replace("-", "_")
        severity = meta.get("severity", "MEDIUM")
        nodes.append(f'    {issue_id}["🔴 Issue: {meta["title"]} (Severity: {severity})"]')
        clicks.append(f'    click {issue_id} "backlog/{filename}"')
        
        # Look for Target file definition in the issue content
        with open(issue, "r", encoding="utf-8") as f:
            issue_content = f.read()
        
        # Find Target Area metadata in content if not in YAML
        target_match = re.search(r'\*\*Target( Area)?:\*\*\s*`(.*?)`', issue_content)
        if target_match:
            target_path = target_match.group(2)
            clean_target = os.path.basename(target_path)
            target_id = clean_target.replace(".", "_").replace("-", "_")
            nodes.append(f'    {target_id}["💻 Source: {clean_target}"]')
            edges.append(f'    {issue_id} -->|Affects| {target_id}')

    # Deduplicate nodes
    nodes = list(set(nodes))
    
    # Construct Mermaid Code
    mermaid = ["```mermaid", "graph TD"]
    mermaid.extend(nodes)
    mermaid.extend(edges)
    mermaid.extend(clicks)
    mermaid.append("```")
    return "\n".join(mermaid)

def main():
    if not os.path.exists(target_map_file):
        print(f"Error: {target_map_file} not found.")
        return
        
    with open(target_map_file, "r", encoding="utf-8") as f:
        content = f.read()
        
    # Check for placeholder markers in architectural_map.md
    # If they don't exist, we will append the knowledge graph section at the bottom
    start_marker = "<!-- KNOWLEDGE_MAP_START -->"
    end_marker = "<!-- KNOWLEDGE_MAP_END -->"
    
    mermaid_diagram = generate_mermaid()
    
    if start_marker in content and end_marker in content:
        pattern = re.escape(start_marker) + r".*?" + re.escape(end_marker)
        replacement = f"{start_marker}\n\n{mermaid_diagram}\n\n{end_marker}"
        new_content = re.sub(pattern, replacement, content, flags=re.DOTALL)
    else:
        new_content = content + f"\n\n## 8. Dynamic Knowledge Map\n\n{start_marker}\n\n{mermaid_diagram}\n\n{end_marker}\n"
        
    with open(target_map_file, "w", encoding="utf-8") as f:
        f.write(new_content)
        
    print("Successfully generated and injected the Knowledge Map diagram.")

if __name__ == "__main__":
    main()
