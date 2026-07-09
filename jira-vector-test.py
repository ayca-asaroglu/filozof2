import base64
import json
import os
import sys
from typing import Any, Optional
from urllib.parse import quote_plus
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError


JIRA_BASE_URL = os.getenv("JIRA_BASE_URL", "https://atlas.fibabanka.local/jira")
JIRA_USERNAME = os.getenv("JIRA_USERNAME", "admin")
JIRA_PASSWORD = os.getenv("JIRA_PASSWORD", "xx")

ISSUE_KEY_DEFAULT = os.getenv("ISSUE_KEY_DEFAULT", "HC-40732")
JQL_QUERY_DEFAULT = os.getenv(
    "JQL_QUERY_DEFAULT",
    '"Channel Type (Custom)" = Filozof and issuetype in (Talep,Fikir) and status != "İptal" and reporter not in (FB007558,FB009604,FB005284,FB007724)',
)
VECTOR_BASE_URL = os.getenv("VECTOR_BASE_URL", "http://127.0.0.1:8000")
VECTOR_INDEX_PATH = os.getenv("VECTOR_INDEX_PATH", "/fibarprIdeaVectorIndex")
VECTOR_SEARCH_PATH = os.getenv("VECTOR_SEARCH_PATH", "/fibarprIdeaVectorSearch")
VECTOR_USE_BASIC_AUTH = os.getenv("VECTOR_USE_BASIC_AUTH", "false").lower() == "true"

IDEA_CF = {
    "amac": "customfield_19801",
    "problem": "customfield_19802",
    "cozumTipi": "customfield_19803",
    "mevcutDurum": "customfield_19804",
    "hedefKitle": "customfield_19805",
    "reporterBirim": os.getenv("REPORTER_BIRIM_CF", "customfield_10745"),
}

SEARCH_TOP_K = int(os.getenv("SEARCH_TOP_K", "5"))
DEFAULT_CATEGORY = os.getenv("DEFAULT_CATEGORY", "filozof")
JQL_PAGE_SIZE = int(os.getenv("JQL_PAGE_SIZE", "50"))
JQL_MAX_ISSUES = int(os.getenv("JQL_MAX_ISSUES", "500"))
RUN_SIMILARITY_SEARCH = os.getenv("RUN_SIMILARITY_SEARCH", "true").lower() == "true"
CONNECT_TIMEOUT_MS = int(os.getenv("CONNECT_TIMEOUT_MS", "15000"))
READ_TIMEOUT_MS = int(os.getenv("READ_TIMEOUT_MS", "60000"))


def as_trimmed(raw: Any) -> Optional[str]:
    if raw is None:
        return None
    s = str(raw).strip()
    return s if s else None


def pretty(value: Any) -> str:
    return json.dumps(value if value is not None else {}, ensure_ascii=False, indent=2)


def basic_auth_header() -> str:
    credentials = f"{JIRA_USERNAME}:{JIRA_PASSWORD}".encode("utf-8")
    encoded = base64.b64encode(credentials).decode("ascii")
    return f"Basic {encoded}"


def http_json(method: str, url: str, headers: dict[str, str], payload: Optional[dict[str, Any]]) -> dict[str, Any]:
    body_bytes = None
    if payload is not None:
        body_bytes = json.dumps(payload, ensure_ascii=False).encode("utf-8")

    req = Request(url=url, data=body_bytes, method=method)
    for k, v in headers.items():
        req.add_header(k, v)

    timeout_seconds = max(CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS) / 1000.0
    try:
        with urlopen(req, timeout=timeout_seconds) as resp:
            status = getattr(resp, "status", 200)
            response_text = resp.read().decode("utf-8", errors="replace")
    except HTTPError as e:
        status = e.code
        response_text = e.read().decode("utf-8", errors="replace") if e.fp is not None else ""
    except URLError as e:
        raise RuntimeError(f"HTTP request failed: {e}") from e

    parsed = None
    text = response_text.strip()
    if text:
        try:
            parsed = json.loads(text)
        except Exception:
            parsed = None

    result: dict[str, Any] = {"status": status, "raw": response_text}
    if isinstance(parsed, dict):
        result["body"] = parsed
    return result


