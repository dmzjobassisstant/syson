#!/usr/bin/env python3
"""
MCP Bridge Server — exposes SysON model operations as MCP tools.

Uses the official MCP Python SDK (FastMCP) so Hermes discovers tools via
the proper MCP JSON-RPC protocol over streamable HTTP.

Architecture:
    Hermes Agent  →  MCP Protocol (JSON-RPC/SSE)  →  This Bridge  →  PostgreSQL + GraphQL

Reads go to PostgreSQL (syson_head_elements) for speed and reliability.
Writes go to SysON GraphQL mutations.
"""

import os
import json
import time
import logging
import requests
import psycopg2
from psycopg2.extras import RealDictCursor
from mcp.server.fastmcp import FastMCP

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s: %(message)s")
logger = logging.getLogger(__name__)

# ── Configuration ──────────────────────────────────────────────────
SYSON_URL = os.environ.get("SYSON_BACKEND_URL", "http://localhost:8080")
SYSON_USER = os.environ.get("SYSON_USER", "admin")
SYSON_PASS = os.environ.get("SYSON_PASSWORD", "admin")

PG_HOST = os.environ.get("PG_HOST", "localhost")
PG_PORT = os.environ.get("PG_PORT", "5432")
PG_DB = os.environ.get("PG_DB", "syson")
PG_USER = os.environ.get("PG_USER", "syson")
PG_PASS = os.environ.get("PG_PASSWORD", "syson")

SCOOTER1_PROJECT_ID = "afa126b5-daa8-41f2-9b1e-bae1ecb0d64f"

# ── JWT Token Management ───────────────────────────────────────────
_jwt_token = None
_jwt_expires = 0

def get_jwt() -> str:
    """Login to SysON and cache the JWT token."""
    global _jwt_token, _jwt_expires
    if _jwt_token and time.time() < _jwt_expires - 60:
        return _jwt_token
    r = requests.post(
        f"{SYSON_URL}/api/auth/login",
        json={"email": SYSON_USER, "password": SYSON_PASS},
        timeout=30,
    )
    r.raise_for_status()
    _jwt_token = r.json()["token"]
    _jwt_expires = time.time() + 3600  # 1 hour
    logger.info("SysON JWT obtained successfully")
    return _jwt_token


def graphql(query: str, variables: dict = None) -> dict:
    """Execute a GraphQL query against SysON with auto-login."""
    token = get_jwt()
    r = requests.post(
        f"{SYSON_URL}/api/graphql",
        json={"query": query, "variables": variables or {}},
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
        timeout=120,
    )
    r.raise_for_status()
    return r.json()


def get_editing_context(project_id: str) -> str:
    """Resolve the editing context ID for a project."""
    data = graphql(
        'query($pid: ID!) { viewer { project(projectId: $pid) { currentEditingContext { id } } } }',
        {"pid": project_id},
    )
    ec = data.get("data", {}).get("viewer", {}).get("project", {}).get("currentEditingContext")
    return ec["id"] if ec else project_id


# ── PostgreSQL helpers ─────────────────────────────────────────────
def pg_query(sql: str, params: tuple = ()) -> list:
    """Execute a read-only SQL query against the SysON database."""
    conn = psycopg2.connect(
        host=PG_HOST, port=PG_PORT, dbname=PG_DB,
        user=PG_USER, password=PG_PASS,
    )
    try:
        with conn.cursor(cursor_factory=RealDictCursor) as cur:
            cur.execute(sql, params)
            return [dict(row) for row in cur.fetchall()]
    finally:
        conn.close()


def resolve_element_name(sirius_id: str, project_id: str) -> str:
    """Resolve a Sirius UUID to an element name via raw_object->>'id'."""
    rows = pg_query(
        """SELECT name FROM syson_head_elements
           WHERE project_id = %s AND is_deleted = false
             AND raw_object->>'id' = %s LIMIT 1""",
        (project_id, sirius_id),
    )
    return rows[0]["name"] if rows else sirius_id


