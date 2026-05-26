#
#  Copyright (C) 2017-2025 Dremio Corporation
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
#

"""Build LangChain chat models for the SQL agent (Ollama, OpenAI-compatible, Gemini)."""

from __future__ import annotations

import logging
from typing import Any, Literal

from dremioai.gateway.config import DEFAULT_OLLAMA_MODEL, env

logger = logging.getLogger("dremio-sql-agent")

LlmProvider = Literal["ollama", "openai", "gemini"]

_llm_cache: dict[str, Any] = {}

DEFAULT_OPENAI_MODEL = "gpt-4o-mini"
DEFAULT_GEMINI_MODEL = "gemini-2.0-flash"


def gemini_api_key() -> str:
    return (
        env("GEMINI_API_KEY")
        or env("GOOGLE_API_KEY")
        or env("GOOGLE_GENERATIVE_AI_API_KEY")
    )


def openai_api_key() -> str:
    return env("OPENAI_API_KEY") or env("LLM_API_KEY")


def openai_base_url() -> str | None:
    url = env("OPENAI_BASE_URL") or env("LLM_BASE_URL")
    return url or None


def _explicit_provider() -> str:
    return env("LLM_PROVIDER", "").lower()


def resolve_llm_provider() -> LlmProvider:
    """``LLM_PROVIDER``: ``ollama`` | ``openai`` | ``gemini``.

    When unset: ``gemini`` if Gemini key present, else ``openai`` if OpenAI key, else ``ollama``.
    """
    raw = _explicit_provider()
    if raw in ("openai", "openai_compatible", "azure", "openrouter", "groq"):
        return "openai"
    if raw in ("gemini", "google", "google_genai"):
        return "gemini"
    if raw in ("ollama", "local"):
        return "ollama"
    if gemini_api_key():
        return "gemini"
    if openai_api_key():
        return "openai"
    return "ollama"


def default_llm_model() -> str:
    provider = resolve_llm_provider()
    if provider == "gemini":
        return env("GEMINI_MODEL") or env("GOOGLE_MODEL") or DEFAULT_GEMINI_MODEL
    if provider == "openai":
        return env("OPENAI_MODEL") or env("LLM_MODEL") or DEFAULT_OPENAI_MODEL
    return env("OLLAMA_MODEL", DEFAULT_OLLAMA_MODEL)


def llm_api_key_configured() -> bool:
    provider = resolve_llm_provider()
    if provider == "openai":
        return bool(openai_api_key())
    if provider == "gemini":
        return bool(gemini_api_key())
    return True


def _stream_enabled(provider: LlmProvider) -> bool:
    if provider == "gemini":
        raw = env("GEMINI_STREAM", env("LLM_STREAM", "false"))
    elif provider == "openai":
        raw = env("OPENAI_STREAM", env("LLM_STREAM", "false"))
    else:
        raw = env("OLLAMA_STREAM", "false")
    return raw.strip().lower() in ("1", "true", "yes", "on")


def _build_ollama_llm(model_name: str, temperature: float) -> Any:
    from langchain_ollama import ChatOllama

    base = env("OLLAMA_BASE_URL", "http://127.0.0.1:11434")
    stream_on = _stream_enabled("ollama")
    cache_key = f"ollama|{model_name}|{base}|{temperature}|{stream_on}"
    if cache_key in _llm_cache:
        return _llm_cache[cache_key]
    llm = ChatOllama(model=model_name, base_url=base, temperature=temperature)
    result = llm if stream_on else llm.bind(stream=False)
    _llm_cache[cache_key] = result
    logger.info("LLM provider=ollama model=%s base_url=%s", model_name, base)
    return result


def _build_openai_llm(model_name: str, temperature: float) -> Any:
    from langchain_openai import ChatOpenAI

    api_key = openai_api_key()
    if not api_key:
        raise ValueError(
            "LLM_PROVIDER=openai requires OPENAI_API_KEY or LLM_API_KEY."
        )
    base_url = openai_base_url()
    stream_on = _stream_enabled("openai")
    cache_key = f"openai|{model_name}|{base_url or ''}|{temperature}|{stream_on}"
    if cache_key in _llm_cache:
        return _llm_cache[cache_key]
    kwargs: dict[str, Any] = {
        "model": model_name,
        "temperature": temperature,
        "api_key": api_key,
    }
    if base_url:
        kwargs["base_url"] = base_url
    llm = ChatOpenAI(**kwargs)
    result = llm if stream_on else llm.bind(stream=False)
    _llm_cache[cache_key] = result
    logger.info(
        "LLM provider=openai model=%s base_url=%s",
        model_name,
        base_url or "<default>",
    )
    return result


def _build_gemini_llm(model_name: str, temperature: float) -> Any:
    from langchain_google_genai import ChatGoogleGenerativeAI

    api_key = gemini_api_key()
    if not api_key:
        raise ValueError(
            "LLM_PROVIDER=gemini requires GEMINI_API_KEY, GOOGLE_API_KEY, "
            "or GOOGLE_GENERATIVE_AI_API_KEY."
        )
    stream_on = _stream_enabled("gemini")
    cache_key = f"gemini|{model_name}|{temperature}|{stream_on}"
    if cache_key in _llm_cache:
        return _llm_cache[cache_key]
    llm = ChatGoogleGenerativeAI(
        model=model_name,
        temperature=temperature,
        google_api_key=api_key,
    )
    result = llm if stream_on else llm.bind(stream=False)
    _llm_cache[cache_key] = result
    logger.info("LLM provider=gemini model=%s", model_name)
    return result


def build_llm(model_name: str | None = None, temperature: float = 0.0) -> Any:
    """Return a cached LangChain chat model for the configured provider."""
    model = (model_name or "").strip() or default_llm_model()
    provider = resolve_llm_provider()
    if provider == "openai":
        return _build_openai_llm(model, temperature)
    if provider == "gemini":
        return _build_gemini_llm(model, temperature)
    return _build_ollama_llm(model, temperature)
