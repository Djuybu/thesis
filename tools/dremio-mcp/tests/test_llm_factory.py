#
#  Copyright (C) 2017-2025 Dremio Corporation
#

import pytest

from dremioai.gateway import llm_factory


@pytest.fixture(autouse=True)
def _clear_llm_env(monkeypatch):
    for name in (
        "LLM_PROVIDER",
        "OPENAI_API_KEY",
        "LLM_API_KEY",
        "GEMINI_API_KEY",
        "GOOGLE_API_KEY",
        "OPENAI_MODEL",
        "LLM_MODEL",
        "GEMINI_MODEL",
        "OLLAMA_MODEL",
    ):
        monkeypatch.delenv(name, raising=False)
    llm_factory._llm_cache.clear()


def test_default_provider_is_ollama_without_key():
    assert llm_factory.resolve_llm_provider() == "ollama"
    assert llm_factory.default_llm_model() == llm_factory.DEFAULT_OLLAMA_MODEL


def test_api_key_selects_openai(monkeypatch):
    monkeypatch.setenv("OPENAI_API_KEY", "sk-test")
    assert llm_factory.resolve_llm_provider() == "openai"
    assert llm_factory.llm_api_key_configured() is True


def test_explicit_openai_provider_requires_key(monkeypatch):
    monkeypatch.setenv("LLM_PROVIDER", "openai")
    with pytest.raises(ValueError, match="OPENAI_API_KEY"):
        llm_factory.build_llm()


def test_gemini_provider_and_model(monkeypatch):
    monkeypatch.setenv("LLM_PROVIDER", "gemini")
    monkeypatch.setenv("GEMINI_API_KEY", "test-key")
    monkeypatch.setenv("GEMINI_MODEL", "gemini-2.0-flash")
    assert llm_factory.resolve_llm_provider() == "gemini"
    assert llm_factory.default_llm_model() == "gemini-2.0-flash"
    assert llm_factory.llm_api_key_configured() is True


def test_gemini_auto_when_only_gemini_key(monkeypatch):
    monkeypatch.setenv("GEMINI_API_KEY", "test-key")
    assert llm_factory.resolve_llm_provider() == "gemini"