# ── MCP Server ─────────────────────────────────────────────────────
mcp = FastMCP("syson-bridge", host="0.0.0.0", port=3001)


@mcp.tool()
def get_model_tree(project_id: str = SCOOTER1_PROJECT_ID) -> str:
    """Get the complete element tree for a SysON project.
    Returns all elements with their names, types, and parent-child relationships.
    Use this first to understand the model structure."""
    elements = pg_query(
        """SELECT stable_id, name, sysml_type, owner_stable_id, qualified_name,
                  body, attributes, raw_object
           FROM syson_head_elements
           WHERE project_id = %s AND is_deleted = false
           ORDER BY sysml_type, name""",
        (project_id,),
    )
    # Build parent → children map
    by_id = {e["stable_id"]: e for e in elements}
    children_map: dict[str, list] = {}
    roots = []
    for e in elements:
        owner = e.get("owner_stable_id")
        if owner and owner in by_id:
            children_map.setdefault(owner, []).append(e)
        else:
            roots.append(e)

    def _node(e, depth=0):
        name = e.get("name") or "(unnamed)"
        etype = e.get("sysml_type", "").replace("sysml:", "")
        result = f"{'  ' * depth}- [{etype}] {name}"
        body = e.get("body")
        if body:
            result += f"  — \"{body[:100]}\""
        for child in children_map.get(e["stable_id"], []):
            result += "\n" + _node(child, depth + 1)
        return result

    tree_text = "\n".join(_node(r) for r in roots if r.get("sysml_type") in ("sysml:Package", "sysml:Namespace") or not r.get("owner_stable_id"))
    summary = {}
    for e in elements:
        t = e.get("sysml_type", "unknown")
        summary[t] = summary.get(t, 0) + 1
    summary_text = "\n".join(f"  {k}: {v}" for k, v in sorted(summary.items(), key=lambda x: -x[1]))

    return f"Scooter1 Model — {len(elements)} elements\n\nElement type summary:\n{summary_text}\n\nModel Tree:\n{tree_text}"


@mcp.tool()
def search_elements(project_id: str = SCOOTER1_PROJECT_ID, query: str = "", element_type: str = "") -> str:
    """Search elements by name and/or SysML type.
    query: case-insensitive substring to match against element name
    element_type: filter by type (e.g. 'RequirementUsage', 'PartUsage', 'Dependency', 'Package')
    Returns matching elements with their IDs and types."""
    conditions = ["project_id = %s", "is_deleted = false"]
    params: list = [project_id]

    if query:
        conditions.append("LOWER(name) LIKE %s")
        params.append(f"%{query.lower()}%")
    if element_type:
        if not element_type.startswith("sysml:"):
            element_type = f"sysml:{element_type}"
        conditions.append("sysml_type = %s")
        params.append(element_type)

    where = " AND ".join(conditions)
    results = pg_query(
        f"""SELECT stable_id, name, sysml_type, body, qualified_name,
                  raw_object->>'id' as sirius_id
            FROM syson_head_elements
            WHERE {where}
            ORDER BY name LIMIT 100""",
        tuple(params),
    )
    if not results:
        return f"No elements found matching query='{query}' type='{element_type}'"

    lines = [f"Found {len(results)} elements:"]
    for r in results:
        etype = r["sysml_type"].replace("sysml:", "")
        body = f" — \"{r['body']}\"" if r.get("body") else ""
        lines.append(f"  [{etype}] {r['name']}{body}  (sirius_id={r['sirius_id']})")
    return "\n".join(lines)


@mcp.tool()
def get_element_details(project_id: str = SCOOTER1_PROJECT_ID, element_id: str = "") -> str:
    """Get full details of a single element including all attributes and raw data.
    element_id can be either a stable_id or a sirius object UUID."""
    results = pg_query(
        """SELECT * FROM syson_head_elements
           WHERE project_id = %s AND is_deleted = false
             AND (stable_id = %s OR raw_object->>'id' = %s)
           LIMIT 1""",
        (project_id, element_id, element_id),
    )
    if not results:
        return f"Element '{element_id}' not found in project {project_id}"
    e = results[0]
    return json.dumps(e, indent=2, default=str)


