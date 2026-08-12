import argparse
import json
from pathlib import Path

import torch
from modelscope.hub.snapshot_download import snapshot_download
from torch.optim import AdamW
from torch.utils.data import DataLoader
from transformers import AutoModelForSequenceClassification, AutoTokenizer


LABELS = ["DIRECT_RAG", "AGENT"]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", default="intent_train.jsonl")
    parser.add_argument("--output", default="F:/ModelRAGModels/intent-router-bert-base-zh")
    parser.add_argument("--epochs", type=int, default=8)
    args = parser.parse_args()
    rows = [json.loads(line) for line in Path(args.data).read_text(encoding="utf-8").splitlines() if line.strip()]
    labels = {name: index for index, name in enumerate(LABELS)}
    base = snapshot_download("google-bert/bert-base-chinese")
    tokenizer = AutoTokenizer.from_pretrained(base)
    model = AutoModelForSequenceClassification.from_pretrained(base, num_labels=len(LABELS), ignore_mismatched_sizes=True)
    model.config.id2label = dict(enumerate(LABELS))
    model.config.label2id = labels
    batches = DataLoader(rows, batch_size=4, shuffle=True)
    optimizer = AdamW(model.parameters(), lr=2e-5)
    model.train()
    for _ in range(args.epochs):
        for batch in batches:
            encoded = tokenizer(batch["text"], padding=True, truncation=True, max_length=128, return_tensors="pt")
            output = model(**encoded, labels=torch.tensor([labels[value] for value in batch["label"]]))
            output.loss.backward(); optimizer.step(); optimizer.zero_grad()
    Path(args.output).mkdir(parents=True, exist_ok=True)
    model.save_pretrained(args.output); tokenizer.save_pretrained(args.output)
    print(args.output)


if __name__ == "__main__":
    main()
