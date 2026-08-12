import os
from contextlib import asynccontextmanager
from pathlib import Path

import torch
from fastapi import FastAPI, HTTPException
from modelscope.hub.snapshot_download import snapshot_download
from pydantic import BaseModel, Field
from transformers import AutoModelForSequenceClassification, AutoTokenizer


RERANK_MODEL_ID = os.getenv("MODELRAG_RERANK_MODEL", "BAAI/bge-reranker-v2-m3")
INTENT_MODEL_PATH = os.getenv("MODELRAG_INTENT_MODEL")
MAX_DOCUMENTS = 20
MAX_LENGTH = 512
rerank_tokenizer = None
rerank_model = None
intent_tokenizer = None
intent_model = None


class RerankRequest(BaseModel):
    query: str = Field(min_length=1)
    documents: list[str] = Field(min_length=1, max_length=MAX_DOCUMENTS)


class RerankResponse(BaseModel):
    scores: list[float]


class IntentRequest(BaseModel):
    query: str = Field(min_length=1)


class IntentResponse(BaseModel):
    label: str
    confidence: float


@asynccontextmanager
async def lifespan(_: FastAPI):
    global rerank_model, rerank_tokenizer, intent_model, intent_tokenizer
    rerank_path = snapshot_download(RERANK_MODEL_ID, cache_dir=os.getenv("MODELSCOPE_CACHE"))
    rerank_tokenizer = AutoTokenizer.from_pretrained(rerank_path)
    rerank_model = AutoModelForSequenceClassification.from_pretrained(rerank_path)
    rerank_model.eval()
    if INTENT_MODEL_PATH and Path(INTENT_MODEL_PATH).is_dir():
        intent_tokenizer = AutoTokenizer.from_pretrained(INTENT_MODEL_PATH)
        intent_model = AutoModelForSequenceClassification.from_pretrained(INTENT_MODEL_PATH)
        intent_model.eval()
    yield


app = FastAPI(title="ModelRAG local model runtime", lifespan=lifespan)


@app.get("/health")
def health():
    return {"status": "UP" if rerank_model is not None else "DOWN", "reranker": RERANK_MODEL_ID,
            "intentReady": intent_model is not None}


@app.post("/rerank", response_model=RerankResponse)
def rerank(request: RerankRequest):
    if rerank_model is None or rerank_tokenizer is None:
        raise HTTPException(status_code=503, detail="reranker is not ready")
    inputs = rerank_tokenizer([[request.query, document] for document in request.documents], padding=True,
                               truncation=True, return_tensors="pt", max_length=MAX_LENGTH)
    with torch.no_grad():
        logits = rerank_model(**inputs, return_dict=True).logits.view(-1).float()
    return RerankResponse(scores=logits.tolist())


@app.post("/classify", response_model=IntentResponse)
def classify(request: IntentRequest):
    if intent_model is None or intent_tokenizer is None:
        raise HTTPException(status_code=503, detail="intent model is not ready")
    inputs = intent_tokenizer(request.query, truncation=True, max_length=128, return_tensors="pt")
    with torch.no_grad():
        probabilities = torch.softmax(intent_model(**inputs, return_dict=True).logits[0], dim=0)
    index = int(probabilities.argmax())
    return IntentResponse(label=intent_model.config.id2label[index], confidence=float(probabilities[index]))
