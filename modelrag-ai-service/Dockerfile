FROM python:3.12-slim

WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    MODELRAG_AI_EMBEDDING_ENABLED=true \
    MODELRAG_AI_EMBEDDING_REQUIRED=true \
    MODELRAG_AI_EMBEDDING_ENGINE=sentence-transformers \
    MODELRAG_AI_EMBEDDING_MODEL=Qwen/Qwen3-Embedding-0.6B \
    MODELRAG_AI_RERANK_ENABLED=true \
    MODELRAG_AI_RERANK_REQUIRED=true \
    MODELRAG_AI_RERANK_ENGINE=sentence-transformers \
    MODELRAG_AI_RERANK_MODEL=cross-encoder/ms-marco-MiniLM-L6-v2 \
    MODELRAG_AI_DOCUMENT_AI_ENABLED=true \
    MODELRAG_AI_DOCUMENT_AI_ENGINE=pymupdf-docx \
    MODELRAG_AI_OCR_ENABLED=true \
    MODELRAG_AI_OCR_ENGINE=pytesseract

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       tesseract-ocr \
       tesseract-ocr-eng \
       tesseract-ocr-chi-sim \
    && rm -rf /var/lib/apt/lists/*

COPY pyproject.toml README.md ./
COPY app ./app

RUN pip install --no-cache-dir ".[runtime]"

EXPOSE 18180
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "18180"]
