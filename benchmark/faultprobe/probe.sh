#!/usr/bin/env bash
# Runs FaultProbe in every scenario this OS supports, one JVM per case, each with a 180 s limit. Writes faults.md,
# the logs and any hs_err files to the output directory $1. $2 is the classpath of the classgraph-base and
# classgraph-vfs classes, for ReaderOps. Needs passwordless sudo on Linux and macOS, and an administrator on Windows.
set -u
OUT=$1
VFS_CP=$2
SRC=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$OUT/classes" "$OUT/logs" "$OUT/hs_err"
SEP=":"
if [ "$RUNNER_OS" = Windows ]; then SEP=";"; fi
# A path as java and the Windows tools want it
jp() {
    case "$1" in
    '\\'*) echo "$1" ;;
    *) if [ "$RUNNER_OS" = Windows ]; then cygpath -w "$1"; else echo "$1"; fi ;;
    esac
}

FEATURE=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p' | tr -d '\r')
javac --release 17 -d "$OUT/classes" "$SRC/Common.java" "$SRC/FaultProbe.java" || exit 1
MAPS="legacy"
HAVE_READER=""
if [ "$FEATURE" -ge 22 ]; then
    javac --release "$FEATURE" -cp "$OUT/classes" -d "$OUT/classes" "$SRC/Arena25.java" || exit 1
    MAPS="legacy arena"
fi
if [ "$FEATURE" -ge 25 ]; then
    javac --release "$FEATURE" -cp "$OUT/classes$SEP$VFS_CP" -d "$OUT/classes" "$SRC/ReaderOps.java" || exit 1
    HAVE_READER=1
fi
CP="$OUT/classes$SEP$VFS_CP"
limit() { perl -e 'alarm shift; exec @ARGV or die "exec: $!"' "$@"; }
create() { java -cp "$CP" FaultProbe --create "$(jp "$1")" 64; }

WORK=$HOME/cgfault
if [ "$RUNNER_OS" = Windows ]; then WORK=/c/cgfault; fi
mkdir -p "$WORK"

{
    echo "## Faults, $RUNNER_OS $(uname -m), JDK $(java -version 2>&1 | head -1 | tr -d '\r')"
    echo
    echo "map \"none\" is the control: the inducer on a file that is not mapped."
    echo
    echo "| scenario | map | kind | size | cold | hot |"
    echo "|---|---|---|---|---|---|"
} > "$OUT/faults.md"

# One case: prints the result of FaultProbe, run after before_$sc and followed by after_$sc
run_case() {
    local sc=$1 map=$2 kind=$3 size=$4 hot=$5
    local name="$sc-$map-$kind-$size-$hot"
    local d="$WORK/hs/$name"
    mkdir -p "$d"
    "before_$sc" >> "$OUT/logs/$sc-hooks.log" 2>&1
    limit 180 java -Xmx512m -XX:ErrorFile="$(jp "$d")/hs_err_pid%p.log" -cp "$CP" FaultProbe "$(jp "$FILE")" \
        "$map" "$kind" "$size" "$hot" "${INDUCE[@]}" > "$OUT/logs/$name.log" 2>&1
    local code=$?
    "after_$sc" >> "$OUT/logs/$sc-hooks.log" 2>&1
    local res
    res=$(sed -n 's/^RESULT //p' "$OUT/logs/$name.log" | head -1 | tr -d '\r')
    if [ -z "$res" ]; then
        local sig frame
        sig=$(sed -n 's/^# *\(SIG[A-Z]*\|EXCEPTION_[A-Z_]*\).*/\1/p' "$OUT/logs/$name.log" | head -1 | tr -d '\r')
        frame=$(grep -A1 '^# Problematic frame:' "$OUT/logs/$name.log" | tail -1 | sed 's/^# *//' | tr -d '\r')
        res="**crash** exit $code $sig $frame"
        for f in "$d"/hs_err_pid*.log; do [ -f "$f" ] && cp "$f" "$OUT/hs_err/$name-$(basename "$f")"; done
    fi
    echo "$res" | tr '|' '/' | cut -c1-240
}

