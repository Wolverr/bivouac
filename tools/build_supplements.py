"""Supplement documents for the Wikipedia pack: Ethereum standards and docs, post-quantum standards, Wikivoyage.

Wikipedia lacks what Boar's question set (AndroidLM's yardstick) asks about Ethereum ("What does EIP-7702 let an EOA
do?", "maximum effective balance after EIP-7251") and travel ("plug type in Brazil", "thank you in Thai"). These
openly licensed sources are taken whole, not page by page for the questions:

  eips/ercs   github.com/ethereum/EIPs and /ERCs: status Final, Living, Last Call, Review (CC0)
  eorg        github.com/ethereum/ethereum-org-website public/content (developer docs, roadmap, ...; MIT)
  ue          github.com/benjaminion/upgrading-ethereum-book src/book.md, one article per page (CC BY-SA 4.0)
  nist        FIPS 203/204/205, SP 800-208, IR 8547 (public domain), the prose before the algorithms
  wikivoyage  dumps.wikimedia.org enwikivoyage pages-articles (CC BY-SA 4.0)

Writes JSON lines {source, title, prior, aliases, text}; text is markdown with "## " headings, as build_corpus.py
chunks it. Titles carry their source ("EIP-4844", "Ethereum docs: Gasper", "Wikivoyage: Thailand") so they never
equal a Wikipedia title. tools/append_supplements.py adds them to wiki.db.

Usage: python build_supplements.py --src ~/bivouac-ext/src --out out/supplements.jsonl
"""

import argparse
import bz2
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

# Popularity stand-ins (log1p of yearly views, like article.prior): a mid-popular article, so a source that matches
# the question well can rank, without crowding out the big Wikipedia articles everywhere.
PRIOR_ETH = 9.0
PRIOR_NIST = 9.0
PRIOR_VOYAGE = 9.5

FRONT = re.compile(r"\A---\n(.*?)\n---\n", re.S)
FENCE = re.compile(r"```.*?```", re.S)
MD_LINK = re.compile(r"!?\[([^\]]*)\]\([^)]*\)")
HTML = re.compile(r"<[^>\n]+>")
JSX_COMMENT = re.compile(r"\{/\*.*?\*/\}", re.S)
HEAD_ANCHOR = re.compile(r"\s*\{#[^}]*\}\s*$|\s*<!--.*?-->\s*$")
EIP_SKIP = {"copyright", "test cases", "reference implementation", "references", "citation"}


def front_matter(text: str) -> tuple[dict, str]:
    m = FRONT.match(text)
    if not m:
        return {}, text
    meta = {}
    for line in m.group(1).splitlines():
        if ":" in line:
            k, v = line.split(":", 1)
            meta[k.strip()] = v.strip().strip('"')
    return meta, text[m.end():]


def clean_md(body: str, max_code_lines: int = 6) -> str:
    """Markdown as plain text: short code blocks stay, long ones (specs, Solidity) and markup go."""
    def code(m):
        lines = m.group(0).splitlines()[1:-1]
        return "\n".join(lines) if len(lines) <= max_code_lines else ""
    body = FENCE.sub(code, body)
    body = JSX_COMMENT.sub("", body)
    body = MD_LINK.sub(r"\1", body)
    body = HTML.sub("", body)
    out = []
    for line in body.splitlines():
        if line.startswith("#"):
            line = HEAD_ANCHOR.sub("", line)
        out.append(line.rstrip())
    return re.sub(r"\n{3,}", "\n\n", "\n".join(out)).strip()


def drop_sections(md: str, skip: set) -> str:
    out, keep = [], True
    for line in md.splitlines():
        m = re.match(r"^(#{1,6})\s+(.*)$", line)
        if m and len(m.group(1)) == 2:
            keep = m.group(2).strip().casefold() not in skip
        if keep:
            out.append(line)
    return "\n".join(out)


def bare(title: str) -> str:
    """A page's own name as an alias ("Proposer-builder separation", "BLS Signatures"), without a trailing "(MEV)":
    an acronym alias would take "MeV" from Electronvolt. append_supplements.py keeps it only when it has two words or
    more and no Wikipedia article or redirect has that name."""
    return re.sub(r"\s*\([^()]*\)$", "", title).strip()


