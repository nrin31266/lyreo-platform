#!/usr/bin/env python3
"""Split a release for a size-limited transport without changing the archive bytes."""
import argparse
import json
from pathlib import Path
from fetch_utils import sha256_file


def split(archive: Path, destination: Path, size: int = 268435456):
    if not 1 <= size <= 536870912:
        raise ValueError('Part size must be within 512 MiB')
    destination.mkdir(parents=True, exist_ok=True)
    parts=[]
    with archive.open('rb') as source:
        for number in range(32):
            block=source.read(size)
            if not block: break
            target=destination/f'{archive.name}.part{number+1:02d}'
            with target.open('xb') as output: output.write(block)
            parts.append({'file':target.name,'url':target.resolve().as_uri(),'size_bytes':len(block),'sha256':sha256_file(target)})
        if source.read(1): raise ValueError('Archive needs more than 32 parts')
    index={'format':'lyreo.archive-parts.v1','archive_sha256':sha256_file(archive),'parts':parts}
    (destination/f'{archive.name}.parts.json').write_text(json.dumps(index,indent=2)+'\n')
    return index


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('archive',type=Path);p.add_argument('destination',type=Path)
    args=p.parse_args();print(json.dumps(split(args.archive,args.destination),indent=2))
