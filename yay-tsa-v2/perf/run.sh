#!/usr/bin/env bash
# Reproducible /Items query perf harness.
#
# Bootstraps a throwaway PostgreSQL 16 cluster, applies the real library + preferences migrations
# (including the perf indexes), seeds at 1k/5k/20k entities, and prints EXPLAIN ANALYZE plans +
# timings for the Albums / Songs / Favorites / Search paths, plus the favorites load-all vs
# SQL-OFFSET threshold sweep. Requires postgresql-16 + the pg_trgm contrib (no Docker).
#
# Usage:  bash perf/run.sh          (run from yay-tsa-v2/)
#
# Numbers are server-side execution time on a warm cache with fsync=off — they reflect plan shape
# and relative cost, not absolute production latency (no network / JVM mapping included).
set -euo pipefail

PGBIN=${PGBIN:-/usr/lib/postgresql/16/bin}
ACC=${PG_RUN_AS:-postgres} # unprivileged account to own the server (PG refuses root)
DIR=$(mktemp -d /tmp/yaytsa-perf.XXXX)
PORT=${PGPORT:-55432}
HERE=$(cd "$(dirname "$0")" && pwd)
MIG=$HERE/..

chown -R "$ACC" "$DIR"
cp "$HERE"/seed.sql "$HERE"/measure.sql "$DIR"/
cp "$MIG"/infra-persistence/library/src/main/resources/db/library/V001__library_schema.sql "$DIR"/lib1.sql
cp "$MIG"/infra-persistence/library/src/main/resources/db/library/V003__entities_created_at_index.sql "$DIR"/lib3.sql
cp "$MIG"/infra-persistence/preferences/src/main/resources/db/preferences/V001__preferences_schema.sql "$DIR"/pref1.sql
cp "$MIG"/infra-persistence/preferences/src/main/resources/db/preferences/V002__favorites_position_index.sql "$DIR"/pref2.sql
chown "$ACC" "$DIR"/*.sql

run_as() { su "$ACC" -c "PATH=$PGBIN:\$PATH $*"; }

run_as "initdb -D $DIR/data -U perf --auth=trust" >/dev/null
cat >>"$DIR/data/postgresql.conf" <<EOF
shared_buffers = 256MB
work_mem = 32MB
fsync = off
synchronous_commit = off
max_parallel_workers_per_gather = 0
unix_socket_directories = '$DIR'
port = $PORT
EOF
chown "$ACC" "$DIR/data/postgresql.conf"
run_as "pg_ctl -D $DIR/data -l $DIR/server.log start" >/dev/null
cleanup() {
  run_as "pg_ctl -D $DIR/data stop -m immediate" >/dev/null 2>&1 || true
  rm -rf "$DIR"
}
trap cleanup EXIT
sleep 1

PSQL="psql -h $DIR -p $PORT -U perf -d yaytsa_perf -v ON_ERROR_STOP=1"
run_as "createdb -h $DIR -p $PORT -U perf yaytsa_perf"
run_as "$PSQL -c 'CREATE EXTENSION IF NOT EXISTS pg_trgm;'" >/dev/null
for f in lib1 pref1 lib3 pref2; do run_as "$PSQL -f $DIR/$f.sql" >/dev/null; done

for spec in "1000 50 100 850 100" "5000 250 500 4250 500" "20000 1000 2000 17000 2000"; do
  # shellcheck disable=SC2086  # intentional word-splitting of the space-separated spec
  set -- $spec
  N=$1 art=$2 alb=$3 trk=$4 fav=$5
  run_as "$PSQL -v artists=$art -v albums=$alb -v tracks=$trk -v favs=$fav -f $DIR/seed.sql" >/dev/null
  echo "===== SCALE: $N entities (favs=$fav) ====="
  run_as "$PSQL -v albums=$alb -v favs=$fav -f $DIR/measure.sql" 2>&1 |
    awk '/^@@Q /{l=$2;g=0;next}
           l&&!g&&/(Seq Scan|Index Scan|Index Only|Bitmap Heap|Sort  \()/{o=$0;sub(/^ +/,"",o);sub(/  \(cost.*/,"",o);plan[l]=o;g=1}
           /Execution Time:/{if(l){printf "  %-44s %-38s %8s ms\n",l,substr(plan[l],1,38),$3;l=""}}'
done

et() { # $1 = SQL; prints top node + execution time on one line
  run_as "$PSQL -tA -c \"EXPLAIN (ANALYZE,TIMING) $1\"" 2>&1 |
    grep -E 'Seq Scan|Index Scan|Bitmap Heap|Sort  \(|Execution Time' | head -3 | tr '\n' '|'
  echo
}

echo "===== (A) INDEX ON vs OFF @ 20k entities / 2000 favorites ====="
echo "favorites load-all WITH idx_favorites_user_position:"
et "SELECT * FROM core_v2_preferences.favorites WHERE user_id='u1' ORDER BY position"
run_as "$PSQL -c 'DROP INDEX core_v2_preferences.idx_favorites_user_position'" >/dev/null
echo "favorites load-all WITHOUT index:"
et "SELECT * FROM core_v2_preferences.favorites WHERE user_id='u1' ORDER BY position"
run_as "$PSQL -c 'CREATE INDEX idx_favorites_user_position ON core_v2_preferences.favorites (user_id, position)'" >/dev/null
echo "recently-added (created_at DESC) WITH idx_entities_type_created_at:"
et "SELECT * FROM core_v2_library.entities WHERE entity_type='TRACK' ORDER BY created_at DESC LIMIT 50"
run_as "$PSQL -c 'DROP INDEX core_v2_library.idx_entities_type_created_at'" >/dev/null
echo "recently-added (created_at DESC) WITHOUT index:"
et "SELECT * FROM core_v2_library.entities WHERE entity_type='TRACK' ORDER BY created_at DESC LIMIT 50"
run_as "$PSQL -c 'CREATE INDEX idx_entities_type_created_at ON core_v2_library.entities (entity_type, created_at)'" >/dev/null

echo "===== (B) favorites load-all vs deep SQL-OFFSET sweep (both are O(N)) ====="
printf "  %-12s %-26s %-26s\n" favorites loadALL deepOFFSET_LIMIT50
for F in 5000 20000 50000 100000; do
  run_as "$PSQL -v artists=10 -v albums=50 -v tracks=$F -v favs=$F -f $DIR/seed.sql" >/dev/null
  la=$(run_as "$PSQL -tA -c \"EXPLAIN (ANALYZE,TIMING) SELECT * FROM core_v2_preferences.favorites WHERE user_id='u1' ORDER BY position\"" 2>&1 | grep -oE 'Execution Time: [0-9.]+ ms')
  sp=$(run_as "$PSQL -tA -c \"EXPLAIN (ANALYZE,TIMING) SELECT * FROM core_v2_preferences.favorites WHERE user_id='u1' ORDER BY position OFFSET $((F - 50)) LIMIT 50\"" 2>&1 | grep -oE 'Execution Time: [0-9.]+ ms')
  printf "  %-12s %-26s %-26s\n" "$F" "$la" "$sp"
done