@mcp.tool()
def get_element_history(project_id: str = SCOOTER1_PROJECT_ID, element_id: str = "") -> str:
    """Get version history for a specific element — all changes and commits.
    element_id can be stable_id or sirius UUID."""
    token = get_jwt()
    r = requests.get(
        f"{SYSON_URL}/api/v1/user/projects/{project_id}/elements/{element_id}/history",
        headers={"Authorization": f"Bearer {token}"},
        timeout=60,
    )
    if r.status_code == 200:
        return json.dumps(r.json(), indent=2)
    # Fallback to commit history from DB
    commits = pg_query(
        """SELECT c.commit_id, c.timestamp, c.message, ch.change_type,
                  ch.field_name, ch.old_value, ch.new_value
           FROM syson_changes ch
           JOIN syson_commits c ON c.commit_id = ch.commit_id
           WHERE ch.project_id = %s AND ch.element_stable_id = %s
           ORDER BY c.timestamp DESC LIMIT 50""",
        (project_id, element_id),
    )
    if not commits:
        return f"No history found for element '{element_id}'"
    return json.dumps(commits, indent=2, default=str)


@mcp.tool()
def get_traceability_matrix(project_id: str = SCOOTER1_PROJECT_ID) -> str:
    """Pull full traceability data: which requirements trace to which parts via Dependency relationships.
    Returns a matrix showing requirement→part connections with names resolved.
    Also includes state machine transitions."""
    deps = pg_query(
        """SELECT d.attributes->'client'->0 as client_id,
                  d.attributes->'supplier'->0 as supplier_id
           FROM syson_head_elements d
           WHERE d.project_id = %s AND d.is_deleted = false
             AND d.sysml_type = 'sysml:Dependency'""",
        (project_id,),
    )
    # Resolve names for all client/supplier IDs
    links = []
    for dep in deps:
        cid = dep["client_id"]
        sid = dep["supplier_id"]
        cname = resolve_element_name(cid, project_id) if cid else "?"
        sname = resolve_element_name(sid, project_id) if sid else "?"
        links.append({"source": cname, "target": sname, "source_id": cid, "target_id": sid})

    # Get all requirements and parts for coverage analysis
    reqs = pg_query(
        """SELECT name, stable_id FROM syson_head_elements
           WHERE project_id = %s AND is_deleted = false
             AND sysml_type = 'sysml:RequirementUsage' ORDER BY name""",
        (project_id,),
    )
    parts = pg_query(
        """SELECT name, stable_id FROM syson_head_elements
           WHERE project_id = %s AND is_deleted = false
             AND sysml_type IN ('sysml:PartUsage', 'sysml:PartDefinition') ORDER BY name""",
        (project_id,),
    )

    # Determine which requirements have trace links
    req_names = {r["name"] for r in reqs}
    linked_names = {l["source"] for l in links} | {l["target"] for l in links}
    covered_reqs = req_names & linked_names
    uncovered = req_names - linked_names

    lines = [
        f"TRACEABILITY MATRIX — {len(links)} dependency links found",
        f"Requirements: {len(reqs)} ({len(covered_reqs)} traced, {len(uncovered)} untraced)",
        f"Parts: {len(parts)}",
        "",
        "Requirement → Part Dependencies:",
    ]
    for l in links:
        lines.append(f"  {l['source']}  →  {l['target']}")
    if uncovered:
        lines.append(f"\nUntraced Requirements ({len(uncovered)}):")
        for u in sorted(uncovered):
            lines.append(f"  ⚠ {u}")

    return "\n".join(lines)


