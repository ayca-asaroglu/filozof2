import json
import os
import re
import uuid
from datetime import datetime, timezone
from typing import Any

import psycopg
import requests
from fastapi import FastAPI
from fastapi.responses import JSONResponse
from dotenv import load_dotenv

load_dotenv()

app = FastAPI(title="Filozof Vector Service", version="1.0.0")

DB_HOST = os.getenv("DB_HOST", "opexaitools-db-tst")
DB_PORT = int(os.getenv("DB_PORT", "5000"))
DB_NAME = os.getenv("DB_NAME", "opexaitoolsTST")
DB_USER = os.getenv("DB_USER", "xx")
DB_PASSWORD = os.getenv("DB_PASSWORD", "xx")
SCHEMA_NAME = os.getenv("SCHEMA_NAME", "public")
TABLE_NAME = os.getenv("TABLE_NAME", "filozof_vectors")
CONF_TABLE_NAME = os.getenv("CONF_TABLE_NAME", "filozof")
VECTOR_DIM = int(os.getenv("VECTOR_DIM", "4096"))

EMBEDDING_URL = os.getenv("EMBEDDING_URL", "https://litellm.fibabanka.local/v1/embeddings")
EMBEDDING_MODEL = os.getenv("EMBEDDING_MODEL", "llama-embed-nemotron-8b")
AUTH_TOKEN = os.getenv("AUTH_TOKEN", "xx")
EMBEDDING_VERIFY_SSL = os.getenv("EMBEDDING_VERIFY_SSL", "true").strip().lower() not in {"0", "false", "no"}

IDENT_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


def as_trimmed(raw: Any) -> str | None:
    if raw is None:
        return None
    return str(raw).strip()


def first_non_blank(data: dict[str, Any], keys: list[str]) -> str | None:
    if not isinstance(data, dict):
        return None
    for key in keys:
        value = as_trimmed(data.get(key))
        if value:
            return value
    return None


def as_int(raw: Any, default_value: int, min_value: int, max_value: int) -> int:
    value = default_value
    try:
        if raw is not None:
            value = int(str(raw).strip())
    except Exception:
        value = default_value
    return max(min_value, min(max_value, value))


def q_ident(raw: str) -> str:
    value = as_trimmed(raw)
    if not value or not IDENT_RE.match(value):
        raise ValueError(f"Gecersiz SQL identifier: {raw}")
    return f'"{value}"'


def table_ref() -> str:
    return f"{q_ident(SCHEMA_NAME)}.{q_ident(TABLE_NAME)}"


def db_dsn() -> str:
    return f"host={DB_HOST} port={DB_PORT} dbname={DB_NAME} user={DB_USER} password={DB_PASSWORD}"


def get_conn() -> psycopg.Connection:
    return psycopg.connect(db_dsn())


def ensure_vector_table() -> None:
        ref = table_ref()
        schema = q_ident(SCHEMA_NAME)
        with get_conn() as conn:
                with conn.cursor() as cur:
                        cur.execute("CREATE EXTENSION IF NOT EXISTS vector")
                        cur.execute(f"CREATE SCHEMA IF NOT EXISTS {schema}")
                        cur.execute(
                                f"""
                                CREATE TABLE IF NOT EXISTS {ref} (
                                    idea_id TEXT PRIMARY KEY,
                                    ozet TEXT,
                                    problem TEXT,
                                    mevcut_durum TEXT,
                                    aciklama TEXT,
                                    cozum_tipi TEXT,
                                    kayit_statu TEXT,
                                    kayit_no TEXT,
                                    category TEXT,
                                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                    embedding VECTOR({VECTOR_DIM}),
                                    payload JSONB
                                )
                                """
                        )

                        # Existing deployments may already have this table with partial columns.
                        cur.execute(f"ALTER TABLE {ref} ADD COLUMN IF NOT EXISTS ozet TEXT")
                        cur.execute(f"ALTER TABLE {ref} ADD COLUMN IF NOT EXISTS problem TEXT")
                        cur.execute(f"ALTER TABLE {ref} ADD COLUMN IF NOT EXISTS mevcut_durum TEXT")
                        cur.execute(f"ALTER TABLE {ref} ADD COLUMN IF NOT EXISTS aciklama TEXT")
                        cur.execute(f"ALTER TABLE {ref} ADD COLUMN IF NOT EXISTS cozum_tipi TEXT")
                        cur.execute(f"ALTER TABLE {ref} ADD COLUMN IF NOT EXISTS kayit_statu TEXT")
                        cur.execute(f"ALTER TABLE {ref} ADD COLUMN IF NOT EXISTS kayit_no TEXT")
                        cur.execute(f"ALTER TABLE {ref} ADD COLUMN IF NOT EXISTS category TEXT")
                        cur.execute(f"ALTER TABLE {ref} ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()")
                        cur.execute(f"ALTER TABLE {ref} ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()")
                        cur.execute(f"ALTER TABLE {ref} ADD COLUMN IF NOT EXISTS payload JSONB")

                        cur.execute(
                                f"""
                                DO $$
                                BEGIN
                                    IF NOT EXISTS (
                                        SELECT 1
                                        FROM information_schema.columns
                                        WHERE table_schema = '{SCHEMA_NAME}'
                                            AND table_name = '{TABLE_NAME}'
                                            AND column_name = 'embedding'
                                    ) THEN
                                        EXECUTE 'ALTER TABLE {ref} ADD COLUMN embedding VECTOR({VECTOR_DIM})';
                                    END IF;
                                END
                                $$;
                                """,
                        )

                        cur.execute(f"CREATE INDEX IF NOT EXISTS idx_{TABLE_NAME}_category ON {ref} (category)")
                        if VECTOR_DIM <= 2000:
                            cur.execute(
                                f"CREATE INDEX IF NOT EXISTS idx_{TABLE_NAME}_embedding_ivfflat "
                                f"ON {ref} USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100)"
                            )
                conn.commit()