def field_text(raw: Any) -> Optional[str]:
    if raw is None:
        return None

    if isinstance(raw, (str, int, float, bool)):
        return as_trimmed(raw)

    if isinstance(raw, dict):
        for key in ("value", "name", "displayName"):
            value = as_trimmed(raw.get(key))
            if value:
                return value
        return as_trimmed(raw)

    if isinstance(raw, list):
        values = [field_text(item) for item in raw]
        values = [item for item in values if item and item.strip()]
        return ", ".join(values) if values else None

    return as_trimmed(raw)


def collect_text_nodes(node: Any, out: list[str]) -> None:
    if node is None:
        return

    if isinstance(node, dict):
        text_val = node.get("text")
        text_trimmed = as_trimmed(text_val)
        if text_trimmed:
            out.append(text_trimmed)

        content = node.get("content")
        if isinstance(content, list):
            for child in content:
                collect_text_nodes(child, out)
        return

    if isinstance(node, list):
        for child in node:
            collect_text_nodes(child, out)


def normalize_description(description_field: Any) -> str:
    if description_field is None:
        return ""
    if isinstance(description_field, str):
        return description_field.strip()
    if isinstance(description_field, dict):
        texts: list[str] = []
        collect_text_nodes(description_field, texts)
        return "\n".join(texts).strip()
    return str(description_field).strip()


def jira_field_list() -> str:
    return ",".join(
        [
            "summary",
            "description",
            "status",
            "created",
            "updated",
            "issuetype",
            IDEA_CF["amac"],
            IDEA_CF["problem"],
            IDEA_CF["cozumTipi"],
            IDEA_CF["mevcutDurum"],
            IDEA_CF["hedefKitle"],
            IDEA_CF["reporterBirim"],
        ]
    )


def get_issue_from_jira(issue_key: str) -> dict[str, Any]:
    encoded_key = quote_plus(issue_key)
    field_list = jira_field_list()
    issue_url = f"{JIRA_BASE_URL}/rest/api/2/issue/{encoded_key}?fields={field_list}"

    response = http_json(
        "GET",
        issue_url,
        {
            "Accept": "application/json",
            "Authorization": basic_auth_header(),
        },
        None,
    )

    if response["status"] < 200 or response["status"] >= 300:
        snippet = (response.get("raw") or "")[:500]
        raise RuntimeError(f"Jira issue fetch failed HTTP {response['status']}: {snippet}")

    body = response.get("body")
    if not isinstance(body, dict) or not body.get("key"):
        raise RuntimeError("Jira issue response invalid")

    return body


def search_issues_page(jql: str, start_at: int, max_results: int) -> dict[str, Any]:
    encoded_jql = quote_plus(jql)
    encoded_fields = quote_plus(jira_field_list())
    url = (
        f"{JIRA_BASE_URL}/rest/api/2/search?"
        f"jql={encoded_jql}&startAt={start_at}&maxResults={max_results}&fields={encoded_fields}"
    )

    response = http_json(
        "GET",
        url,
        {
            "Accept": "application/json",
            "Authorization": basic_auth_header(),
        },
        None,
    )

    if response["status"] < 200 or response["status"] >= 300:
        snippet = (response.get("raw") or "")[:500]
        raise RuntimeError(f"Jira search failed HTTP {response['status']}: {snippet}")

    body = response.get("body")
    if not isinstance(body, dict):
        raise RuntimeError("Jira search response invalid")

    return body


def get_issues_by_jql(jql: str, page_size: int, max_issues: int) -> list[dict[str, Any]]:
    all_issues: list[dict[str, Any]] = []
    start_at = 0
    capped_page_size = max(1, min(page_size, 100))

    while len(all_issues) < max_issues:
        remaining = max_issues - len(all_issues)
        current_page_size = min(capped_page_size, remaining)
        page = search_issues_page(jql, start_at, current_page_size)
        issues = page.get("issues") if isinstance(page.get("issues"), list) else []

        if not issues:
            break

        all_issues.extend(issues)
        total = int(page.get("total", len(all_issues)))
        start_at += len(issues)
        if start_at >= total or len(issues) < current_page_size:
            break

    return all_issues


