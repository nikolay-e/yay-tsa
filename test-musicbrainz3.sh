#!/bin/bash

# Search for specific CUPSIZE tracks
echo "=== Search for 'прыгай' by CUPSIZE ==="
curl -s "https://musicbrainz.org/ws/2/recording?query=artist:%22CUPSIZE%22%20AND%20recording:прыгай&fmt=json&limit=10" \
  -H "User-Agent: Yay-Tsa/0.1.0" | python3 -m json.tool 2>/dev/null | grep -E '"title":|"name":|"count"' | head -20
