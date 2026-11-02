
import pytest

from chunking import chunk_text


def test_empty_text():
    assert chunk_text("") == []


def test_whitespace_only_text():
    assert chunk_text("   ") == []


def test_short_text():
    result = chunk_text("Hello DocuMind AI")
    assert result == ["Hello DocuMind AI"]


def test_text_splits_into_chunks():
    text = "A" * 2500

    result = chunk_text(text)

    assert len(result) == 3
    assert len(result[0]) == 1000
    assert len(result[1]) == 1000
    assert len(result[2]) == 900


def test_chunks_have_overlap():
    text = "0123456789ABCDEFGHIJ"

    result = chunk_text(
        text,
        chunk_size=10,
        overlap=3
    )

    assert len(result) == 3
    assert result[0][-3:] == result[1][:3]
    assert result[1][-3:] == result[2][:3]


def test_invalid_chunk_size():
    with pytest.raises(ValueError):
        chunk_text(
            "Hello",
            chunk_size=0
        )


def test_negative_overlap():
    with pytest.raises(ValueError):
        chunk_text(
            "Hello",
            overlap=-1
        )


def test_overlap_equal_to_chunk_size():
    with pytest.raises(ValueError):
        chunk_text(
            "Hello",
            chunk_size=100,
            overlap=100
        )
