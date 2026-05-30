#!/bin/sh

cd `dirname "$0"`

if ! zip --version | grep Info-ZIP >/dev/null; then
  echo >&2 `basename "$0"`: error: this only works with Info-ZIP.
  exit -1
fi

# Always do a FULL rebuild. The previous incremental mode (zip -u) only re-added files
# whose mtime was newer than the existing archive entry and never dropped deleted cards,
# so the cache could silently drift out of sync with the .txt sources (e.g. stale AI
# flags surviving in the zip while the loose files were edited). Deleting first guarantees
# the archive exactly matches the current card scripts.
rm -f cardsfolder.zip
echo Building cardsfolder.zip...

find . -name '*.txt' -print | zip -1@oX cardsfolder.zip