def eips(root: Path, kind: str):
    folder = root / ("eips/EIPS" if kind == "EIP" else "ercs/ERCS")
    for f in sorted(folder.glob("*.md")):
        meta, body = front_matter(f.read_text(errors="replace"))
        if meta.get("status") not in ("Final", "Living", "Last Call", "Review"):
            continue
        n = meta.get("eip") or f.stem.split("-")[-1]
        title = f"{kind}-{n}"
        lead = f"{title}: {meta.get('title', '')}. {meta.get('description', '')}".strip()
        facts = f"Status: {meta.get('status')}. Type: {meta.get('type', '')}" + \
                (f", {meta['category']}" if meta.get("category") else "") + f". Created {meta.get('created', '')}."
        text = lead + "\n\n" + facts + "\n\n" + clean_md(drop_sections(body, EIP_SKIP))
        aliases = [f"{kind} {n}", f"{kind}{n}"]
        if kind == "ERC":  # ERC-20 was EIP-20 before the ERCs repository split off
            aliases += [f"EIP-{n}", f"EIP {n}"]
        yield {"source": "eips", "title": title, "prior": PRIOR_ETH, "aliases": aliases, "text": text}


def ethereum_org(root: Path):
    base = root / "eorg/public/content"
    for f in sorted(base.rglob("index.md")):
        rel = f.parent.relative_to(base)
        if rel.parts and rel.parts[0] == "translations":
            continue
        meta, body = front_matter(f.read_text(errors="replace"))
        title = meta.get("title") or rel.name.replace("-", " ").capitalize()
        text = (meta.get("description", "") + "\n\n" + clean_md(body)).strip()
        if len(text) < 300:
            continue
        yield {"source": "ethereum.org", "title": f"Ethereum docs: {title}", "prior": PRIOR_ETH, "aliases": [bare(title)],
               "text": text}


def upgrading_ethereum(root: Path):
    """One article per page ("### "), titled with its chapter; "#### " and deeper become its sections."""
    chapter, title, buf = "", None, []

    def flush():
        if title and buf:
            text = clean_md("\n".join(buf))
            if len(text) >= 300:
                return {"source": "upgrading-ethereum", "title": f"Upgrading Ethereum: {title}",
                        "prior": PRIOR_ETH, "aliases": [bare(title)], "text": text}
        return None

    for line in (root / "ue/src/book.md").read_text(errors="replace").splitlines():
        m = re.match(r"^(#{1,6})\s+(.*?)\s*(<!--.*-->)?\s*$", line)
        level = len(m.group(1)) if m else 0
        if m and level <= 3:
            doc = flush()
            if doc:
                yield doc
            name = m.group(2).replace("&ldquo;", '"').replace("&rdquo;", '"')
            if level <= 2:
                chapter, title, buf = name, (name if level == 2 else None), []
            else:
                title, buf = f"{chapter}, {name}" if name.lower() in ("introduction", "overview", "terminology") else name, []
            continue
        if m:  # #### and deeper: sections of the page
            line = "#" * (level - 2) + " " + m.group(2)
        buf.append(line)
    doc = flush()
    if doc:
        yield doc


NIST_DOCS = {
    "NIST.FIPS.203.pdf": ("FIPS 203: ML-KEM, Module-Lattice-Based Key-Encapsulation Mechanism Standard", ["ML-KEM", "FIPS 203"]),
    "NIST.FIPS.204.pdf": ("FIPS 204: ML-DSA, Module-Lattice-Based Digital Signature Standard", ["ML-DSA", "FIPS 204"]),
    "NIST.FIPS.205.pdf": ("FIPS 205: SLH-DSA, Stateless Hash-Based Digital Signature Standard", ["SLH-DSA", "FIPS 205"]),
    # Not "(LMS, XMSS)": a title ending in "(A, B)" with both in the question reads as a namesake town (is_namesake).
    "NIST.SP.800-208.pdf": ("NIST SP 800-208: Stateful Hash-Based Signature Schemes, LMS and XMSS",
                            ["SP 800-208", "XMSS", "Leighton-Micali Signature", "eXtended Merkle Signature Scheme",
                             "Stateful hash-based signature"]),
    "NIST.IR.8547.ipd.pdf": ("NIST IR 8547: Transition to Post-Quantum Cryptography Standards", ["IR 8547"]),
}
NIST_SECTION = re.compile(r"^(\d{1,2}(?:\.\d{1,2})*)\.?\s+([A-Z][A-Za-z ,()/-]{3,80})$")