def build_idea_payload(issue: dict[str, Any]) -> dict[str, Any]:
    fields = issue.get("fields") if isinstance(issue.get("fields"), dict) else {}

    summary = as_trimmed(fields.get("summary")) or "Basliksiz Fikir"
    description = normalize_description(fields.get("description"))
    status_name = as_trimmed((fields.get("status") or {}).get("name")) if isinstance(fields.get("status"), dict) else None
    issue_type_name = as_trimmed((fields.get("issuetype") or {}).get("name")) if isinstance(fields.get("issuetype"), dict) else None

    amac_value = field_text(fields.get(IDEA_CF["amac"]))
    problem_value = field_text(fields.get(IDEA_CF["problem"]))
    cozum_tipi_value = field_text(fields.get(IDEA_CF["cozumTipi"]))
    mevcut_durum_value = field_text(fields.get(IDEA_CF["mevcutDurum"]))
    hedef_kitle_value = field_text(fields.get(IDEA_CF["hedefKitle"]))
    reporter_birim_value = field_text(fields.get(IDEA_CF["reporterBirim"]))

    return {
        "idea_id": as_trimmed(issue.get("key")),
        "ozet": summary,
        "amac": amac_value,
        "problem": problem_value or summary,
        "mevcut_durum": mevcut_durum_value or status_name,
        "aciklama": description,
        "cozum_tipi": cozum_tipi_value or issue_type_name,
        "hedef_kitle": hedef_kitle_value,
        "reporter_birim": reporter_birim_value,
        "kayit_statu": status_name,
        "kayit_no": as_trimmed(issue.get("key")),
        "category": DEFAULT_CATEGORY,
        "created_at": as_trimmed(fields.get("created")),
        "updated_at": as_trimmed(fields.get("updated")),
    }


def build_search_query(issue: dict[str, Any]) -> str:
    fields = issue.get("fields") if isinstance(issue.get("fields"), dict) else {}

    summary = as_trimmed(fields.get("summary"))
    description = normalize_description(fields.get("description"))
    amac_value = field_text(fields.get(IDEA_CF["amac"]))
    problem_value = field_text(fields.get(IDEA_CF["problem"]))
    cozum_tipi_value = field_text(fields.get(IDEA_CF["cozumTipi"]))
    mevcut_durum_value = field_text(fields.get(IDEA_CF["mevcutDurum"]))
    hedef_kitle_value = field_text(fields.get(IDEA_CF["hedefKitle"]))

    parts: list[str] = []
    if amac_value:
        parts.append(amac_value)
    if problem_value:
        parts.append(problem_value)
    if cozum_tipi_value:
        parts.append(cozum_tipi_value)
    if mevcut_durum_value:
        parts.append(mevcut_durum_value)
    if hedef_kitle_value:
        parts.append(hedef_kitle_value)
    if summary:
        parts.append(summary)
    if description:
        parts.append(description)

    merged = "\n".join(parts)
    return merged if merged else (as_trimmed(issue.get("key")) or "")


def call_vector_index(idea: dict[str, Any]) -> dict[str, Any]:
    url = f"{VECTOR_BASE_URL}{VECTOR_INDEX_PATH}"
    headers = {
        "Content-Type": "application/json; charset=UTF-8",
        "Accept": "application/json",
    }
    if VECTOR_USE_BASIC_AUTH:
        headers["Authorization"] = basic_auth_header()

    response = http_json("POST", url, headers, {"idea": idea})
    if response["status"] < 200 or response["status"] >= 300:
        snippet = (response.get("raw") or "")[:500]
        raise RuntimeError(f"Vector index failed HTTP {response['status']}: {snippet}")
    body = response.get("body")
    return body if isinstance(body, dict) else {"raw": response.get("raw")}


