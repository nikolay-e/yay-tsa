#!/bin/bash

# Test with exact search
echo "=== Search 1: Latin artist name ==="
curl -s "https://musicbrainz.org/ws/2/recording?query=artist:CUPSIZE&fmt=json&limit=3" \
  -H "User-Agent: Yay-Tsa/0.1.0" | head -c 500

echo -e "\n\n=== Search 2: Cyrillic title ==="
curl -s "https://musicbrainz.org/ws/2/recording?query=recording:%D0%BF%D1%80%D1%8B%D0%B3%D0%B0%D0%B9%20%D0%B4%D1%83%D1%80%D0%B0&fmt=json&limit=3" \
  -H "User-Agent: Yay-Tsa/0.1.0" | head -c 500

echo -e "\n\n=== Search 3: Combined ==="
curl -s "https://musicbrainz.org/ws/2/recording?query=artist:CUPSIZE%20recording:прыгай%20дура&fmt=json&limit=3" \
  -H "User-Agent: Yay-Tsa/0.1.0" | head -c 500
