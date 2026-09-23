"""Builds app/src/main/assets/base_bigrams.txt from Norvig's count_2w.txt.

Usage:
    curl -sLO https://www.norvig.com/ngrams/count_2w.txt
    python tools/build_bigrams.py count_2w.txt app/src/main/assets/base_dictionary.txt app/src/main/assets/base_bigrams.txt

Output format, one line per PREVIOUS word, rows sorted by prev, entries
sorted by count descending:

    prev<TAB>next1 count1<TAB>next2 count2 ...

Counts are the source counts divided by 1000 (every source count is
>= 100000, so nothing rounds to zero) and clipped to fit an Int.
"""
import re
import sys
from collections import defaultdict

SRC = sys.argv[1]
DICT = sys.argv[2]
OUT = sys.argv[3]
MAX_PER_ROW = 400
MIN_ROW_TOTAL = 1  # keep every row that survives the vocabulary filter

# Mirrors FastEngine.CONTRACTION_FIXES (lowercased outputs) plus the corpus's
# other apostrophe-stripped spellings.
CONTRACTION_FIXES = {
    "dont": "don't", "doesnt": "doesn't", "didnt": "didn't",
    "cant": "can't", "couldnt": "couldn't", "wont": "won't",
    "wouldnt": "wouldn't", "shouldnt": "shouldn't", "isnt": "isn't",
    "arent": "aren't", "wasnt": "wasn't", "werent": "weren't",
    "hasnt": "hasn't", "havent": "haven't", "hadnt": "hadn't",
    "im": "i'm", "ive": "i've", "youre": "you're",
    "youve": "you've", "youll": "you'll", "youd": "you'd",
    "thats": "that's", "whats": "what's", "theres": "there's",
    "heres": "here's", "hes": "he's", "shes": "she's",
    "whos": "who's", "hows": "how's", "wheres": "where's",
    "whens": "when's", "theyre": "they're",
    "theyve": "they've", "theyll": "they'll", "theyd": "they'd",
    "weve": "we've", "yall": "y'all", "oclock": "o'clock",
    "cmon": "c'mon", "itll": "it'll", "thatll": "that'll",
    "couldve": "could've", "shouldve": "should've",
    "wouldve": "would've", "mightve": "might've", "mustve": "must've",
    "ill": None,   # ambiguous (ill / I'll): keep as the real word "ill"
    "id": None,    # ambiguous (id / I'd)
    "well": None,  # ambiguous (well / we'll)
    "were": None,  # ambiguous (were / we're)
    "lets": "let's", "its": None, "isnt": "isn't",
}

CONTRACTIONS = {
    "don't", "it's", "i'm", "that's", "can't", "didn't", "doesn't", "you're",
    "i'll", "won't", "isn't", "what's", "there's", "couldn't", "they're",
    "we're", "i've", "wasn't", "shouldn't", "wouldn't", "haven't", "you'll",
    "we'll", "he's", "she's", "let's", "who's", "aren't", "weren't", "hasn't",
    "hadn't", "you've", "we've", "they've", "could've", "should've",
    "would've", "might've", "must've", "i'd", "you'd", "he'd", "she'd",
    "we'd", "they'd", "it'd", "that'd", "there'd", "it'll", "that'll",
    "there'll", "he'll", "she'll", "they'll", "who'll", "how's", "where's",
    "when's", "why's", "here's", "who've", "what've", "who'd", "what'd",
    "how'd", "what'll", "everybody's", "someone's", "anyone's", "nobody's",
    "nothing's", "something's", "everything's", "one's", "o'clock", "y'all",
    "c'mon", "ma'am", "'cause", "'til", "'bout",
}

TEXTING = {
    "omw", "hbu", "wbu", "rn", "af", "ty", "thx", "tysm", "pls", "plz", "tbh",
    "idk", "idc", "ikr", "ngl", "ttyl", "lmk", "brb", "gtg", "imo", "imho",
    "fwiw", "smh", "wdym", "hby", "fr", "bc", "cuz", "tho", "tmrw", "tmr",
    "gn", "gm", "hf", "gl", "wb", "wya", "wyd", "ofc", "msg", "txt", "vid",
    "lowkey", "highkey", "sus", "slay", "dm", "dms", "irl", "tfw", "mfw",
    "bruh", "sheesh", "oop", "lmaooo", "lmao", "lmfao", "rofl", "omg", "omfg",
    "wtf", "wth", "ffs", "smfh", "istg",
}

# Web-corpus noise that is never a chat continuation.
JUNK = {
    "http", "https", "www", "com", "org", "net", "html", "htm", "php", "asp",
    "url", "href", "src", "img", "jpg", "gif", "png", "pdf", "faq", "rss",
    "xml", "css", "js", "cgi", "ftp", "aspx", "jsp", "wiki", "login", "sitemap",
    "copyright", "inc", "llc", "ltd", "corp", "vs", "etc", "eg", "ie",
    "th", "st", "nd", "rd",
}

vocab = set()
with open(DICT, encoding="utf-8") as f:
    for line in f:
        w, _, _ = line.rstrip("\n").partition("\t")
        if w:
            vocab.add(w)
vocab |= CONTRACTIONS | TEXTING | {"i", "a"}

WORD = re.compile(r"^[a-z]+$")


def norm(w):
    w = w.lower()
    if w in CONTRACTION_FIXES:
        fixed = CONTRACTION_FIXES[w]
        return fixed if fixed else w
    return w


rows = defaultdict(dict)
kept = 0
for line in open(SRC, encoding="utf-8", errors="replace"):
    line = line.rstrip("\n")
    if not line:
        continue
    pair, _, cnt = line.partition("\t")
    if not cnt.isdigit():
        continue
    parts = pair.split(" ")
    if len(parts) != 2:
        continue
    a, b = parts
    if not WORD.match(a) or not WORD.match(b):
        continue
    a = norm(a)
    b = norm(b)
    if a in JUNK or b in JUNK:
        continue
    if a not in vocab or b not in vocab:
        continue
    if a == b and len(a) <= 3:
        continue  # "the the", "of of": corpus artefacts
    c = int(cnt) // 1000
    if c <= 0:
        continue
    row = rows[a]
    row[b] = row.get(b, 0) + c
    kept += 1

n_entries = 0
with open(OUT, "w", encoding="utf-8", newline="\n") as out:
    for prev in sorted(rows):
        row = rows[prev]
        items = sorted(row.items(), key=lambda kv: -kv[1])[:MAX_PER_ROW]
        total = sum(c for _, c in items)
        if total < MIN_ROW_TOTAL:
            continue
        out.write(prev)
        for w, c in items:
            out.write("\t%s %d" % (w, min(c, 2_000_000_000)))
            n_entries += 1
        out.write("\n")

print("rows=%d entries=%d (kept before cap %d)" % (len(rows), n_entries, kept))