@mcp.tool()
def get_requirements_coverage(project_id: str = SCOOTER1_PROJECT_ID) -> str:
    """Get requirements coverage report: total requirements, which are satisfied by parts, coverage gaps."""
    reqs = pg_query(
        """SELECT name, body, stable_id FROM syson_head_elements
           WHERE project_id = %s AND is_deleted = false
             AND sysml_type = 'sysml:RequirementUsage' ORDER BY name""",
        (project_id,),
    )
    deps = pg_query(
        """SELECT d.attributes->'client'->0 as client_id,
                  d.attributes->'supplier'->0 as supplier_id
           FROM syson_head_elements d
           WHERE d.project_id = %s AND d.is_deleted = false
             AND d.sysml_type = 'sysml:Dependency'""",
        (project_id,),
    )
    # Build requirement → linked parts map
    req_links: dict[str, list[str]] = {}
    for d in deps:
        cname = resolve_element_name(d["client_id"], project_id) if d["client_id"] else ""
        sname = resolve_element_name(d["supplier_id"], project_id) if d["supplier_id"] else ""
        req_links.setdefault(cname, []).append(sname)
        req_links.setdefault(sname, []).append(cname)

    total = len(reqs)
    covered = 0
    lines = [f"REQUIREMENTS COVERAGE REPORT — {total} requirements\n"]
    for r in reqs:
        name = r["name"]
        linked = req_links.get(name, [])
        body = r.get("body") or ""
        if linked:
            covered += 1
            lines.append(f"  ✅ {name}")
            if body:
                lines.append(f"      \"{body[:120]}\"")
            lines.append(f"      → Traced to: {', '.join(linked)}")
        else:
            lines.append(f"  ❌ {name} — NOT TRACED")
            if body:
                lines.append(f"      \"{body[:120]}\"")

    lines.append(f"\nCoverage: {covered}/{total} ({100*covered//total if total else 0}%)")
    return "\n".join(lines)


@mcp.tool()
def import_sysml_text(project_id: str, sysml_text: str) -> str:
    """Import valid SysML v2 textual notation into the project.
    This creates or modifies elements using SysML v2 syntax.
    Example: 'part def NewPart { attribute mass : Real; }'
    project_id: the SysON project UUID
    sysml_text: valid SysML v2 textual notation"""
    ec = get_editing_context(project_id)
    import uuid as uuidlib
    mutation = """
    mutation($input: InsertTextualSysMLInput!) {
      insertTextualSysML(input: $input) {
        __typename
        ... on SuccessPayload { id }
        ... on ErrorPayload { message }
      }
    }
    """
    variables = {
        "input": {
            "id": str(uuidlib.uuid4()),
            "editingContextId": ec,
            "objectId": "ROOT_ELEMENT_ID",
            "textualContent": sysml_text,
        }
    }
    result = graphql(mutation, variables)
    return json.dumps(result, indent=2)


