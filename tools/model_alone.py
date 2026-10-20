"""A model on its own, without sources: the "a 1B model fails" comparison the bounty's demo asks for.

  llama-server -m Llama-3.2-1B-Instruct-Q4_K_M.gguf -c 4096 --port 8089 --jinja --cache-ram 0 &
  python model_alone.py --port 8089 --name llama3.2-1b-alone --questions ../eval/questions-holdout.jsonl

Writes eval/results-pc-<name>.jsonl in the format grade.py reads (id, question, answer), so the run is graded
blind against the frontier + web baseline like Bivouac's own runs. Greedy and seeded, like answer_proto.py.
"""
import argparse
import json
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def ask(port: int, q: str) -> str:
    body = json.dumps({"messages": [{"role": "user", "content": q}], "temperature": 0, "seed": 1,
                       "max_tokens": 1000, "cache_prompt": False}).encode()
    req = urllib.request.Request(f"http://localhost:{port}/v1/chat/completions", body,
                                 {"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=900))["choices"][0]["message"]["content"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8089)
    ap.add_argument("--name", required=True)
    ap.add_argument("--questions", required=True)
    args = ap.parse_args()
    out = ROOT / f"eval/results-pc-{args.name}.jsonl"
    done = {json.loads(l)["id"] for l in open(out)} if out.exists() else set()
    with open(out, "a") as f:
        for q in map(json.loads, open(args.questions)):
            if q["id"] in done:
                continue
            a = ask(args.port, q["q"])
            f.write(json.dumps({"id": q["id"], "question": q["q"], "answer": a}, ensure_ascii=False) + "\n")
            f.flush()
            print(f"{q['id']:14s} {len(a)} chars")


if __name__ == "__main__":
    main()
