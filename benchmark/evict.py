#!/usr/bin/env python3
"""Drop every jar in the given directory from the Linux page cache.

posix_fadvise(POSIX_FADV_DONTNEED) only affects the named files, so the rest of the machine's
page cache is left alone. macOS has no equivalent, so the benchmark uses "sudo purge" there.
"""
import os
import sys

directory = sys.argv[1]
for name in sorted(os.listdir(directory)):
    if not name.endswith(".jar"):
        continue
    fd = os.open(os.path.join(directory, name), os.O_RDONLY)
    try:
        os.posix_fadvise(fd, 0, os.fstat(fd).st_size, os.POSIX_FADV_DONTNEED)
    finally:
        os.close(fd)