def nist(root: Path):
    """The prose up to the algorithm specifications (sections 1-3 of a FIPS; all of SP 800-208's and IR 8547's
    prose up to their references), numbered headings as sections."""
    from pypdf import PdfReader
    for name, (title, aliases) in NIST_DOCS.items():
        reader = PdfReader(str(root / "nist" / name))
        out, started = [], False
        for page in reader.pages:
            for line in (page.extract_text() or "").splitlines():
                line = line.strip()
                m = NIST_SECTION.match(line)
                if m and "." not in line[len(m.group(1)) + 2:]:
                    top = int(m.group(1).split(".")[0])
                    if top == 1:
                        started = True
                    if name.startswith("NIST.FIPS") and top >= 4:
                        started = None
                    if m.group(2).strip().lower() in ("references", "bibliography"):
                        started = None
                    if started:
                        out.append("\n## " + m.group(2).strip())
                        continue
                if started is None:
                    break
                if started and line and not line.startswith("This publication is available free of charge"):
                    out.append(line)
            if started is None:
                break
        text = re.sub(r"(?<=[a-z,])\n(?=[a-z])", " ", "\n".join(out))
        yield {"source": "nist", "title": title, "prior": PRIOR_NIST, "aliases": aliases, "text": text.strip()}


LISTINGS = {"see", "do", "buy", "eat", "drink", "sleep", "listing", "go", "around", "marker", "vcard"}


def voyage_text(wikitext: str) -> str:
    import mwparserfromhell
    code = mwparserfromhell.parse(wikitext)
    for t in code.filter_templates(recursive=False):
        name = str(t.name).strip().lower()
        if name in LISTINGS:
            def p(k):
                return t.get(k).value.strip_code().strip() if t.has(k) else ""
            bits = [x for x in (p("address"), p("hours"), p("price"), p("phone")) if x]
            line = p("name") + (f" ({'; '.join(bits)})" if bits else "") + (": " + p("content") if p("content") else "")
            try:
                code.replace(t, line)
            except ValueError:
                pass
    # strip_code drops the "==" of headings, and a "#" at a line start is wikitext list markup: mark headings with a
    # placeholder, then make them markdown, so sections survive ("Brazil > Electricity").
    for h in code.filter_headings():
        try:
            code.replace(h, f"\n@@H{h.level}@@ {h.title.strip_code().strip()}\n")
        except ValueError:
            pass
    text = code.strip_code(normalize=True, collapse=True)
    text = re.sub(r"^\s*@@H(\d)@@ ", lambda m: "#" * int(m.group(1)) + " ", text, flags=re.M)
    lines = [line for line in text.splitlines() if not IMAGE_LEFTOVER.match(line.strip())]
    return re.sub(r"\n{3,}", "\n\n", "\n".join(lines)).strip()


IMAGE_LEFTOVER = re.compile(r"^(thumb|frameless|upright|right|left|center|\d+px)(\|.*)?$")


def wikivoyage(root: Path, min_chars: int = 1500):
    ns = "{http://www.mediawiki.org/xml/export-0.11/}"
    with bz2.open(root / "wikivoyage/enwikivoyage-latest-pages-articles.xml.bz2") as f:
        for _, el in ET.iterparse(f):
            if el.tag.endswith("}page") or el.tag == "page":
                tag = el.tag[: -len("page")]
                title = el.findtext(f"{tag}title") or ""
                nsid = el.findtext(f"{tag}ns")
                redirect = el.find(f"{tag}redirect") is not None
                wt = el.findtext(f"{tag}revision/{tag}text") or ""
                el.clear()
                if nsid != "0" or redirect or "{{disamb" in wt.lower() or len(wt) < min_chars:
                    continue
                text = voyage_text(wt)
                if len(text) < min_chars:
                    continue
                yield {"source": "wikivoyage", "title": f"Wikivoyage: {title}", "prior": PRIOR_VOYAGE,
                       "aliases": [], "text": text}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--only", default="", help="comma-separated sources: eips,ercs,eorg,ue,nist,wikivoyage")
    args = ap.parse_args()
    root = Path(args.src).expanduser()
    gens = {"eips": lambda: eips(root, "EIP"), "ercs": lambda: eips(root, "ERC"), "eorg": lambda: ethereum_org(root),
            "ue": lambda: upgrading_ethereum(root), "nist": lambda: nist(root), "wikivoyage": lambda: wikivoyage(root)}
    only = [s for s in args.only.split(",") if s] or list(gens)
    counts = {}
    with open(args.out, "w") as out:
        for s in only:
            for doc in gens[s]():
                out.write(json.dumps(doc, ensure_ascii=False) + "\n")
                counts[s] = counts.get(s, 0) + 1
            print(s, counts.get(s, 0), flush=True)


if __name__ == "__main__":
    main()
