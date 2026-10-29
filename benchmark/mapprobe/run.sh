#!/usr/bin/env bash
# Compiles the probes, then runs the truncation matrix and the read benchmark. Writes trunc.md, bench.md and
# raw logs to the output directory given as $1. Every java run is in its own JVM, with a 180 s limit.
set -u
OUT=$1
SRC=$(dirname "$0")
mkdir -p "$OUT/classes" "$OUT/work" "$OUT/hs_err"
FEATURE=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')
javac --release 17 -d "$OUT/classes" "$SRC/Common.java" "$SRC/TruncMatrix.java" "$SRC/ReadBench.java" || exit 1
MAPS="legacy"
if [ "$FEATURE" -ge 22 ]; then
    javac --release "$FEATURE" -cp "$OUT/classes" -d "$OUT/classes" "$SRC/Arena25.java" || exit 1
    MAPS="legacy arena"
fi
limit() { perl -e 'alarm shift; exec @ARGV or die "exec: $!"' "$@"; }

{
    echo "## Truncation, $(uname -s) $(uname -m), JDK $(java -version 2>&1 | head -1)"
    echo
    echo "| map | kind | size | cold | hot |"
    echo "|---|---|---|---|---|"
} > "$OUT/trunc.md"
TRUNC_KINDS=${TRUNC_KINDS:-"byte1 long1 bulk bulkHeapBuf longLoop longLoop4 longLoopVH segCopy segLongLoop sizeCheckBulk channel raf heap"}
for map in $MAPS; do
    for kind in $TRUNC_KINDS; do
        case $kind in segCopy|segLongLoop) [ "$map" = arena ] || continue ;; esac
        case $kind in channel|raf|heap) [ "$map" = legacy ] || continue ;; esac
        case $kind in byte1|long1) sizes="8" ;; *) sizes="8 100 8192 tail" ;; esac
        for size in $sizes; do
            row="| $map | $kind | $size |"
            for hot in false true; do
                d="$OUT/work/$map-$kind-$size-$hot"
                mkdir -p "$d"
                log="$OUT/work/$map-$kind-$size-$hot.log"
                limit 180 java -Xmx512m -XX:ErrorFile="$d/hs_err_pid%p.log" -cp "$OUT/classes" TruncMatrix "$d" "$map" "$kind" "$size" "$hot" > "$log" 2>&1
                code=$?
                res=$(sed -n 's/^RESULT //p' "$log" | head -1)
                if [ -z "$res" ]; then
                    sig=$(sed -n 's/^# *\(SIG[A-Z]*\).*/\1/p' "$log" | head -1)
                    frame=$(grep -A1 '^# Problematic frame:' "$log" | tail -1 | sed 's/^# *//')
                    res="**crash** exit $code ${sig} ${frame}"
                    for f in "$d"/hs_err_pid*.log; do [ -f "$f" ] && cp "$f" "$OUT/hs_err/$map-$kind-$size-$hot-$(basename "$f")"; done
                fi
                res=$(echo "$res" | tr '|' '/' | cut -c1-220)
                row="$row $res |"
            done
            echo "$row" >> "$OUT/trunc.md"
        done
    done
done

{
    echo "## Random reads of a cached 128 MB file, $(uname -s) $(uname -m), JDK $(java -version 2>&1 | head -1), $(getconf _NPROCESSORS_ONLN 2>/dev/null || echo "$NUMBER_OF_PROCESSORS") cores"
    echo
    echo "ns per read per thread (lower is better); the median of 3 rounds"
    echo
} > "$OUT/bench.md"
BENCH_KINDS=${BENCH_KINDS:-"bulk longLoop longLoop4 longLoopVH segCopy segLongLoop sizeCheckBulk channel channelDirect channelPerThread raf heap"}
: > "$OUT/bench.raw"
for map in $MAPS; do
    for kind in $BENCH_KINDS; do
        case $kind in segCopy|segLongLoop) [ "$map" = arena ] || continue ;; esac
        case $kind in channel*|raf|heap) [ "$map" = legacy ] || continue ;; esac
        limit 900 java -Xmx1g -cp "$OUT/classes" ReadBench "$OUT/work" "$map" "$kind" > "$OUT/work/bench-$map-$kind.log" 2>&1 \
            || echo "FAILED $map $kind exit $?" >> "$OUT/bench.raw"
        grep -E '^(RESULT|LOAD) ' "$OUT/work/bench-$map-$kind.log" >> "$OUT/bench.raw"
    done
done
# One row per method, one column per size and thread count
perl -ne '
    if (/^RESULT (\S+) (\S+) (\d+) (\d+) (\S+) (\S+)/) {
        my $m = "$2 $1"; my $c = "$3 B x$4";
        push @m, $m unless $seen{$m}++; push @c, $c unless $seenc{$c}++; $v{$m}{$c} = $6;
    } elsif (/^LOAD (\S+) (.*)/) { $load = "heap: loading the file took $2"; }
    elsif (/^FAILED/) { push @fail, $_; }
    END {
        print "| method | ", join(" | ", @c), " |\n|---|", "---|" x @c, "\n";
        for my $m (@m) { print "| $m | ", join(" | ", map { $v{$m}{$_} // "" } @c), " |\n"; }
        print "\n$load\n" if $load; print "\n$_" for @fail;
    }' "$OUT/bench.raw" >> "$OUT/bench.md"
