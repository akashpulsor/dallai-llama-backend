#!/bin/bash

# Output file
OUTPUT_FILE="all-java-files.txt"

# Base directory (change if needed)
BASE_DIR="."

# Clear output file
: > "$OUTPUT_FILE"

# Find all .java files recursively, excluding common junk dirs
find "$BASE_DIR" \
  -type d \( -name target -o -name build -o -name .git \) -prune -o \
  -type f -name "*.java" -print | sort | while IFS= read -r file; do

  echo "==================================================" >> "$OUTPUT_FILE"
  echo "FILE: $file" >> "$OUTPUT_FILE"
  echo "==================================================" >> "$OUTPUT_FILE"
  echo "" >> "$OUTPUT_FILE"

  cat "$file" >> "$OUTPUT_FILE"

  echo -e "\n\n" >> "$OUTPUT_FILE"
done

echo "✅ All Java files (including nested directories) saved to $OUTPUT_FILE"

