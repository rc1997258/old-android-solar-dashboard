#!/usr/bin/env bash
# Runs the protocol, history and graph tests against a simulated Tapo P110.
# Needs: Java 8+ (javac), Python 3 with python-kasa  (pip install python-kasa)
set -euo pipefail
cd "$(dirname "$0")"
SRC=../src/com/diy/tapologger

python3 mock_p110.py 18080 'Me@Example.com' 'S3cret!' > mock_ok.log 2>&1 &
P1=$!
python3 mock_p110.py 18081 'Me@Example.com' 'S3cret!' forbid > mock_403.log 2>&1 &
P2=$!
trap 'kill $P1 $P2 2>/dev/null || true' EXIT
sleep 2

echo "== mock self-check with python-kasa's own client"
python3 py_client_check.py 18080 'Me@Example.com' 'S3cret!'

rm -rf out && mkdir out
javac -nowarn -d out $SRC/Klap.java $SRC/History.java $SRC/GraphData.java KlapTest.java HistoryTest.java GraphTest.java 2>&1 | grep -v '^Note' || true

status=0
for t in KlapTest HistoryTest GraphTest; do
  echo "== $t"
  java -cp out "$t" | tee "out/$t.txt"
  grep -q "ALL .* PASSED" "out/$t.txt" || status=1
done
[ $status -eq 0 ] && echo "ALL SUITES PASSED" || echo "SOME TESTS FAILED"
exit $status