# Every kind, or with "quick" only enough to show whether the inducer works on a mapped file
run_scenario() {
    local sc=$1 mode=$2 kinds sizes
    if [ -n "${ONLY:-}" ] && [ "$ONLY" != "$sc" ]; then return; fi
    echo "== $sc $(date +%T)" >&2
    "prepare_$sc" > "$OUT/logs/$sc-prepare.log" 2>&1
    if [ $? -ne 0 ]; then
        echo "| $sc | unavailable: $(tail -3 "$OUT/logs/$sc-prepare.log" | tr '\r\n|' '  /' | cut -c1-200) | | | | |" \
            >> "$OUT/faults.md"
        return
    fi
    echo "| $sc | none | | | $(run_case "$sc" none none 0 false) | |" >> "$OUT/faults.md"
    if [ "$mode" = quick ]; then
        kinds="bulk"
    else
        kinds="byte1 byte1F long1F bulk bulkF longLoop8BB segCopy readerRead readerLong channel"
    fi
    for map in $MAPS; do
        for kind in $kinds; do
            case $kind in segCopy*) [ "$map" = arena ] || continue ;; esac
            case $kind in reader*) [ "$map" = arena ] && [ -n "$HAVE_READER" ] || continue ;; esac
            case $kind in channel) [ "$map" = legacy ] || continue ;; esac
            case $kind in byte1*|long1*|readerLong) sizes="8" ;; channel) sizes="8192" ;; *) sizes="100 8192" ;; esac
            if [ "$mode" = quick ]; then sizes="8192"; fi
            for size in $sizes; do
                echo "| $sc | $map | $kind | $size | $(run_case "$sc" "$map" "$kind" "$size" false) | $(run_case "$sc" "$map" "$kind" "$size" true) |" \
                    >> "$OUT/faults.md"
            done
        done
    done
}

# ---- Truncation of a local file through another handle (every OS)

prepare_local_setLength() { mkdir -p "$WORK/local" && FILE=$WORK/local/f.bin && INDUCE=(setLength); }
before_local_setLength() { create "$FILE"; }
after_local_setLength() { :; }

# ---- Windows: the other ways of truncating a mapped local file

prepare_local_channelTruncate() { mkdir -p "$WORK/local" && FILE=$WORK/local/f.bin && INDUCE=(channelTruncate); }
before_local_channelTruncate() { create "$FILE"; }
after_local_channelTruncate() { :; }

prepare_local_fsutil() {
    mkdir -p "$WORK/local" && FILE=$WORK/local/f.bin && INDUCE=(cmd fsutil file seteof "$(jp "$FILE")" 4096)
}
before_local_fsutil() { create "$FILE"; }
after_local_fsutil() { :; }

prepare_local_dotnet() {
    mkdir -p "$WORK/local" && FILE=$WORK/local/f.bin && INDUCE=(cmd powershell -NoProfile -Command
        "\$f = [IO.File]::Open('$(jp "$FILE")', 'Open', 'Write', 'ReadWrite, Delete'); \$f.SetLength(4096); \$f.Close()")
}
before_local_dotnet() { create "$FILE"; }
after_local_dotnet() { :; }

# ---- Linux: a block device that starts failing every read (the device-mapper "error" target)

