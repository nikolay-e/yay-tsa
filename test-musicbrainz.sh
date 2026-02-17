#!/bin/bash

# Test MusicBrainz API
curl -s "https://musicbrainz.org/ws/2/recording?query=artist:CUPSIZE%20AND%20recording:прыгай&fmt=json&limit=5" \
  -H "User-Agent: Yay-Tsa/0.1.0 (https://github.com/yay-tsa)"
