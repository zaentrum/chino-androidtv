#!/usr/bin/env bash
# Neutrality guard: this is a PUBLIC, vendor-neutral client. The tree must
# not carry an internal hostname or another media product's name, nor
# acquisition vocabulary in what users read (the Android resources and the
# docs). CI's neutrality job runs this before the build; it needs ripgrep.
#
# The patterns are put together from fragments so that the banned words never
# stand whole in the tree, this file (which the scan reads too) included.
#
# A name counts where what stands on either side of it is not a letter or a
# digit. \b and rg -w do not do: they count "_" as part of a word, so a name
# in an environment variable or a constant joined by "_" went through
# unseen. A letter or a digit on either side still keeps a name out of
# longer words: the product names are not in "complex" or "multiplex". (A
# name run into camelCase is not caught either; the guard trades that for no
# false positives.) zaentrum-operator's scripts/check-neutrality.sh draws the
# line the same way.
#
# Before it scans, the guard checks its patterns against lines they must and
# must not flag; a pattern that has gone blind fails the run.
#
# Run: scripts/check-neutrality.sh              (self-test, then the scan)
#      scripts/check-neutrality.sh --self-test  (the patterns only)
set -uo pipefail

# Without ripgrep every pattern would look blind to the self-test below, and
# its message would send people looking in the wrong place: say so first.
command -v rg >/dev/null 2>&1 || {
  echo "neutrality guard: needs ripgrep (rg) on PATH" >&2
  exit 2
}

# ── the patterns ─────────────────────────────────────────────────────────────
word() { printf '(^|[^[:alnum:]])(%s)([^[:alnum:]]|$)' "$1"; }
upper() { printf '%s' "$1" | tr '[:lower:]' '[:upper:]'; }

tld='cloud'
host_re="nalet\\.${tld}"
# Other media products, and the internal one's name.
p1="jelly""fin" p2="p""lex" p3="em""by" p4="k""odi" p5="nalet""flix"
names_re=$(word "$p1|$p2|$p3|$p4|$p5")
# Acquisition vocabulary.
v1="index""er" v2="trac""ker" v3="use""net" v4="a""rr" v5="tor""rent"
vocab_re=$(word "$v1|$v2|$v3|$v4|$v5")

# ── self-test ────────────────────────────────────────────────────────────────
self_test() {
  local bad=0
  expect() { # expect <name> <pattern> <hit|miss> <line>
    local got=miss
    printf '%s\n' "$4" | rg -q -i -e "$2" && got=hit
    if [[ "$got" != "$3" ]]; then
      echo "SELF-TEST FAIL: the $1 pattern should $([[ $3 == hit ]] && echo flag || echo pass) this line: $4"
      bad=1
    fi
  }
  # Joined by "_": the names \b let through.
  expect names "$names_re" hit "$(upper "$p2")_TOKEN=x"
  expect names "$names_re" hit "        - name: $(upper "$p1")_API_URL"
  expect names "$names_re" hit "val ${p3}_client = connect()"
  expect names "$names_re" hit "${p4}_remote"
  expect names "$names_re" hit "MY_$(upper "$p5")"
  # The forms \b caught, still caught.
  expect names "$names_re" hit "the ${p2} client"
  expect names "$names_re" hit "url: http://${p1}:8096"
  expect names "$names_re" hit "$(upper "$p3")"
  expect names "$names_re" hit "${p5}.example.org"
  # Longer words are other words.
  expect names "$names_re" miss 'complex'
  expect names "$names_re" miss 'multiplex'
  expect names "$names_re" miss 'COMPLEX_QUERY=1'
  expect names "$names_re" miss 'perplexed'
  expect names "$names_re" miss 'kodiak'
  expect vocab "$vocab_re" hit "$(upper "$v5")_DIR=/data"
  expect vocab "$vocab_re" hit "${v1}_url: http://x"
  expect vocab "$vocab_re" hit "the *${v4} apps"
  expect vocab "$vocab_re" hit "$(upper "$v4")_API_KEY"
  expect vocab "$vocab_re" hit "${v3}-server"
  expect vocab "$vocab_re" hit "<string name=\"x\">Your ${v2}</string>"
  expect vocab "$vocab_re" miss "${v5}ial"
  expect vocab "$vocab_re" miss "re${v1}s"
  expect vocab "$vocab_re" miss 'an array of carriers'
  expect host "$host_re" hit "https://sso.nalet.${tld}/realms/x"
  expect host "$host_re" hit "API_BASE_URL=https://API.NALET.$(upper "$tld")"
  expect host "$host_re" miss 'https://media.example.org'
  return $bad
}

if ! self_test; then
  echo "neutrality guard: its own patterns are wrong; fix them before trusting a scan"
  exit 2
fi
if [[ "${1:-}" == "--self-test" ]]; then
  echo "neutrality guard: self-test passed"
  exit 0
fi

# ── the scan ─────────────────────────────────────────────────────────────────
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 2
fail=0

# scan <what> <pattern> <path…>: rg's 0 is a find, 1 a clean tree, and
# anything else (rg missing, a path gone) a failed check, not a pass.
scan() {
  local what=$1 re=$2 status
  shift 2
  rg -n -i --hidden -g '!.git/**' -e "$re" "$@"
  status=$?
  case $status in
    0) echo "::error::$what found above. This is a public repo." >&2; fail=1 ;;
    1) echo "OK: no $what." ;;
    *) echo "::error::the scan for $what failed (rg exit $status)." >&2; fail=1 ;;
  esac
}

scan "internal hostname" "$host_re" .
scan "competitor product name" "$names_re" .
# User-facing surfaces only: the Android resources and the docs.
shopt -s nullglob
docs=(*.md)
# (The +-form: bash 3.2, macOS's, calls an empty array unbound under set -u.)
scan "acquisition vocabulary in user-facing text" "$vocab_re" app/src/main/res ${docs[@]+"${docs[@]}"}

exit $fail