@mcp.tool()
def create_diagram(project_id: str, diagram_name: str, diagram_type: str = "General View") -> str:
    """Create a new diagram (representation) in the project.
    diagram_type options: 'General View', 'State Transition View', 'Action Flow View', 'Interconnection View', 'Definition Diagram'
    Returns the new diagram's representation ID."""
    import uuid as uuidlib
    ec = get_editing_context(project_id)

    # Find a Package element to use as objectId (needs to be a real Package, not Parts/Requirements sub-package)
    # Package 1 is the root package — try it first, then any Package, then any Namespace
    root_pkg = pg_query(
        """SELECT raw_object->>'id' as sirius_id, name FROM syson_head_elements
           WHERE project_id = %s AND is_deleted = false
             AND sysml_type = 'sysml:Package'
             AND name IN ('Package 1', 'Package', 'Model', 'Root')
           ORDER BY name LIMIT 1""",
        (project_id,),
    )
    if not root_pkg:
        root_pkg = pg_query(
            """SELECT raw_object->>'id' as sirius_id, name FROM syson_head_elements
               WHERE project_id = %s AND is_deleted = false
                 AND sysml_type = 'sysml:Package'
               ORDER BY name LIMIT 1""",
            (project_id,),
        )
    object_id = root_pkg[0]["sirius_id"] if root_pkg else "ROOT_ELEMENT_ID"

    # Find the representation description ID for the requested type
    q = (
        f'query {{ viewer {{ editingContext(editingContextId:"{ec}") {{ '
        f'representationDescriptions(objectId:"{object_id}") {{ edges {{ node {{ id label }} }} }} '
        f'}} }} }}'
    )
    desc_result = graphql(q)
    edges = desc_result.get("data", {}).get("viewer", {}).get("editingContext", {}).get("representationDescriptions", {}).get("edges", [])
    desc_id = None
    for edge in edges:
        node = edge.get("node", {})
        if diagram_type.lower() == node.get("label", "").lower():
            desc_id = node["id"]
            break
    if not desc_id and edges:
        # Fuzzy match
        for edge in edges:
            if diagram_type.lower().split()[0] in edge.get("node", {}).get("label", "").lower():
                desc_id = edge["node"]["id"]
                break
    if not desc_id and edges:
        desc_id = edges[0]["node"]["id"]

    if not desc_id:
        available = [e["node"]["label"] for e in edges]
        return f"No diagram type found matching '{diagram_type}'. Available types: {', '.join(available)}"

    # Create the diagram
    name_json = json.dumps(diagram_name)
    mutation = f"""mutation {{
      createRepresentation(input:{{
        id:"{uuidlib.uuid4()}",
        editingContextId:"{ec}",
        objectId:"{object_id}",
        representationDescriptionId:"{desc_id}",
        representationName:{name_json}
      }}) {{
        __typename
        ... on CreateRepresentationSuccessPayload {{ representation {{ id label }} }}
        ... on ErrorPayload {{ messages {{ body level }} }}
      }}
    }}"""
    result = _graphql_mutation(mutation)
    return json.dumps(result, indent=2)


@mcp.tool()
def get_diagrams(project_id: str = SCOOTER1_PROJECT_ID) -> str:
    """List all existing diagrams (representations) in the project with their IDs and types."""
    ec = get_editing_context(project_id)
    result = graphql(
        'query($ec: ID!) { viewer { editingContext(editingContextId: $ec) { representations { edges { node { id label description { id label } } } } } } }',
        {"ec": ec},
    )
    edges = result.get("data", {}).get("viewer", {}).get("editingContext", {}).get("representations", {}).get("edges", [])
    if not edges:
        return "No diagrams found in this project."
    lines = [f"Diagrams ({len(edges)}):"]
    for edge in edges:
        node = edge["node"]
        desc = node.get("description", {})
        lines.append(f"  - {node['label']} (id={node['id']}, type={desc.get('label', '?')})")
    return "\n".join(lines)


@mcp.tool()
def get_element_relationships(project_id: str = SCOOTER1_PROJECT_ID, element_name: str = "") -> str:
    """Get all relationships (dependencies, memberships, typings) for a named element.
    Shows what this element depends on and what depends on it."""
    if not element_name:
        return "Please provide an element_name to look up relationships."

    # Find the element
    elements = pg_query(
        """SELECT stable_id, name, sysml_type, raw_object->>'id' as sirius_id
           FROM syson_head_elements
           WHERE project_id = %s AND is_deleted = false AND name = %s""",
        (project_id, element_name),
    )
    if not elements:
        return f"Element '{element_name}' not found"
    elem = elements[0]
    sid = elem["sirius_id"]

    # Find all dependencies where this element is client or supplier
    deps = pg_query(
        """SELECT d.attributes->'client'->0 as client_id,
                  d.attributes->'supplier'->0 as supplier_id
           FROM syson_head_elements d
           WHERE d.project_id = %s AND d.is_deleted = false
             AND d.sysml_type = 'sysml:Dependency'
             AND (%s = d.attributes->'client'->0 OR %s = d.attributes->'supplier'->0)""",
        (project_id, sid, sid),
    )

    lines = [f"Relationships for '{element_name}' ({elem['sysml_type']}):"]
    for d in deps:
        cname = resolve_element_name(d["client_id"], project_id)
        sname = resolve_element_name(d["supplier_id"], project_id)
        if d["client_id"] == sid:
            lines.append(f"  → depends on: {sname}")
        else:
            lines.append(f"  ← depended on by: {cname}")

    # Also check FeatureTyping relationships
    typings = pg_query(
        """SELECT d.attributes->'client'->0 as client_id,
                  d.attributes->'supplier'->0 as supplier_id
           FROM syson_head_elements d
           WHERE d.project_id = %s AND d.is_deleted = false
             AND d.sysml_type = 'sysml:FeatureTyping'""",
        (project_id,),
    )
    for t in typings:
        cname = resolve_element_name(t["client_id"], project_id)
        sname = resolve_element_name(t["supplier_id"], project_id)
        if t["client_id"] == sid:
            lines.append(f"  → typed by: {sname}")
        elif t["supplier_id"] == sid:
            lines.append(f"  ← types: {cname}")

    return "\n".join(lines) if len(lines) > 1 else f"No relationships found for '{element_name}'"


