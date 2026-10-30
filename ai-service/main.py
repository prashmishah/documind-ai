
from fastapi import FastAPI, UploadFile, File, HTTPException
from pydantic import BaseModel
from chunking import chunk_text
from embeddings import generate_embeddings
from ollama_client import generate_answer
import pymupdf


# ==========================================
# Initialize FastAPI
# ==========================================

app = FastAPI(
    title="DocuMind AI Service",
    description="AI-powered PDF processing service",
    version="1.0.0"
)


# ==========================================
# Health Check
# ==========================================

@app.get("/health")
def health():
    return {
        "status": "running",
        "service": "DocuMind AI Python Service"
    }


# ==========================================
# PDF Text Extraction + Automatic Chunking
# ==========================================

@app.post("/extract-text")
async def extract_text(file: UploadFile = File(...)):

    if not file.filename or not file.filename.lower().endswith(".pdf"):
        raise HTTPException(
            status_code=400,
            detail="Only PDF files are allowed"
        )

    content = await file.read()

    if len(content) > 10 * 1024 * 1024:
        raise HTTPException(
            status_code=413,
            detail="Maximum file size is 10 MB"
        )

    if not content.startswith(b"%PDF-"):
        raise HTTPException(
            status_code=400,
            detail="Invalid PDF file"
        )

    try:
        with pymupdf.open(stream=content, filetype="pdf") as pdf:

            total_pages = len(pdf)
            pages = []

            for page_number, page in enumerate(pdf, start=1):
                text = page.get_text()

                pages.append({
                    "page": page_number,
                    "text": text
                })

        full_text = "\n\n".join(
            page["text"] for page in pages
        )

        chunks = chunk_text(full_text)

        return {
            "filename": file.filename,
            "total_pages": total_pages,
            "pages": pages,
            "total_chunks": len(chunks),
            "chunks": [
                {
                    "chunk_index": index,
                    "text": chunk
                }
                for index, chunk in enumerate(chunks)
            ]
        }

    except Exception as e:
        print(f"PDF processing error: {type(e).__name__}: {e}")

        raise HTTPException(
            status_code=400,
            detail="Unable to process PDF"
        )


# ==========================================
# Text Chunking
# ==========================================

class ChunkRequest(BaseModel):
    text: str


@app.post("/chunk-text")
def create_chunks(request: ChunkRequest):

    chunks = chunk_text(request.text)

    return {
        "total_chunks": len(chunks),
        "chunks": [
            {
                "chunk_index": index,
                "text": chunk
            }
            for index, chunk in enumerate(chunks)
        ]
    }


# ==========================================
# Generate Embeddings
# ==========================================

class EmbeddingRequest(BaseModel):
    texts: list[str]


@app.post("/generate-embeddings")
def create_embeddings(request: EmbeddingRequest):

    if not request.texts:
        raise HTTPException(
            status_code=400,
            detail="Please provide at least one text"
        )

    vectors = generate_embeddings(request.texts)

    return {
        "model": "all-MiniLM-L6-v2",
        "total_embeddings": len(vectors),
        "dimensions": 384,
        "embeddings": vectors
    }


# ==========================================
# AI Question Answering with Ollama
# ==========================================

class QuestionRequest(BaseModel):
    question: str
    context: str


@app.post("/ask-question")
def ask_question(request: QuestionRequest):

    if not request.question.strip():
        raise HTTPException(
            status_code=400,
            detail="Question cannot be empty"
        )

    if not request.context.strip():
        raise HTTPException(
            status_code=400,
            detail="Document context cannot be empty"
        )

    try:
        answer = generate_answer(
            question=request.question,
            context=request.context
        )

        return {
            "question": request.question,
            "answer": answer,
            "model": "qwen2.5:3b"
        }

    except Exception as e:
        print(
            f"Ollama error: {type(e).__name__}: {e}"
        )

        raise HTTPException(
            status_code=502,
            detail="Unable to generate an AI answer"
        )
