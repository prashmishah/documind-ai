
import { useEffect, useState } from "react";
import "./App.css";

function App() {
  const [file, setFile] = useState(null);
  const [documents, setDocuments] = useState([]);
  const [selectedId, setSelectedId] = useState("");
  const [question, setQuestion] = useState("");
  const [answer, setAnswer] = useState(null);
  const [message, setMessage] = useState("");
  const [loading, setLoading] = useState("");
  const [history, setHistory] = useState([]);

  async function readResponse(response) {
    const text = await response.text();
    let data;

    try {
      data = text ? JSON.parse(text) : null;
    } catch {
      data = text;
    }

    if (!response.ok) {
      const details =
        typeof data === "string"
          ? data
          : data?.detail ||
            data?.message ||
            data?.error ||
            data?.reason ||
            `Request failed with HTTP ${response.status}`;

      throw new Error(
        typeof details === "string"
          ? details
          : JSON.stringify(details)
      );
    }

    return data;
  }

  async function loadDocuments() {
    try {
      const response = await fetch("/api/documents");
      const data = await readResponse(response);
      const items = Array.isArray(data) ? data : [];

      setDocuments(items);

      setSelectedId((current) =>
        items.some((doc) => String(doc.id) === String(current))
          ? current
          : items.length > 0
            ? String(items[0].id)
            : ""
      );

      return items;
    } catch (error) {
      setMessage(`Could not load documents: ${error.message}`);
      throw error;
    }
  }

  useEffect(() => {
    loadDocuments().catch(() => {});
  }, []);

  async function handleUpload(event) {
    event.preventDefault();

    if (!file) {
      setMessage("Please select a PDF file.");
      return;
    }

    if (!file.name.toLowerCase().endsWith(".pdf")) {
      setMessage("Only PDF files are allowed.");
      return;
    }

    if (file.size > 10 * 1024 * 1024) {
      setMessage("Maximum PDF size is 10 MB.");
      return;
    }

    setLoading("upload");
    setMessage("");

    try {
      const formData = new FormData();
      formData.append("file", file);

      const response = await fetch("/api/documents", {
        method: "POST",
        body: formData,
      });

      const uploaded = await readResponse(response);

      await loadDocuments();

      if (uploaded?.id != null) {
        setSelectedId(String(uploaded.id));
      }

      setFile(null);
      event.target.reset();
      setAnswer(null);
      setHistory([]);
      setQuestion("");

      setMessage(
        "PDF uploaded successfully. Process it to enable AI questions."
      );
    } catch (error) {
      setMessage(`Upload failed: ${error.message}`);
    } finally {
      setLoading("");
    }
  }

  async function handleProcess() {
    if (!selectedId) {
      setMessage("Select a document first.");
      return;
    }

    setLoading("process");
    setMessage("");
    setAnswer(null);

    try {
      const response = await fetch(
        `/api/documents/${selectedId}/extract`,
        {
          method: "POST",
        }
      );

      await readResponse(response);

      setMessage(
        "Document processed successfully. You can now ask questions."
      );
    } catch (error) {
      setMessage(`Processing failed: ${error.message}`);
    } finally {
      setLoading("");
    }
  }

  async function handleAsk(event) {
    event.preventDefault();

    const cleanQuestion = question.trim();

    if (!selectedId) {
      setMessage("Select a document first.");
      return;
    }

    if (!cleanQuestion) {
      setMessage("Please enter a question.");
      return;
    }

    setLoading("ask");
    setMessage("");
    setAnswer(null);

    try {
      const response = await fetch(
        `/api/documents/${selectedId}/ask`,
        {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
          },
          body: JSON.stringify({
            question: cleanQuestion,
          }),
        }
      );

      const data = await readResponse(response);

      const result = {
        question: cleanQuestion,
        answer: data.answer || "No answer was returned.",
        model: data.model || "AI model",
        chunksUsed: data.chunksUsed,
      };

      setAnswer(result);
      setHistory((previous) => [result, ...previous]);
      setQuestion("");
    } catch (error) {
      setMessage(`Question failed: ${error.message}`);
    } finally {
      setLoading("");
    }
  }

  function chooseDocument(id) {
    setSelectedId(String(id));
    setAnswer(null);
    setHistory([]);
    setQuestion("");
    setMessage("");
  }

  // ==========================================
  // DELETE DOCUMENT
  // ==========================================

  async function handleDelete(doc) {
    const confirmed = window.confirm(
      `Are you sure you want to permanently delete "${doc.filename}"?\n\n` +
        "This will remove the PDF and its saved AI data. This cannot be undone."
    );

    if (!confirmed) {
      return;
    }

    setLoading("delete");
    setMessage("");

    try {
      const response = await fetch(
        `/api/documents/${doc.id}`,
        {
          method: "DELETE",
        }
      );

      await readResponse(response);

      const remaining = documents.filter(
        (item) => String(item.id) !== String(doc.id)
      );

      setDocuments(remaining);

      if (String(selectedId) === String(doc.id)) {
        setSelectedId(
          remaining.length > 0
            ? String(remaining[0].id)
            : ""
        );
        setAnswer(null);
        setHistory([]);
        setQuestion("");
      }

      setMessage(
        `"${doc.filename}" was deleted successfully.`
      );
    } catch (error) {
      setMessage(`Delete failed: ${error.message}`);
    } finally {
      setLoading("");
    }
  }

  const selectedDocument = documents.find(
    (doc) => String(doc.id) === String(selectedId)
  );

  const busy = loading !== "";

  return (
    <div className="app-container">
      <header className="header">
        <div>
          <h1>DocuMind AI</h1>
          <p>Your AI-powered PDF research assistant</p>
        </div>

        <span className="app-badge">
          AI Document Assistant
        </span>
      </header>

      <main className="dashboard">
        {/* UPLOAD DOCUMENT */}

        <section className="upload-card">
          <h2>Upload a Document</h2>
          <p>
            Upload a PDF and let DocuMind AI understand its
            content.
          </p>

          <form onSubmit={handleUpload}>
            <input
              type="file"
              accept=".pdf,application/pdf"
              disabled={busy}
              onChange={(event) =>
                setFile(event.target.files?.[0] || null)
              }
            />

            <button type="submit" disabled={busy}>
              {loading === "upload"
                ? "Uploading..."
                : "Upload PDF"}
            </button>
          </form>
        </section>

        {/* DOCUMENT LIBRARY */}

        <section className="documents-card">
          <h2>My Documents</h2>
          <p>
            Select a document to work with, or delete PDFs
            you no longer need.
          </p>

          {documents.length === 0 ? (
            <p>No documents uploaded yet.</p>
          ) : (
            <ul className="document-list">
              {documents.map((doc) => (
                <li
                  key={doc.id}
                  className={
                    String(selectedId) === String(doc.id)
                      ? "selected-document"
                      : ""
                  }
                >
                  <button
                    type="button"
                    className="document-select"
                    disabled={busy}
                    onClick={() => chooseDocument(doc.id)}
                  >
                    <span className="document-name">
                      📄 {doc.filename}
                    </span>

                    <small>
                      {typeof doc.fileSize === "number"
                        ? `${(
                            doc.fileSize /
                            1024 /
                            1024
                          ).toFixed(2)} MB`
                        : `Document #${doc.id}`}
                    </small>
                  </button>

                  <button
                    type="button"
                    className="delete-document"
                    disabled={busy}
                    onClick={() => handleDelete(doc)}
                    title={`Delete ${doc.filename}`}
                    aria-label={`Delete ${doc.filename}`}
                  >
                    Delete
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>

        {/* PROCESS DOCUMENT */}

        <section className="processing-card">
          <h2>Process Document</h2>

          {selectedDocument ? (
            <>
              <p>
                Selected:{" "}
                <strong>
                  {selectedDocument.filename}
                </strong>
              </p>

              <p>
                Extract text, split it into chunks, and
                generate embeddings for semantic search.
              </p>

              <button
                type="button"
                disabled={busy}
                onClick={handleProcess}
              >
                {loading === "process"
                  ? "Processing document..."
                  : "Process Selected PDF"}
              </button>
            </>
          ) : (
            <p>Upload or select a PDF to begin.</p>
          )}
        </section>

        {/* ASK AI */}

        <section className="question-card">
          <h2>Ask DocuMind AI</h2>

          <p>
            Ask questions about your selected PDF. Answers
            are generated using relevant document content.
          </p>

          <form onSubmit={handleAsk}>
            <textarea
              value={question}
              onChange={(event) =>
                setQuestion(event.target.value)
              }
              placeholder="What is this document about?"
              rows={4}
              disabled={busy || !selectedId}
            />

            <button
              type="submit"
              disabled={
                busy ||
                !selectedId ||
                !question.trim()
              }
            >
              {loading === "ask"
                ? "Generating answer..."
                : "Ask Question"}
            </button>
          </form>

          {answer && (
            <div className="answer-box">
              <h3>AI Answer</h3>

              <p className="answer-question">
                <strong>Question:</strong>{" "}
                {answer.question}
              </p>

              <p className="answer-content">
                {answer.answer}
              </p>

              <div className="answer-meta">
                <span>
                  Model: {answer.model}
                </span>

                {answer.chunksUsed != null && (
                  <span>
                    Retrieved chunks:{" "}
                    {answer.chunksUsed}
                  </span>
                )}
              </div>
            </div>
          )}
        </section>

        {/* STATUS MESSAGE */}

        {message && (
          <section className="status-card">
            <p role="status">{message}</p>
          </section>
        )}

        {/* RECENT QUESTIONS */}

        {history.length > 1 && (
          <section className="history-card">
            <h2>Recent Questions</h2>

            {history.slice(1).map((item, index) => (
              <div
                className="history-item"
                key={index}
              >
                <h3>{item.question}</h3>
                <p>{item.answer}</p>
              </div>
            ))}
          </section>
        )}
      </main>

      <footer className="app-footer">
        DocuMind AI — Java Spring Boot, React,
        PostgreSQL, FastAPI and Ollama
      </footer>
    </div>
  );
}

export default App;
