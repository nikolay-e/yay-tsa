#!/bin/sh
set -e

MEDIA_DIR="${1:-/media}"
ARTIST="Test Artist"
ALBUM1="First Album"
ALBUM2="Second Album"

ALBUM1_DIR="$MEDIA_DIR/$ARTIST/$ALBUM1"
ALBUM2_DIR="$MEDIA_DIR/$ARTIST/$ALBUM2"

if [ -d "$ALBUM1_DIR" ] && [ "$(ls -A "$ALBUM1_DIR" 2>/dev/null)" ]; then
  echo "Test media already exists, skipping generation"
  exit 0
fi

mkdir -p "$ALBUM1_DIR" "$ALBUM2_DIR"

generate_track() {
  dir="$1"
  track_num="$2"
  title="$3"
  album="$4"
  freq="$5"
  duration="$6"
  ffmpeg -y -f lavfi -i "sine=frequency=${freq}:duration=${duration}" \
    -metadata title="$title" \
    -metadata artist="$ARTIST" \
    -metadata album="$album" \
    -metadata track="$track_num" \
    -metadata date="2024" \
    -metadata genre="Electronic" \
    -codec:a libmp3lame -b:a 128k \
    "$dir/$(printf '%02d' "$track_num") - $title.mp3" 2>/dev/null
}

generate_track "$ALBUM1_DIR" 1 "Morning Light" "$ALBUM1" 440 15
generate_track "$ALBUM1_DIR" 2 "Afternoon Drift" "$ALBUM1" 520 12
generate_track "$ALBUM1_DIR" 3 "Evening Glow" "$ALBUM1" 330 18
generate_track "$ALBUM1_DIR" 4 "Night Sky" "$ALBUM1" 660 10
generate_track "$ALBUM1_DIR" 5 "Dawn Chorus" "$ALBUM1" 550 14

generate_track "$ALBUM2_DIR" 1 "Ocean Waves" "$ALBUM2" 280 16
generate_track "$ALBUM2_DIR" 2 "Mountain Echo" "$ALBUM2" 392 11
generate_track "$ALBUM2_DIR" 3 "Forest Rain" "$ALBUM2" 470 13
generate_track "$ALBUM2_DIR" 4 "Desert Wind" "$ALBUM2" 350 17
generate_track "$ALBUM2_DIR" 5 "River Flow" "$ALBUM2" 415 15

echo "Generated 10 test tracks in $MEDIA_DIR"