def to_vector_literal(values: list[float]) -> str:
    if not isinstance(values, list) or not values:
        raise ValueError("Embedding bos olamaz")
    if len(values) != VECTOR_DIM:
        raise ValueError(f"Embedding boyutu {VECTOR_DIM} olmali, gelen: {len(values)}")

    parts: list[str] = []
    for value in values:
        if value is None:
            raise ValueError("Embedding degeri gecersiz")
        f = float(value)
        if f != f or f in (float("inf"), float("-inf")):
            raise ValueError("Embedding degeri gecersiz")
        parts.append(f"{f:.15g}")

    return "[" + ",".join(parts) + "]"


def create_embedding(text: str) -> list[float]:
    trimmed = as_trimmed(text)
    if not trimmed:
        raise ValueError("Embedding icin text zorunlu")

    headers = {
        "Content-Type": "application/json; charset=UTF-8",
        "Authorization": f"Bearer {AUTH_TOKEN}",
    }
    payload = {"model": EMBEDDING_MODEL, "input": trimmed}

    response = requests.post(
        EMBEDDING_URL,
        headers=headers,
        json=payload,
        timeout=(15, 60),
        verify=EMBEDDING_VERIFY_SSL,
    )
    raw = response.text or ""
    if response.status_code < 200 or response.status_code >= 300:
        raise RuntimeError(f"Embedding HTTP {response.status_code}: {raw[:400]}")

    body = response.json() if raw.strip() else {}
    vectors = body.get("data")
    first = vectors[0] if isinstance(vectors, list) and vectors else None
    embedding = first.get("embedding") if isinstance(first, dict) else None
    if not isinstance(embedding, list) or not embedding:
        raise RuntimeError("Embedding response beklenen formatta degil")

    return [float(x) for x in embedding]


def normalize_idea_id(idea: dict[str, Any]) -> str:
    idea_id = as_trimmed(idea.get("idea_id")) or as_trimmed(idea.get("id"))
    return idea_id or str(uuid.uuid4())


def build_embedding_text(idea: dict[str, Any]) -> str:
    chunks: list[str] = []

    ozet = first_non_blank(idea, ["ozet", "summary", "fikrin_ozeti", "title"])
    problem = first_non_blank(idea, ["problem"])
    mevcut_durum = first_non_blank(idea, ["mevcut_durum", "mevcutDurum"])
    aciklama = first_non_blank(idea, ["aciklama", "description", "fikrin_aciklamasi"])
    cozum_tipi = first_non_blank(idea, ["cozum_tipi", "cozumTipi"])

    if ozet:
        chunks.append(f"Ozet: {ozet}")
    if problem:
        chunks.append(f"Problem: {problem}")
    if mevcut_durum:
        chunks.append(f"MevcutDurum: {mevcut_durum}")
    if aciklama:
        chunks.append(f"Aciklama: {aciklama}")
    if cozum_tipi:
        chunks.append(f"CozumTipi: {cozum_tipi}")

    merged = "\n".join(chunks)
    if not merged:
        raise ValueError("Fikir metni bos olamaz")
    return merged


def build_vector_row(idea: dict[str, Any], embedding: list[float]) -> dict[str, Any]:
    now_iso = datetime.now(timezone.utc).isoformat()
    return {
        "idea_id": normalize_idea_id(idea),
        "ozet": first_non_blank(idea, ["ozet", "summary", "fikrin_ozeti", "title"]) or "Basliksiz Fikir",
        "problem": first_non_blank(idea, ["problem"]),
        "mevcut_durum": first_non_blank(idea, ["mevcut_durum", "mevcutDurum"]),
        "aciklama": first_non_blank(idea, ["aciklama", "description", "fikrin_aciklamasi"]) or "",
        "cozum_tipi": first_non_blank(idea, ["cozum_tipi", "cozumTipi"]),
        "kayit_statu": first_non_blank(idea, ["kayit_statu", "kayitStatu", "status", "issue_status"]),
        "kayit_no": first_non_blank(idea, ["kayit_no", "kayitNo", "jira_key", "issue_key"]),
        "created_at": as_trimmed(idea.get("created_at")) or now_iso,
        "category": as_trimmed(idea.get("category")) or CONF_TABLE_NAME,
        "embedding": embedding,
        "payload": idea,
    }