prepare_dm_error() {
    truncate -s 300M "$WORK/dm.img" &&
        DM_LOOP=$(sudo losetup -f --show "$WORK/dm.img") &&
        DM_SECTORS=$(sudo blockdev --getsz "$DM_LOOP") &&
        sudo dmsetup create cgfault --table "0 $DM_SECTORS linear $DM_LOOP 0" &&
        sudo mkfs.ext4 -q -F /dev/mapper/cgfault &&
        sudo mkdir -p /mnt/cgfault &&
        sudo mount /dev/mapper/cgfault /mnt/cgfault &&
        sudo chmod 777 /mnt/cgfault &&
        create /mnt/cgfault/f.bin &&
        sudo mount -o remount,ro /mnt/cgfault || return 1
    cat > "$WORK/dm.sh" <<EOF
set -e
if [ "\$1" = error ]; then table="0 $DM_SECTORS error"; else table="0 $DM_SECTORS linear $DM_LOOP 0"; fi
dmsetup suspend cgfault
dmsetup load cgfault --table "\$table"
dmsetup resume cgfault
echo 1 > /proc/sys/vm/drop_caches
EOF
    FILE=/mnt/cgfault/f.bin
    INDUCE=(cmd sudo bash "$WORK/dm.sh" error)
}
before_dm_error() { sudo bash "$WORK/dm.sh" linear; }
after_dm_error() { sudo bash "$WORK/dm.sh" linear; }

# ---- NFS over loopback, truncated on the server, or with the server stopped (a soft mount, so reads fail)

prepare_linux_nfs() {
    if ! mountpoint -q /mnt/cgnfs; then
        sudo apt-get update -qq &&
            sudo apt-get install -y -qq nfs-kernel-server &&
            sudo mkdir -p /srv/cgnfs /mnt/cgnfs &&
            sudo chmod 777 /srv/cgnfs &&
            echo "/srv/cgnfs 127.0.0.1(rw,sync,no_subtree_check,no_root_squash,insecure)" | sudo tee /etc/exports &&
            sudo systemctl restart nfs-kernel-server &&
            sudo exportfs -ra &&
            sudo mount -t nfs -o vers=3,soft,timeo=20,retrans=1,noac 127.0.0.1:/srv/cgnfs /mnt/cgnfs || return 1
    fi
    FILE=/mnt/cgnfs/f.bin
}
prepare_mac_nfs() {
    if ! mount | grep -q " on $WORK/nfsmnt "; then
        sudo mkdir -p /private/tmp/cgnfs &&
            sudo chmod 777 /private/tmp/cgnfs &&
            echo "/private/tmp/cgnfs -maproot=root localhost" | sudo tee /etc/exports &&
            { sudo nfsd enable || true; } &&
            sudo nfsd restart &&
            sleep 3 &&
            sudo nfsd checkexports &&
            showmount -e localhost &&
            mkdir -p "$WORK/nfsmnt" &&
            sudo mount -t nfs -o vers=3,soft,timeo=20,retrans=1,resvport localhost:/private/tmp/cgnfs "$WORK/nfsmnt" ||
            return 1
    fi
    FILE=$WORK/nfsmnt/f.bin
}
prepare_nfs() { if [ "$RUNNER_OS" = Linux ]; then prepare_linux_nfs; else prepare_mac_nfs; fi; }
nfs_server() {
    if [ "$RUNNER_OS" = Linux ]; then sudo systemctl "$1" nfs-kernel-server; else sudo nfsd "$1"; fi
    sleep 1
}
drop_caches() {
    if [ "$RUNNER_OS" = Linux ]; then sudo sh -c "echo 1 > /proc/sys/vm/drop_caches"; else sudo purge; fi
}

prepare_nfs_truncate() { prepare_nfs && INDUCE=(setLength "$NFS_EXPORT/f.bin"); }
before_nfs_truncate() { create "$NFS_EXPORT/f.bin"; drop_caches; }
after_nfs_truncate() { :; }

prepare_nfs_down() {
    prepare_nfs || return 1
    if [ "$RUNNER_OS" = Linux ]; then
        INDUCE=(cmd sudo sh -c "systemctl stop nfs-kernel-server && echo 1 > /proc/sys/vm/drop_caches")
    else
        INDUCE=(cmd sudo sh -c "nfsd stop && purge")
    fi
}
before_nfs_down() { nfs_server start; create "$NFS_EXPORT/f.bin"; drop_caches; }
after_nfs_down() { nfs_server start; }

# ---- macOS: a disk image detached by force while the file is mapped