# ═══════════════════════════════════════════════════════════════
# WRITE/MUTATION TOOLS (migrated from old agent executor.py)
# ═══════════════════════════════════════════════════════════════


def _graphql_mutation(mutation: str) -> dict:
    """Execute a GraphQL mutation and return a standardized result dict."""
    result = graphql(mutation)
    if result.get("errors"):
        return {"success": False, "error": result["errors"][0].get("message", "GraphQL error")}
    # Find the payload under data.*
    data = result.get("data", {})
    for key, payload in data.items():
        typename = payload.get("__typename", "")
        if "Success" in typename:
            return {"success": True, "details": payload}
        else:
            msgs = payload.get("messages", [])
            err = msgs[0].get("body", "Unknown error") if msgs else f"Operation returned {typename}"
            return {"success": False, "error": err}
    return {"success": False, "error": "No data in response"}


@mcp.tool()
def create_element(project_id: str, parent_id: str, element_type: str, name: str) -> str:
    """Create a new child element under a parent element.
    parent_id: the Sirius object UUID of the parent (find via get_model_tree or search_elements)
    element_type: SysML type without prefix, e.g. 'PartUsage', 'Package', 'RequirementUsage', 'AttributeUsage'
    name: the name for the new element
    Returns success status with the new element reference."""
    import uuid as uuidlib
    if not element_type.startswith("sysml:"):
        element_type = f"sysml:{element_type}"
    ec = get_editing_context(project_id)
    name_json = json.dumps(name) if name else "null"
    mutation = f"""mutation {{
      addChildElement(input:{{
        id:"{uuidlib.uuid4()}",
        editingContextId:"{ec}",
        parentElementId:"{parent_id}",
        elementType:"{element_type}",
        name:{name_json}
      }}) {{
        __typename
        ... on SuccessPayload {{ id }}
        ... on ErrorPayload {{ messages {{ body level }} }}
      }}
    }}"""
    result = _graphql_mutation(mutation)
    return json.dumps(result, indent=2)


@mcp.tool()
def update_element(project_id: str, element_id: str, new_label: str = "", new_body: str = "") -> str:
    """Update an existing element's label (name) and/or body text.
    element_id: Sirius object UUID of the element to update
    new_label: new name for the element (optional)
    new_body: new documentation/body text (optional)
    For requirement text updates: find the AttributeUsage:'text' child and update THAT element."""
    import uuid as uuidlib
    ec = get_editing_context(project_id)
    fields = []
    if new_label:
        fields.append(f"newLabel:{json.dumps(new_label)}")
    if new_body:
        fields.append(f"newBody:{json.dumps(new_body)}")
    if not fields:
        return json.dumps({"success": False, "error": "No fields to update — provide new_label and/or new_body"})
    fields_str = "," + ",".join(fields)
    mutation = f"""mutation {{
      updateElement(input:{{
        id:"{uuidlib.uuid4()}",
        editingContextId:"{ec}",
        elementId:"{element_id}"{fields_str}
      }}) {{
        __typename
        ... on SuccessPayload {{ id }}
        ... on ErrorPayload {{ messages {{ body level }} }}
      }}
    }}"""
    result = _graphql_mutation(mutation)
    return json.dumps(result, indent=2)


