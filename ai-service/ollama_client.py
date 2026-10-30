
import json
import urllib.request
import urllib.error


OLLAMA_URL = "http://127.0.0.1:11434/api/generate"
OLLAMA_MODEL = "qwen2.5:3b"


def generate_answer(question: str, context: str) -> str:
    """
    Generate an answer using Ollama and retrieved
    text from an uploaded PDF.
    """

    prompt = f"""
You are DocuMind AI, a document question-answering assistant.

Answer the user's question using ONLY the provided
document context.

Rules:
1. Use only information from the document.
2. Do not invent information.
3. If the answer is not in the document, say:
   "I couldn't find that information in the document."
4. Keep your answer clear and helpful.

DOCUMENT CONTEXT:
{context}

USER QUESTION:
{question}

ANSWER:
"""

    payload = {
        "model": OLLAMA_MODEL,
        "prompt": prompt,
        "stream": False,
        "options": {
            "temperature": 0.2
        }
    }

    request = urllib.request.Request(
        OLLAMA_URL,
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Content-Type": "application/json"
        },
        method="POST"
    )

    try:
        with urllib.request.urlopen(
            request,
            timeout=180
        ) as response:

            result = json.loads(
                response.read().decode("utf-8")
            )

            return result.get(
                "response",
                ""
            ).strip()

    except urllib.error.URLError as error:
        raise RuntimeError(
            f"Unable to connect to Ollama: {error}"
        )