prepare_dmg_detach() {
    hdiutil create -size 300m -fs HFS+ -volname cgfault "$WORK/cg.dmg" &&
        mkdir -p "$WORK/dmgmnt" &&
        hdiutil attach -nobrowse -mountpoint "$WORK/dmgmnt" "$WORK/cg.dmg" &&
        create "$WORK/dmgmnt/f.bin" &&
        hdiutil detach "$WORK/dmgmnt" || return 1
    FILE=$WORK/dmgmnt/f.bin
    INDUCE=(cmd hdiutil detach -force "$WORK/dmgmnt")
}
before_dmg_detach() { hdiutil attach -readonly -nobrowse -mountpoint "$WORK/dmgmnt" "$WORK/cg.dmg"; }
after_dmg_detach() { hdiutil detach -force "$WORK/dmgmnt" || true; }

# ---- Windows: a virtual disk detached while the file is mapped

prepare_vhd_detach() {
    local vhd
    vhd=$(jp "$WORK/cg.vhdx")
    printf 'create vdisk file="%s" maximum=300 type=expandable\nselect vdisk file="%s"\nattach vdisk\ncreate partition primary\nformat fs=ntfs quick label=cgfault\nassign letter=Q\n' \
        "$vhd" "$vhd" > "$WORK/create.txt"
    printf 'select vdisk file="%s"\nattach vdisk readonly\n' "$vhd" > "$WORK/attach.txt"
    printf 'select vdisk file="%s"\ndetach vdisk\n' "$vhd" > "$WORK/detach.txt"
    diskpart /s "$(jp "$WORK/create.txt")" &&
        create /q/f.bin &&
        diskpart /s "$(jp "$WORK/detach.txt")" || return 1
    FILE=/q/f.bin
    INDUCE=(cmd diskpart /s "$(jp "$WORK/detach.txt")")
}
before_vhd_detach() { diskpart /s "$(jp "$WORK/attach.txt")"; ls -l /q/f.bin; }
after_vhd_detach() { diskpart /s "$(jp "$WORK/detach.txt")" || true; }

# ---- Windows: SMB over loopback, truncated on the server, or with the server stopped

prepare_smb() {
    mkdir -p /c/cgsmb &&
        powershell -NoProfile -Command "if (-not (Get-SmbShare -Name cgsmb -ErrorAction SilentlyContinue)) { New-SmbShare -Name cgsmb -Path C:\\cgsmb -FullAccess Everyone }" &&
        powershell -NoProfile -Command "if (-not (Test-Path '\\\\localhost\\cgsmb')) { exit 1 }" || return 1
    FILE='\\localhost\cgsmb\f.bin'
}
smb_server_start() {
    powershell -NoProfile -Command "Start-Service LanmanServer; for (\$i = 0; \$i -lt 30 -and -not (Test-Path '\\\\localhost\\cgsmb')) { Start-Sleep 1 }"
}
prepare_smb_truncate() { prepare_smb && INDUCE=(setLength 'C:\cgsmb\f.bin'); }
before_smb_truncate() { create /c/cgsmb/f.bin; }
after_smb_truncate() { :; }

prepare_smb_down() {
    prepare_smb && INDUCE=(cmd powershell -NoProfile -Command "Stop-Service LanmanServer -Force")
}
before_smb_down() { smb_server_start; create /c/cgsmb/f.bin; }
after_smb_down() { smb_server_start; }

case "$RUNNER_OS" in
Linux)
    run_scenario local_setLength full
    run_scenario dm_error full
    NFS_EXPORT=/srv/cgnfs
    run_scenario nfs_truncate full
    run_scenario nfs_down full
    ;;
macOS)
    run_scenario local_setLength full
    run_scenario dmg_detach full
    NFS_EXPORT=/private/tmp/cgnfs
    run_scenario nfs_truncate full
    run_scenario nfs_down full
    ;;
Windows)
    run_scenario local_setLength quick
    run_scenario local_channelTruncate quick
    run_scenario local_fsutil quick
    run_scenario local_dotnet quick
    run_scenario smb_truncate full
    run_scenario smb_down full
    run_scenario vhd_detach full
    ;;
esac
cat "$OUT/faults.md"