def upsert_vector_row(row: dict[str, Any]) -> dict[str, Any]:
    ensure_vector_table()
    ref = table_ref()
    vector_literal = to_vector_literal(row["embedding"])
    payload_json = json.dumps(row["payload"], ensure_ascii=False)

    with get_conn() as conn:
        with conn.cursor() as cur:
            cur.execute(
                f"""
                INSERT INTO {ref} (
                  idea_id,
                  ozet,
                  problem,
                  mevcut_durum,
                  aciklama,
                  cozum_tipi,
                  kayit_statu,
                  kayit_no,
                  category,
                  created_at,
                  updated_at,
                  embedding,
                  payload
                ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, COALESCE(%s::timestamptz, NOW()), NOW(), %s::vector, %s::jsonb)
                ON CONFLICT (idea_id) DO UPDATE SET
                  ozet = EXCLUDED.ozet,
                  problem = EXCLUDED.problem,
                  mevcut_durum = EXCLUDED.mevcut_durum,
                  aciklama = EXCLUDED.aciklama,
                  cozum_tipi = EXCLUDED.cozum_tipi,
                  kayit_statu = EXCLUDED.kayit_statu,
                  kayit_no = EXCLUDED.kayit_no,
                  category = EXCLUDED.category,
                  created_at = EXCLUDED.created_at,
                  updated_at = NOW(),
                  embedding = EXCLUDED.embedding,
                  payload = EXCLUDED.payload
                """,
                [
                    row["idea_id"],
                    row["ozet"],
                    row["problem"],
                    row["mevcut_durum"],
                    row["aciklama"],
                    row["cozum_tipi"],
                    row["kayit_statu"],
                    row["kayit_no"],
                    row["category"],
                    row["created_at"],
                    vector_literal,
                    payload_json,
                ],
            )
        conn.commit()

    return {"upserted": True, "idea_id": row["idea_id"]}


def find_similar_ideas(query_text: str, top_k: int) -> list[dict[str, Any]]:
    ensure_vector_table()
    query_embedding = create_embedding(query_text)
    query_vector = to_vector_literal(query_embedding)
    ref = table_ref()

    with get_conn() as conn:
        with conn.cursor() as cur:
            cur.execute(
                f"""
                SELECT
                  idea_id,
                  ozet,
                  problem,
                  mevcut_durum,
                  aciklama,
                  cozum_tipi,
                  kayit_statu,
                  kayit_no,
                  created_at,
                  category,
                  (1 - (embedding <=> %s::vector)) AS score
                FROM {ref}
                ORDER BY embedding <=> %s::vector
                LIMIT %s
                """,
                [query_vector, query_vector, top_k],
            )
            rows = cur.fetchall()

    items: list[dict[str, Any]] = []
    for row in rows:
        items.append(
            {
                "idea_id": row[0],
                "ozet": row[1],
                "problem": row[2],
                "mevcut_durum": row[3],
                "aciklama": row[4],
                "cozum_tipi": row[5],
                "kayit_statu": row[6],
                "kayit_no": row[7],
                "created_at": row[8].isoformat() if row[8] else None,
                "category": row[9],
                "score": float(row[10]) if row[10] is not None else None,
            }
        )

    return items


@app.post("/fibarprIdeaVectorIndex")
def fibarpr_idea_vector_index(payload: dict[str, Any]) -> JSONResponse:
    try:
        idea = payload.get("idea") if isinstance(payload, dict) else None
        if not isinstance(idea, dict) or not idea:
            return JSONResponse(status_code=400, content={"ok": False, "error": "idea objesi zorunlu"})

        embedding_text = build_embedding_text(idea)
        embedding = create_embedding(embedding_text)
        row = build_vector_row(idea, embedding)
        upsert = upsert_vector_row(row)

        return JSONResponse(
            status_code=200,
            content={
                "ok": True,
                "message": "Fikir kaydi pgvector tablosuna yazildi",
                "idea_id": row["idea_id"],
                "result": upsert,
            },
        )
    except ValueError as e:
        return JSONResponse(status_code=400, content={"ok": False, "error": str(e)})
    except Exception as e:
        return JSONResponse(status_code=500, content={"ok": False, "error": "Beklenmeyen hata", "detail": str(e)})


@app.post("/fibarprIdeaVectorSearch")
def fibarpr_idea_vector_search(payload: dict[str, Any]) -> JSONResponse:
    try:
        query = as_trimmed(payload.get("query") if isinstance(payload, dict) else None)
        if not query:
            return JSONResponse(status_code=400, content={"ok": False, "error": "query zorunlu"})

        top_k = as_int(payload.get("top_k"), 5, 1, 20)
        items = find_similar_ideas(query, top_k)

        return JSONResponse(
            status_code=200,
            content={
                "ok": True,
                "query": query,
                "top_k": top_k,
                "count": len(items),
                "items": items,
            },
        )
    except ValueError as e:
        return JSONResponse(status_code=400, content={"ok": False, "error": str(e)})
    except Exception as e:
        return JSONResponse(status_code=500, content={"ok": False, "error": "Beklenmeyen hata", "detail": str(e)})


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("fastapi_vector_service:app", host="127.0.0.1", port=8000, reload=False)