def call_vector_search(query: str, top_k: int) -> dict[str, Any]:
    url = f"{VECTOR_BASE_URL}{VECTOR_SEARCH_PATH}"
    headers = {
        "Content-Type": "application/json; charset=UTF-8",
        "Accept": "application/json",
    }
    if VECTOR_USE_BASIC_AUTH:
        headers["Authorization"] = basic_auth_header()

    response = http_json("POST", url, headers, {"query": query, "top_k": top_k})
    if response["status"] < 200 or response["status"] >= 300:
        snippet = (response.get("raw") or "")[:500]
        raise RuntimeError(f"Vector search failed HTTP {response['status']}: {snippet}")
    body = response.get("body")
    return body if isinstance(body, dict) else {"raw": response.get("raw")}


def resolve_input(argv: list[str]) -> dict[str, str]:
    jql_query = os.getenv("JQL_QUERY", JQL_QUERY_DEFAULT)
    issue_key = os.getenv("ISSUE_KEY", ISSUE_KEY_DEFAULT)

    if argv:
        first_arg = as_trimmed(argv[0])
        if first_arg:
            lower_arg = first_arg.lower()
            upper_arg = first_arg.upper()
            if lower_arg.startswith("jql="):
                jql_query = first_arg[4:].strip()
            elif lower_arg.startswith("--jql="):
                jql_query = first_arg[6:].strip()
            elif "=" in first_arg or " AND " in upper_arg or " OR " in upper_arg or " ORDER BY " in upper_arg:
                jql_query = first_arg
            else:
                issue_key = first_arg

    return {
        "issueKey": issue_key,
        "jqlQuery": jql_query,
    }


def run() -> int:
    input_data = resolve_input(sys.argv[1:])
    jql_query = as_trimmed(input_data.get("jqlQuery"))

    if jql_query:
        print("[INFO] running JQL mode")
        print(f"[INFO] jql: {jql_query}")
        print(f"[INFO] page size: {JQL_PAGE_SIZE}, max issues: {JQL_MAX_ISSUES}")

        issues = get_issues_by_jql(jql_query, JQL_PAGE_SIZE, JQL_MAX_ISSUES)
        print(f"[INFO] total issues fetched: {len(issues)}")

        success_keys: list[str] = []
        failures: dict[str, str] = {}

        for idx, issue in enumerate(issues, start=1):
            current_key = as_trimmed(issue.get("key")) or "UNKNOWN"
            print(f"[INFO] ({idx}/{len(issues)}) indexing {current_key}")
            try:
                idea = build_idea_payload(issue)
                call_vector_index(idea)
                success_keys.append(current_key)
            except Exception as ex:
                message = str(ex)
                failures[current_key] = message
                print(f"[ERROR] {current_key} indexing failed: {message[:300]}")

        print("\n===== JQL INDEX SUMMARY =====")
        print(
            pretty(
                {
                    "jql": jql_query,
                    "total": len(issues),
                    "indexed": len(success_keys),
                    "failed": len(failures),
                    "failures": failures,
                }
            )
        )
        return 0 if not failures else 2

    issue_key = as_trimmed(input_data.get("issueKey")) or ISSUE_KEY_DEFAULT
    print("[INFO] running single issue mode")
    print(f"[INFO] issue key: {issue_key}")

    issue = get_issue_from_jira(issue_key)
    idea = build_idea_payload(issue)
    search_query = build_search_query(issue)

    print(f"[INFO] issue fetched: {issue.get('key')}")
    print("[INFO] indexing vector data...")
    index_response = call_vector_index(idea)

    print("\n===== INDEX RESPONSE =====")
    print(pretty(index_response))

    if RUN_SIMILARITY_SEARCH:
        print("[INFO] running similarity search...")
        search_response = call_vector_search(search_query, SEARCH_TOP_K)
        print("\n===== SEARCH RESPONSE =====")
        print(pretty(search_response))

    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(run())
    except Exception as e:
        print(f"[FATAL] {e}")
        raise SystemExit(1)
