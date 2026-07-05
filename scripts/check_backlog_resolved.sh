#!/bin/bash
# scripts/check_backlog_resolved.sh
# Automated check for potentially resolved or stale backlog items in docs/internals/backlog/

BACKLOG_DIR="docs/internals/backlog"

if [ ! -d "$BACKLOG_DIR" ]; then
    echo "❌ Backlog directory not found: $BACKLOG_DIR"
    exit 1
fi

echo "🔍 Scanning backlog for potentially stale items..."
echo "--------------------------------------------------"

# Scan all issue markdown files
for file in "$BACKLOG_DIR"/issue-*.md; do
    [ -f "$file" ] || continue

    # Check if the issue is open (from frontmatter)
    if ! grep -q '^status: "open"' "$file"; then
        continue
    fi

    # Read the title line (starts with # 🔴)
    title_line=$(grep "^# 🔴" "$file" | head -n1)
    if [ -z "$title_line" ]; then
        continue
    fi

    # Extract the title text
    title=$(echo "$title_line" | sed 's/^# 🔴 //')

    # Extract the "Target" or "Target Area" if present in the file
    target=$(grep -iE "\*\*Target( Area)?:\*\*" "$file" | head -n1 | sed -E 's/.*\*\*Target( Area)?:\*\* (.*)/\2/')

    if [ -n "$target" ]; then
        # Clean up target string (remove backticks, trim)
        target_clean=$(echo "$target" | sed 's/`//g' | xargs)

        # Check if target refers to a file that exists
        first_target=$(echo "$target_clean" | awk '{print $1}' | sed 's/,//g')

        found_file=""
        if [ -f "$first_target" ]; then
            found_file="$first_target"
        else
            # Try to find the file by name if it's a class/partial path
            file_name=$(basename "$first_target" .kt)
            found_file=$(find . -name "${file_name}.kt" -o -name "${file_name}" 2>/dev/null | head -n1)
        fi

        if [ -n "$found_file" ]; then
             # Check git log for recent changes to this file
             last_change=$(git log -1 --format="%ar" -- "$found_file")
             change_count=$(git rev-list --count HEAD --since="7 days ago" -- "$found_file")

             if [ "$change_count" -gt 0 ]; then
                 echo "⚠️  POTENTIALLY STALE: $title"
                 echo "   - File: $file"
                 echo "   - Target: $found_file"
                 echo "   - Recent Changes: $change_count commit(s) in the last 7 days (Last: $last_change)"
                 echo "   - Action: Verify if this issue was addressed in recent commits."
                 echo ""
             fi
        else
             # If target is not a file, search for symbols in the codebase
             symbol=$(echo "$first_target" | sed 's/.*\.//')
             if [ ${#symbol} -gt 3 ]; then
                 matches=$(grep -r -l "$symbol" . --include="*.kt" --exclude-dir=build --exclude-dir=.gradle 2>/dev/null | head -n 5)
                 if [ -n "$matches" ]; then
                    stale=false
                    for f in $matches; do
                        c=$(git rev-list --count HEAD --since="7 days ago" -- "$f")
                        if [ "$c" -gt 0 ]; then
                            stale=true
                            break
                        fi
                    done

                    if [ "$stale" = true ]; then
                        echo "❓ UNCERTAIN: $title"
                        echo "   - File: $file"
                        echo "   - Symbol '$symbol' found in modified files."
                        echo "   - Action: Review symbol usage in recent changes."
                        echo ""
                     fi
                  fi
              fi
         fi
    fi
done

echo "--------------------------------------------------"
echo "✅ Scan complete. Please manually verify highlighted items."

