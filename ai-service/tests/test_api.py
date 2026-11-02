
from unittest.mock import patch

from fastapi.testclient import TestClient
from main import app

client = TestClient(app)


def test_health_endpoint():
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {
        "status": "running",
        "service": "DocuMind AI Python Service"
    }


def test_chunk_text_endpoint():
    response = client.post(
        "/chunk-text",
        json={"text": "Hello DocuMind AI"}
    )

    assert response.status_code == 200
    assert response.json()["total_chunks"] == 1
    assert response.json()["chunks"][0]["text"] == "Hello DocuMind AI"


def test_chunk_text_empty():
    response = client.post(
        "/chunk-text",
        json={"text": ""}
    )

    assert response.status_code == 200
    assert response.json()["total_chunks"] == 0


def test_embedding_empty_input():
    response = client.post(
        "/generate-embeddings",
        json={"texts": []}
    )

    assert response.status_code == 400


def test_ask_question_empty_question():
    response = client.post(
        "/ask-question",
        json={
            "question": "",
            "context": "Some document text"
        }
    )

    assert response.status_code == 400


def test_ask_question_empty_context():
    response = client.post(
        "/ask-question",
        json={
            "question": "What is this document about?",
            "context": ""
        }
    )

    assert response.status_code == 400


def test_ask_question_success():
    with patch(
        "main.generate_answer",
        return_value="This document describes DocuMind AI."
    ):
        response = client.post(
            "/ask-question",
            json={
                "question": "What is DocuMind AI?",
                "context": "DocuMind AI processes PDF documents."
            }
        )

    assert response.status_code == 200
    assert response.json()["answer"] == (
        "This document describes DocuMind AI."
    )
    assert response.json()["model"] == "qwen2.5:3b"


def test_invalid_file_type():
    response = client.post(
        "/extract-text",
        files={
            "file": (
                "notes.txt",
                b"Hello World",
                "text/plain"
            )
        }
    )

    assert response.status_code == 400
