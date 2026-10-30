
from functools import lru_cache
from sentence_transformers import SentenceTransformer


# Model used to convert text into embeddings
MODEL_NAME = "sentence-transformers/all-MiniLM-L6-v2"


@lru_cache(maxsize=1)
def get_embedding_model():
    """
    Load the embedding model once and reuse it.
    """
    return SentenceTransformer(MODEL_NAME)


def generate_embeddings(chunks: list[str]) -> list[list[float]]:
    """
    Convert text chunks into numerical embeddings.
    Each embedding contains 384 numbers.
    """

    if not chunks:
        return []

    model = get_embedding_model()

    embeddings = model.encode(
        chunks,
        normalize_embeddings=True
    )

    return embeddings.tolist()