@mcp.tool()
def delete_element(project_id: str, element_id: str) -> str:
    """Delete an element. DESTRUCTIVE — confirm with the user before calling.
    element_id: Sirius object UUID of the element to delete"""
    import uuid as uuidlib
    ec = get_editing_context(project_id)
    mutation = f"""mutation {{
      deleteElement(input:{{
        id:"{uuidlib.uuid4()}",
        editingContextId:"{ec}",
        elementId:"{element_id}"
      }}) {{
        __typename
        ... on SuccessPayload {{ id }}
        ... on ErrorPayload {{ messages {{ body level }} }}
      }}
    }}"""
    result = _graphql_mutation(mutation)
    return json.dumps(result, indent=2)


@mcp.tool()
def manage_relationship(project_id: str, relationship_type: str, source_element_id: str,
                        target_element_ids: str, operation: str = "ADD") -> str:
    """Create or remove a relationship between elements.
    relationship_type: 'Dependency', 'Subclassification', or 'Specialization'
    source_element_id: Sirius UUID of the source (client) element
    target_element_ids: comma-separated Sirius UUIDs of target (supplier) elements
    operation: 'ADD' to create, 'REMOVE' to delete"""
    import uuid as uuidlib
    ec = get_editing_context(project_id)
    targets = json.dumps([t.strip() for t in target_element_ids.split(",") if t.strip()])
    mutation = f"""mutation {{
      manageRelationship(input:{{
        id:"{uuidlib.uuid4()}",
        editingContextId:"{ec}",
        relationshipType:"{relationship_type}",
        sourceElementId:"{source_element_id}",
        targetElementIds:{targets},
        action:"{operation}"
      }}) {{
        __typename
        ... on SuccessPayload {{ messages {{ body level }} }}
        ... on ErrorPayload {{ messages {{ body level }} }}
      }}
    }}"""
    result = _graphql_mutation(mutation)
    return json.dumps(result, indent=2)


@mcp.tool()
def populate_diagram(project_id: str, diagram_id: str, element_ids: str) -> str:
    """Place elements onto an existing diagram.
    diagram_id: the representation ID of the diagram (from create_diagram or get_diagrams)
    element_ids: comma-separated Sirius UUIDs of elements to place"""
    import uuid as uuidlib
    ec = get_editing_context(project_id)
    ids = json.dumps([t.strip() for t in element_ids.split(",") if t.strip()])
    mutation = f"""mutation {{
      dropOnDiagram(input:{{
        id:"{uuidlib.uuid4()}",
        editingContextId:"{ec}",
        representationId:"{diagram_id}",
        objectIds:{ids},
        startingPositionX:200.0,
        startingPositionY:150.0
      }}) {{
        __typename
        ... on DropOnDiagramSuccessPayload {{ __typename }}
        ... on ErrorPayload {{ messages {{ body level }} }}
      }}
    }}"""
    result = _graphql_mutation(mutation)
    return json.dumps(result, indent=2)


@mcp.tool()
def layout_diagram(project_id: str, diagram_id: str) -> str:
    """Auto-layout/arrange all elements on a diagram.
    diagram_id: the representation ID of the diagram to layout"""
    import uuid as uuidlib
    ec = get_editing_context(project_id)
    mutation = f"""mutation {{
      arrangeAll(input:{{
        id:"{uuidlib.uuid4()}",
        editingContextId:"{ec}",
        representationId:"{diagram_id}"
      }}) {{
        __typename
        ... on SuccessPayload {{ messages {{ body level }} }}
        ... on ErrorPayload {{ messages {{ body level }} }}
      }}
    }}"""
    result = _graphql_mutation(mutation)
    return json.dumps(result, indent=2)


if __name__ == "__main__":
    logger.info(f"MCP Bridge starting on :3001 — SysON at {SYSON_URL}, PG at {PG_HOST}:{PG_PORT}")
    mcp.run(transport="streamable-http")
