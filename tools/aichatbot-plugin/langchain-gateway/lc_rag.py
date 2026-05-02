# Copyright (C) 2017-2019 Dremio Corporation
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.

"""PDF ingestion and FAISS + Ollama embeddings (RAG tool for the agent)."""

from __future__ import annotations

import asyncio
import hashlib
from pathlib import Path
from typing import Any

from langchain_community.document_loaders import PyPDFLoader
from langchain_community.vectorstores import FAISS
from langchain_core.tools import tool
from langchain_ollama import OllamaEmbeddings
from langchain_text_splitters import RecursiveCharacterTextSplitter

from lc_config import env


class RagIndexManager:
    """Per-tenant FAISS index on disk under GATEWAY_RAG_DIR."""

    def __init__(self, rag_root: str, ollama_base: str, embed_model: str) -> None:
        self._root = Path(rag_root)
        self._root.mkdir(parents=True, exist_ok=True)
        self._embeddings = OllamaEmbeddings(base_url=ollama_base, model=embed_model)
        self._splitter = RecursiveCharacterTextSplitter(chunk_size=1200, chunk_overlap=200)

    def _tenant_dir(self, tenant_id: str) -> Path:
        h = hashlib.sha256(tenant_id.encode()).hexdigest()
        return self._root / h

    def index_dir(self, tenant_id: str) -> Path:
        return self._tenant_dir(tenant_id) / "faiss_index"

    def has_index(self, tenant_id: str) -> bool:
        p = self.index_dir(tenant_id)
        return p.exists()

    def total_chunks(self, tenant_id: str) -> int:
        """Total chunk count from sidecar meta if present."""
        meta = self._tenant_dir(tenant_id) / "meta.txt"
        if not meta.exists():
            return 0
        try:
            return int(meta.read_text().strip())
        except Exception:
            return 0

    def ingest_pdf_path(self, tenant_id: str, pdf_path: Path) -> int:
        loader = PyPDFLoader(str(pdf_path))
        docs = loader.load()
        chunks = self._splitter.split_documents(docs)
        if not chunks:
            return 0
        vs_path = self.index_dir(tenant_id)
        if vs_path.exists():
            store = FAISS.load_local(
                str(vs_path),
                self._embeddings,
                allow_dangerous_deserialization=True,
            )
            if chunks:
                store.add_documents(chunks)
            store.save_local(str(vs_path))
        elif chunks:
            store = FAISS.from_documents(chunks, self._embeddings)
            vs_path.parent.mkdir(parents=True, exist_ok=True)
            store.save_local(str(vs_path))
        meta_f = self._tenant_dir(tenant_id) / "meta.txt"
        try:
            prev = int(meta_f.read_text().strip()) if meta_f.exists() else 0
        except Exception:
            prev = 0
        meta_f.write_text(str(prev + len(chunks)))
        return len(chunks)

    def search(self, tenant_id: str, query: str, k: int = 4) -> str:
        vs_path = self.index_dir(tenant_id)
        if not vs_path.exists():
            return "No PDF documents indexed for this session/user. Upload a PDF via POST /gateway/rag/upload first."
        store = FAISS.load_local(
            str(vs_path),
            self._embeddings,
            allow_dangerous_deserialization=True,
        )
        retriever = store.as_retriever(search_kwargs={"k": k})
        docs = retriever.invoke(query)
        if not docs:
            return "No matching passages found in indexed documents."
        parts = []
        for i, d in enumerate(docs, 1):
            src = d.metadata.get("source", "unknown")
            parts.append(f"[{i}] ({src})\n{d.page_content}")
        return "\n\n".join(parts)

    def clear(self, tenant_id: str) -> None:
        import shutil

        d = self._tenant_dir(tenant_id)
        if d.exists():
            shutil.rmtree(d, ignore_errors=True)

    def make_search_tool(self, tenant_id: str) -> Any:
        mgr = self

        @tool
        async def search_uploaded_documents(query: str) -> str:
            """Search indexed PDF documents for this chat tenant. Use for questions about uploaded PDFs and their content."""
            q = query.strip()
            if not q:
                return "Empty query."
            return await asyncio.to_thread(mgr.search, tenant_id, q)

        return search_uploaded_documents


def default_rag_manager(ollama_base: str) -> RagIndexManager | None:
    rag_dir = env("GATEWAY_RAG_DIR", "").strip()
    if not rag_dir:
        return None
    embed_model = env("GATEWAY_EMBED_MODEL", "nomic-embed-text")
    return RagIndexManager(rag_dir=rag_dir, ollama_base=ollama_base, embed_model=embed_model)
