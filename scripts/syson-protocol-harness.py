#!/usr/bin/env python3
"""
SysON Sirius Web Protocol Test Harness (v3.2)
==============================================
Message building blocks + element discovery for formulating correct
create/update/delete messages against the Sirius Web GraphQL protocol.

COMPONENTS:
  - ElementCatalog: discovers all elements in a project with their IDs
    (XMI id, elementId) and hierarchical paths
  - MessageBuilder: high-level primitives for constructing mutations
    - insert_sysml()          — insert textual SysML v2 content
    - create_child()          — create a child element under a parent
    - discover_child_tools()  — query childCreationDescriptions for a kind
    - delete_tree_item()      — delete an element from the explorer tree
    - rename_tree_item()      — rename an element in the explorer tree
    - edit_textfield()        — update an attribute via form textfield
    - warm_editing_context()  — prepare the editing context
    - explain()               — annotated "message anatomy" view
                                (USER-provided vs AUTO-generated params)
  - ProjectManager: create, list, inspect projects
  - ModelVerifier: validate model state post-mutation

CLI COMMANDS:
  catalog       List elements in a project
  insert        Insert SysML v2 textual content (new project)
  verify        Verify model state
  tools         List available child creation tools for a parent type
  create-child  Create a child element under a parent
  --explain     (flag) Show message anatomy without executing

Protocol rules (from Sirius Web ICD):
  - editingContextId = semantic_data UUID (NOT project UUID)
  - objectId in insertTextualSysMLv2 = XMI id from document.content[0].id
  - childCreationDescriptions takes a `kind` argument in URL format:
    siriusComponents://?domain=sysml&entity=<EClassName>
    (NOT `containerId` as some old schemas show)
  - childCreationDescriptionId format: SysMLv2EditService-<EClassName>
  - treeItemId in delete/rename = explorer tree item ID (from tree widget, not DB)
  - representationId in delete/rename = tree representation description ID
    (discovered via explorerDescriptions[0].id, NOT ec_id)
  - deleteTreeItem/renameTreeItem return generic SuccessPayload, not typed payloads
  - GraphQL returns HTTP 200 with errors — always check body.errors
  - Always warm the editing context before mutations

Element ID types:
  - XMI id: document.content[*].id — used in insertTextualSysMLv2.objectId
  - elementId: document.content[*].data.elementId — stable SysML element ID
  - treeItemId: returned by explorer tree widget queries — used in delete/rename
  - representationId: explorerDescriptions[*].id — the tree description UUID
"""

import argparse
import base64
import hashlib
import hmac
import json
import os
import shlex
import ssl
import subprocess
import sys
import textwrap
import time
import urllib.error
import urllib.request
import uuid
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime
from typing import Optional

# ── CONFIG ────────────────────────────────────────────────────────────────
BASE_URL = os.environ.get("SYSON_BASE_URL", "http://localhost:8080")
GRAPHQL_URL = f"{BASE_URL}/api/graphql"
JWT_SECRET = os.environ.get("SYSON_JWT_SECRET", "changeme-please-override-in-production")
ADMIN_EMAIL = os.environ.get("SYSON_TEST_USER", "admin")
ADMIN_PASSWORD = os.environ.get(
    "SYSON_ADMIN_PASSWORD",
    os.environ.get("SYSON_BOOTSTRAP_PASSWORD", os.environ.get("SYSON_TEST_PASSWORD", "")),
)
TEMPLATE_ID = "sysmlv2-template"
DEBUG = os.environ.get("SYSON_DEBUG", "1") == "1"

# Sirius Web identifies element types by URL-format "kind" strings, e.g.:
#   sirisComponents://?domain=sysml&entity=Package
# The `childCreationDescriptions` query field on EditingContext takes this kind
# as its `kind` argument (NOT `containerId`, despite what some old schemas show).
SYSML_KIND_PREFIX = "siriusComponents://?domain=sysml&entity="


def eclass_to_kind(eclass: str) -> str:
    """Convert an eClass like 'sysml:PartDefinition' to a Sirius Web kind URL.

    Sirius Web identifies element types by a URL-format kind string, e.g.:
        sirisComponents://?domain=sysml&entity=Package

    This is the kind argument required by the childCreationDescriptions query.
    Handles both 'sysml::PartDefinition' and 'sysml:PartDefinition' forms.
    Returns "" for an empty input.
    """
    if not eclass:
        return ""
    # Strip namespace prefix if present (handle both :: and : forms)
    if "::" in eclass:
        entity = eclass.split("::")[-1]
    elif ":" in eclass:
        entity = eclass.rsplit(":", 1)[-1]
    else:
        entity = eclass
    return SYSML_KIND_PREFIX + entity


# ── SSL ────────────────────────────────────────────────────────────────────

def _ssl_context() -> ssl.SSLContext:
    """Build an SSL context that tolerates self-signed/dev certificates."""
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    return ctx


_SSL_CTX = _ssl_context()


# ── DATABASE HELPER ────────────────────────────────────────────────────────


def _db_query(sql: str, params: dict | None = None) -> str:
    """
    Run a parameterized PostgreSQL query safely.

    Uses psql variables (-v key=value) with :'key' literal quoting to prevent
    SQL injection. SQL is piped via stdin because psql does NOT expand
    variables in -c arguments.

    Args:
        sql: SQL with :'varname' placeholders for string literals.
        params: dict of {varname: value}. Values are passed via psql -v.

    Returns:
        stdout from psql (stripped).
    """
    cmd = [
        "sudo", "-u", "postgres",
        "psql", "-d", "syson", "-At",
    ]
    for key, val in (params or {}).items():
        cmd.extend(["-v", f"{key}={val}"])
    # Pipe SQL via stdin — psql expands :'var' references only from stdin/file, not -c
    r = subprocess.run(cmd, input=sql, capture_output=True, text=True, timeout=10)
    if r.returncode != 0:
        raise RuntimeError(f"psql error: {r.stderr.strip()}")
    return r.stdout.strip()


# ── DATA TYPES ─────────────────────────────────────────────────────────────


@dataclass
class ElementInfo:
    """Describes a single model element discovered from the document JSON."""

    path: str  # hierarchical path, e.g. "CoolingFanAssembly/motor"
    element_id: str  # stable SysML elementId (UUID)
    xmi_id: str  # XMI fragment id
    eclass: str  # e.g. "sysml:PartDefinition", "sysml:Package"
    name: str  # human-readable name
    depth: int  # nesting depth (0 = root)
    parent_xmi_id: Optional[str] = None
    attributes: dict[str, str] = field(default_factory=dict)


@dataclass
class ProjectContext:
    """Runtime context needed for mutations on a project."""

    project_id: str
    ec_id: str  # semantic_data UUID (= editingContextId)
    root_xmi: str  # root namespace XMI id
    root_element_id: str  # root namespace elementId
    document_id: str  # document UUID
    tree_representation_id: str = ""  # explorer tree representation ID (for delete/rename)
    elements: list[ElementInfo] = field(default_factory=list)

    @property
    def element_by_xmi(self) -> dict[str, ElementInfo]:
        return {e.xmi_id: e for e in self.elements}

    @property
    def element_by_stable(self) -> dict[str, ElementInfo]:
        return {e.element_id: e for e in self.elements}

    @property
    def element_by_name(self) -> dict[str, list[ElementInfo]]:
        d: dict[str, list[ElementInfo]] = defaultdict(list)
        for e in self.elements:
            d[e.name].append(e)
        return dict(d)


# ── AUTH ──────────────────────────────────────────────────────────────────


def make_jwt(
    user_id: str = "00000000-0000-0000-0000-000000000001", email: str = "admin"
) -> str:
    key = hashlib.sha256(JWT_SECRET.encode()).digest()
    now = int(time.time())

    def b64(o):
        return (
            base64.urlsafe_b64encode(
                json.dumps(o, separators=(",", ":")).encode()
            )
            .rstrip(b"=")
            .decode()
        )

    msg = (
        b64({"alg": "HS256", "typ": "JWT"})
        + "."
        + b64(
            {
                "tenantId": "00000000-0000-0000-0000-000000000001",
                "userId": user_id,
                "sub": email or ADMIN_EMAIL,
                "iat": now,
                "exp": now + 86400,
            }
        )
    )
    return (
        msg
        + "."
        + base64.urlsafe_b64encode(
            hmac.new(key, msg.encode(), hashlib.sha256).digest()
        )
        .rstrip(b"=")
        .decode()
    )


def login_via_api(email: Optional[str] = None, password: Optional[str] = None) -> str:
    """Login via REST API and return JWT token."""
    req = urllib.request.Request(
        f"{BASE_URL}/api/auth/login",
        data=json.dumps(
            {"email": email or ADMIN_EMAIL, "password": password or ADMIN_PASSWORD}
        ).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    return json.loads(urllib.request.urlopen(req, context=_SSL_CTX).read())["token"]


# ── GRAPHQL CLIENT ────────────────────────────────────────────────────────


class GQLClient:
    """Low-level GraphQL client with error-aware response handling."""

    def __init__(self, token: str):
        self.token = token
        self.metrics: list[dict] = []

    def log(self, msg: str, level: str = "INFO"):
        ts = datetime.now().strftime("%H:%M:%S.%f")[:12]
        line = f"[{level:7s}] [{ts}] {msg}"
        print(line)

    def execute(
        self,
        query: str,
        variables: dict | None = None,
        op_name: str | None = None,
    ) -> tuple[dict | None, list[dict] | None]:
        """Execute a GraphQL operation. Returns (data, errors)."""
        body = {"query": query}
        if variables:
            body["variables"] = variables
        if op_name:
            body["operationName"] = op_name

        if DEBUG:
            self.log(f"GQL → {op_name or query[:90].strip()}")

        req = urllib.request.Request(
            GRAPHQL_URL,
            data=json.dumps(body).encode(),
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.token}",
            },
            method="POST",
        )
        try:
            resp = json.loads(urllib.request.urlopen(req, context=_SSL_CTX).read())
        except Exception as e:
            self.metrics.append({"op": op_name, "ok": False, "error": str(e)})
            return None, [{"message": str(e)}]

        errors = resp.get("errors")
        data = resp.get("data")

        ok = errors is None
        self.metrics.append(
            {"op": op_name, "ok": ok, "errors": errors, "has_data": data is not None}
        )

        if errors and DEBUG:
            for err in errors:
                self.log(f"  GQL error: {err.get('message', str(err))}", "WARN")

        return data, errors


# ── PROJECT MANAGER ───────────────────────────────────────────────────────


class ProjectManager:
    """Create, list, and inspect SysON projects."""

    def __init__(self, client: GQLClient):
        self.gql = client

    def create(self, name: Optional[str] = None) -> Optional[str]:
        """Create a new project from the SysMLv2 template. Returns project_id."""
        mutation = """
        mutation CreateProject($input: CreateProjectFromTemplateInput!) {
          createProjectFromTemplate(input: $input) {
            __typename
            ... on CreateProjectFromTemplateSuccessPayload {
              project { id name }
              representationToOpen { id label }
            }
            ... on ErrorPayload { message }
          }
        }
        """
        variables = {
            "input": {
                "id": str(uuid.uuid4()),
                "templateId": TEMPLATE_ID,
            }
        }
        data, errors = self.gql.execute(mutation, variables, "CreateProject")
        if errors:
            self.gql.log(f"Create project: {errors}", "ERROR")
            return None
        result = (data or {}).get("createProjectFromTemplate", {})
        if result.get("__typename") == "CreateProjectFromTemplateSuccessPayload":
            pid = result["project"]["id"]
            pname = result["project"]["name"]
            self.gql.log(f"Project created: {pname} ({pid})", "OK")
            return pid
        self.gql.log(f"Create project failed: {result}", "ERROR")
        return None

    def list_projects(self) -> list[dict]:
        """List all projects via REST (more reliable than GraphQL for admin)."""
        try:
            req = urllib.request.Request(
                f"{BASE_URL}/api/v1/user/me/projects",
                headers={"Authorization": f"Bearer {self.gql.token}"},
                method="GET",
            )
            return json.loads(urllib.request.urlopen(req, context=_SSL_CTX).read())
        except Exception as e:
            self.gql.log(f"List projects failed: {e}", "ERROR")
            return []

    def get_context(self, project_id: str) -> Optional[ProjectContext]:
        """Get the full runtime context for a project (context IDs + elements).

        Validates that project_id is a UUID to prevent SQL injection.
        """
        # Validate project_id is a UUID (defense-in-depth against injection)
        try:
            uuid.UUID(project_id)
        except (ValueError, AttributeError):
            self.gql.log(f"Invalid project_id (not a UUID): {project_id}", "ERROR")
            return None

        try:
            row = _db_query(
                "select psd.semantic_data_id, "
                "d.id as doc_id, "
                "d.content::jsonb #>> '{content,0,id}' as root_xmi, "
                "d.content::jsonb #>> '{content,0,data,elementId}' as root_elid "
                "from document d "
                "join project_semantic_data psd on psd.semantic_data_id = d.semantic_data_id "
                "where psd.project_id = :'pid'::uuid and d.name like '%.sysml' limit 1;",
                {"pid": project_id},
            )
            if not row:
                self.gql.log(f"No document found for project {project_id}", "ERROR")
                return None

            parts = row.split("|")
            if len(parts) < 4:
                self.gql.log(f"Unexpected DB row format: {row[:100]}", "ERROR")
                return None

            ec_id, doc_id, root_xmi, root_elid = parts[0], parts[1], parts[2], parts[3]

            # Guard against empty root_xmi (fresh project without content yet)
            if not root_xmi:
                self.gql.log(
                    f"Root XMI id is empty — document may not have content yet. "
                    f"Wait for Sirius to initialize the project.",
                    "ERROR",
                )
                return None

            ctx = ProjectContext(
                project_id=project_id,
                ec_id=ec_id,
                root_xmi=root_xmi,
                root_element_id=root_elid,
                document_id=doc_id,
            )

            # Discover elements
            ctx.elements = ElementCatalog.discover(project_id)

            # Discover tree representation ID (needed for delete/rename mutations)
            ctx.tree_representation_id = self._discover_tree_rep_id(ctx.ec_id)

            self.gql.log(f"Context: ec={ec_id[:12]}… root_xmi={root_xmi[:12]}…", "OK")
            return ctx
        except Exception as e:
            self.gql.log(f"Get context failed: {e}", "ERROR")
            return None

    def _discover_tree_rep_id(self, ec_id: str) -> str:
        """Discover the explorer tree representation ID for this editing context.

        This is required by deleteTreeItem and renameTreeItem mutations.
        It comes from explorerDescriptions[0].id in the GraphQL response.
        The editing context must be warm for this to return results.
        """
        query, variables = MessageBuilder.warm_editing_context(ec_id)
        data, _ = self.gql.execute(query, variables, "WarmEC")
        ec = (data or {}).get("viewer", {}).get("editingContext", {})
        descs = ec.get("explorerDescriptions", [])
        if descs:
            return descs[0]["id"]
        return ""


# ── ELEMENT CATALOG ───────────────────────────────────────────────────────


class ElementCatalog:
    """Discover all elements in a project with their IDs and hierarchical paths."""

    @staticmethod
    def _extract_elements(
        parent: dict,
        path: str,
        depth: int,
        parent_xmi_id: Optional[str],
        elements: list[ElementInfo],
    ):
        """Recursively extract elements from document JSON content tree."""
        xmi_id = parent.get("id", "")
        eclass = parent.get("eClass", "")
        data = parent.get("data", {})
        element_id = data.get("elementId", "")
        name = data.get("name", "") or data.get("declaredName", "") or data.get("declaredShortName", "")

        if xmi_id and eclass:
            # Collect attributes
            attrs = {}
            for k, v in data.items():
                if k not in (
                    "elementId",
                    "ownedRelationship",
                    "ownedRelatedElement",
                    "ownedAttribute",
                    "ownedType",
                    "ownedMember",
                    "ownedUsage",
                ):
                    if isinstance(v, (str, int, float, bool)):
                        attrs[k] = str(v)

            elements.append(
                ElementInfo(
                    path=path or "(root)",
                    element_id=element_id,
                    xmi_id=xmi_id,
                    eclass=eclass,
                    name=name,
                    depth=depth,
                    parent_xmi_id=parent_xmi_id,
                    attributes=attrs,
                )
            )

        # Recurse into ownedRelationship → ownedRelatedElement
        for rel in data.get("ownedRelationship", []):
            if isinstance(rel, dict):
                for child in rel.get("data", {}).get("ownedRelatedElement", []):
                    if isinstance(child, dict):
                        child_name = (
                            child.get("data", {}).get("name", "")
                            or child.get("data", {}).get("declaredName", "")
                            or child.get("data", {}).get("declaredShortName", "")
                        )
                        child_path = f"{path}/{child_name}" if path else child_name
                        ElementCatalog._extract_elements(
                            child, child_path, depth + 1, xmi_id, elements
                        )

        # Recurse into direct ownedRelatedElement (sometimes not nested in relationship)
        for child in data.get("ownedRelatedElement", []):
            if isinstance(child, dict):
                child_name = (
                    child.get("data", {}).get("name", "")
                    or child.get("data", {}).get("declaredName", "")
                )
                child_path = f"{path}/{child_name}" if path else child_name
                ElementCatalog._extract_elements(
                    child, child_path, depth + 1, xmi_id, elements
                )

    @staticmethod
    def discover(project_id: str) -> list[ElementInfo]:
        """Discover all elements in a project. Returns flat list with paths.

        Uses parameterized query to prevent SQL injection.
        """
        try:
            uuid.UUID(project_id)
        except (ValueError, AttributeError):
            return []

        try:
            raw = _db_query(
                "select d.content::jsonb->'content' "
                "from document d "
                "join project_semantic_data psd on psd.semantic_data_id = d.semantic_data_id "
                "where psd.project_id = :'pid'::uuid and d.name like '%.sysml' limit 1;",
                {"pid": project_id},
            )
            if not raw:
                return []

            content_array = json.loads(raw)
            elements: list[ElementInfo] = []
            for root in content_array:
                ElementCatalog._extract_elements(root, "", 0, None, elements)
            return elements
        except Exception:
            return []

    @staticmethod
    def format_catalog(elements: list[ElementInfo], verbose: bool = False) -> str:
        """Format element catalog as a human-readable table."""
        if not elements:
            return "  (no elements found)"

        lines = []
        for e in sorted(elements, key=lambda x: (x.depth, x.path)):
            indent = "  " * e.depth
            type_short = e.eclass.replace("sysml:", "")
            name_str = e.name or "(unnamed)"
            xmi_short = e.xmi_id[:8] if e.xmi_id else "?"
            elid_short = e.element_id[:8] if e.element_id else "?"

            line = f"{indent}├─ {name_str}"
            if verbose:
                line += f"  [{type_short}]  xmi={xmi_short}…  elid={elid_short}…"
            lines.append(line)

        return "\n".join(lines)


# ── MESSAGE BUILDER ───────────────────────────────────────────────────────


class MessageBuilder:
    """
    High-level building blocks for constructing Sirius Web GraphQL mutations.

    Each method returns a (query_string, variables_dict) tuple ready for
    GQLClient.execute(). All mutations require an editingContextId, which
    is the semantic_data UUID, NOT the project UUID.

    IMPORTANT: deleteTreeItem and renameTreeItem require a representationId
    which is the explorer tree description ID (NOT ec_id). Discover it via
    ProjectManager._discover_tree_rep_id() or ProjectContext.tree_representation_id.
    """

    @staticmethod
    def insert_sysml(
        ec_id: str,
        root_xmi: str,
        sysml_text: str,
    ) -> tuple[str, dict]:
        """
        Insert textual SysML v2 content into the model.

        This is the primary way to create elements in bulk. The SysML text
        is parsed by the Sirius Web SysMLv2 parser and the resulting elements
        are added to the document under the root namespace.

        Args:
            ec_id: editingContextId (semantic_data UUID)
            root_xmi: XMI id of the root namespace (objectId)
            sysml_text: SysML v2 textual notation
        """
        mutation = """
        mutation InsertSysML($input: InsertTextualSysMLv2Input!) {
          insertTextualSysMLv2(input: $input) {
            __typename
            ... on SuccessPayload {
              id
              messages { level body }
            }
            ... on ErrorPayload {
              messages { level body }
              message
            }
          }
        }
        """
        variables = {
            "input": {
                "id": str(uuid.uuid4()),
                "editingContextId": ec_id,
                "objectId": root_xmi,
                "textualContent": sysml_text,
            }
        }
        return mutation, variables

    @staticmethod
    def warm_editing_context(ec_id: str) -> tuple[str, dict]:
        """Warm the editing context by querying it — required before mutations.

        Also returns explorerDescriptions which contain the tree representation ID.
        """
        query = """
        query WarmEC($ecId: ID!) {
          viewer {
            editingContext(editingContextId: $ecId) {
              id
              explorerDescriptions { id label }
            }
          }
        }
        """
        return query, {"ecId": ec_id}

    @staticmethod
    def delete_tree_item(
        ec_id: str,
        tree_item_id: str,
        representation_id: str,
    ) -> tuple[str, dict]:
        """
        Delete a tree item (element) from the explorer.

        Args:
            ec_id: editingContextId
            tree_item_id: the tree item ID (from explorer tree widget, NOT XMI id)
            representation_id: the explorer tree representation description ID
                (from explorerDescriptions[0].id, NOT ec_id)
        """
        mutation = """
        mutation DeleteItem($input: DeleteTreeItemInput!) {
          deleteTreeItem(input: $input) {
            __typename
            ... on SuccessPayload { id }
            ... on ErrorPayload { message }
          }
        }
        """
        variables = {
            "input": {
                "id": str(uuid.uuid4()),
                "editingContextId": ec_id,
                "representationId": representation_id,
                "treeItemId": tree_item_id,
            }
        }
        return mutation, variables

    @staticmethod
    def rename_tree_item(
        ec_id: str,
        tree_item_id: str,
        new_name: str,
        representation_id: str,
    ) -> tuple[str, dict]:
        """Rename a tree item in the explorer.

        Args:
            ec_id: editingContextId
            tree_item_id: the tree item ID (from explorer tree widget)
            new_name: the new label for the element
            representation_id: the explorer tree representation description ID
                (from explorerDescriptions[0].id, NOT ec_id)
        """
        mutation = """
        mutation RenameItem($input: RenameTreeItemInput!) {
          renameTreeItem(input: $input) {
            __typename
            ... on SuccessPayload { id }
            ... on ErrorPayload { message }
          }
        }
        """
        variables = {
            "input": {
                "id": str(uuid.uuid4()),
                "editingContextId": ec_id,
                "representationId": representation_id,
                "treeItemId": tree_item_id,
                "newLabel": new_name,
            }
        }
        return mutation, variables

    @staticmethod
    def create_child(
        ec_id: str,
        parent_object_id: str,
        child_description_id: str,
    ) -> tuple[str, dict]:
        """Create a child element under a parent.

        Args:
            ec_id: editingContextId (semantic_data UUID, auto-discovered from project)
            parent_object_id: the parent's object ID (XMI id from ElementCatalog)
            child_description_id: the child creation description ID, in format
                ``SysMLv2EditService-<EClassName>`` (e.g.
                ``SysMLv2EditService-PartDefinition``,
                ``SysMLv2EditService-Package``).

        To discover available ``childCreationDescriptionId`` values, query
        ``childCreationDescriptions(kind: $kind)`` on the editing context,
        where ``kind`` is the parent's Sirius Web kind URL built by
        :func:`eclass_to_kind` (e.g.
        ``siriusComponents://?domain=sysml&entity=Package`` — which yields
        ~84 available child types). See :meth:`discover_child_tools`.

        Returns a ``(mutation, variables)`` tuple. The variables dict has
        four leaf values whose roles are:

        - ``input.id`` — AUTO (client mutation id, random UUID)
        - ``input.editingContextId`` — AUTO (from project)
        - ``input.objectId`` — USER (parent XMI id)
        - ``input.childCreationDescriptionId`` — USER (tool id)
        """
        mutation = """
        mutation CreateChild($input: CreateChildInput!) {
          createChild(input: $input) {
            __typename
            ... on CreateChildSuccessPayload {
              object { id label kind }
            }
            ... on ErrorPayload { message }
          }
        }
        """
        variables = {
            "input": {
                "id": str(uuid.uuid4()),
                "editingContextId": ec_id,
                "objectId": parent_object_id,
                "childCreationDescriptionId": child_description_id,
            }
        }
        return mutation, variables

    @staticmethod
    def discover_child_tools(ec_id: str, parent_kind: str) -> tuple[str, dict]:
        """Build the query to discover available child creation tools.

        Args:
            ec_id: editingContextId (semantic_data UUID)
            parent_kind: the parent element's Sirius Web kind URL, e.g.
                ``siriusComponents://?domain=sysml&entity=Package``.
                Build it from an eClass with :func:`eclass_to_kind`.

        Returns a ``(query, variables)`` tuple. The response contains
        ``viewer.editingContext.childCreationDescriptions[*].{id,label}``
        where ``id`` is the ``childCreationDescriptionId`` to pass to
        :meth:`create_child` and ``label`` is a human-readable tool label
        (e.g. ``"Part Definition"``).
        """
        query = """
        query ChildTools($ecId: ID!, $kind: ID!) {
          viewer {
            editingContext(editingContextId: $ecId) {
              childCreationDescriptions(kind: $kind) {
                id
                label
              }
            }
          }
        }
        """
        return query, {"ecId": ec_id, "kind": parent_kind}

    @staticmethod
    def _annotate_json(obj: dict | list, param_roles: dict, indent: int = 2) -> list[str]:
        """Pretty-print ``obj`` as JSON with role annotations on leaf lines.

        Each leaf line is suffixed with ``  ← 🟢 AUTO`` or ``  ← 🔵 USER``
        based on ``param_roles[path]``. Paths use dotted notation for nested
        dicts (``input.id``) and bracket notation for lists (``input.tags[0]``).
        Internal helper used by :meth:`explain`.
        """
        lines: list[tuple[str, Optional[str]]] = []

        def emit_kv(key: str, value, level: int, path: str, comma: str) -> None:
            pad = " " * (indent * level)
            if isinstance(value, dict):
                if not value:
                    lines.append((pad + f'"{key}": {{}}' + comma, None))
                    return
                lines.append((pad + f'"{key}": {{', None))
                items = list(value.items())
                for i, (k, v) in enumerate(items):
                    c = "," if i < len(items) - 1 else ""
                    child_path = f"{path}.{k}" if path else k
                    emit_kv(k, v, level + 1, child_path, c)
                lines.append((pad + "}" + comma, None))
            elif isinstance(value, list):
                if not value:
                    lines.append((pad + f'"{key}": []' + comma, None))
                    return
                lines.append((pad + f'"{key}": [', None))
                for j, item in enumerate(value):
                    c = "," if j < len(value) - 1 else ""
                    ip = f"{path}[{j}]"
                    if isinstance(item, (dict, list)):
                        emit_anon(item, level + 1, ip, c)
                    else:
                        item_pad = " " * (indent * (level + 1))
                        role = param_roles.get(ip)
                        lines.append((item_pad + json.dumps(item) + c, role))
                lines.append((pad + "]" + comma, None))
            else:
                role = param_roles.get(path)
                lines.append((pad + f'"{key}": {json.dumps(value)}' + comma, role))

        def emit_anon(value, level: int, path: str, comma: str) -> None:
            pad = " " * (indent * level)
            if isinstance(value, dict):
                if not value:
                    lines.append((pad + "{}" + comma, None))
                    return
                lines.append((pad + "{", None))
                items = list(value.items())
                for i, (k, v) in enumerate(items):
                    c = "," if i < len(items) - 1 else ""
                    child_path = f"{path}.{k}"
                    emit_kv(k, v, level + 1, child_path, c)
                lines.append((pad + "}" + comma, None))
            elif isinstance(value, list):
                if not value:
                    lines.append((pad + "[]" + comma, None))
                    return
                lines.append((pad + "[", None))
                for j, item in enumerate(value):
                    c = "," if j < len(value) - 1 else ""
                    ip = f"{path}[{j}]"
                    emit_anon(item, level + 1, ip, c)
                lines.append((pad + "]" + comma, None))
            else:
                role = param_roles.get(path)
                lines.append((pad + json.dumps(value) + comma, role))

        # Top level
        if isinstance(obj, dict):
            if not obj:
                return ["{}"]
            lines.append(("{", None))
            items = list(obj.items())
            for i, (k, v) in enumerate(items):
                c = "," if i < len(items) - 1 else ""
                emit_kv(k, v, 1, k, c)
            lines.append(("}", None))
        elif isinstance(obj, list):
            if not obj:
                return ["[]"]
            lines.append(("[", None))
            for j, item in enumerate(obj):
                c = "," if j < len(obj) - 1 else ""
                emit_anon(item, 1, f"[{j}]", c)
            lines.append(("]", None))
        else:
            return [json.dumps(obj)]

        result = []
        for text, role in lines:
            if role:
                emoji = "🟢 AUTO" if role == "AUTO" else "🔵 USER"
                result.append(f"{text}  ← {emoji}")
            else:
                result.append(text)
        return result

    @staticmethod
    def explain(
        query: str,
        variables: dict,
        param_roles: Optional[dict] = None,
        op_name: Optional[str] = None,
    ) -> str:
        """Build an annotated "message anatomy" view of a GraphQL operation.

        Distinguishes USER-provided parameters (the caller must supply them)
        from AUTO-generated ones (the harness builds them), then shows the
        assembled query and the variables JSON with per-leaf annotations.

        Args:
            query: GraphQL query/mutation string.
            variables: variables dict that would be sent with the query.
            param_roles: dict mapping variable paths (e.g. ``"input.id"``) to
                ``"USER"`` or ``"AUTO"``. Paths not listed default to ``"USER"``.
            op_name: optional operation name for the box title. If omitted,
                extracted from the query text (e.g. ``mutation CreateChild(...)``
                → ``CreateChild``).

        Returns:
            A multi-line string with box-drawing characters, ready to print.
        """
        import re
        from unicodedata import east_asian_width

        param_roles = param_roles or {}

        title = op_name
        if not title:
            m = re.search(r"\b(?:mutation|query)\s+(\w+)", query)
            title = m.group(1) if m else "operation"

        W = 60  # inner content width (between the vertical bars)

        def disp_len(s: str) -> int:
            return sum(2 if east_asian_width(c) in "WF" else 1 for c in s)

        def truncate_to(s: str, width: int) -> str:
            if disp_len(s) <= width:
                return s
            out = ""
            cur = 0
            for c in s:
                cw = 2 if east_asian_width(c) in "WF" else 1
                if cur + cw + 3 > width:
                    return out + "..."
                out += c
                cur += cw
            return out

        def pad_to(s: str, width: int = W) -> str:
            s = truncate_to(s, width)
            return s + " " * (width - disp_len(s))

        def row(s: str = "") -> str:
            return "║ " + pad_to(s) + " ║"

        def bar(left: str, right: str, fill: str = "═") -> str:
            return left + fill * (W + 2) + right

        # Collect USER/AUTO params by walking variables
        user_params: list[tuple[str, str, str]] = []
        auto_params: list[tuple[str, str, str]] = []

        def _walk(obj, prefix: str = "") -> None:
            if isinstance(obj, dict):
                for k, v in obj.items():
                    path = f"{prefix}.{k}" if prefix else k
                    if isinstance(v, dict):
                        _walk(v, path)
                    elif isinstance(v, list):
                        for i, item in enumerate(v):
                            _walk(item, f"{path}[{i}]")
                    else:
                        role = param_roles.get(path, "USER")
                        val_str = json.dumps(v)
                        if len(val_str) > 30:
                            val_str = val_str[:27] + "..."
                        entry = (k, val_str, path)
                        if role == "AUTO":
                            auto_params.append(entry)
                        else:
                            user_params.append(entry)

        _walk(variables)

        annotated_lines = MessageBuilder._annotate_json(variables, param_roles)

        out: list[str] = []
        out.append(bar("╔", "╗"))
        out.append(row(f"MESSAGE ANATOMY: {title}"))
        out.append(bar("╠", "╣"))
        out.append(row())
        out.append(row("🔵 USER-PROVIDED (you must supply these):"))
        if user_params:
            for k, v, _ in user_params:
                out.append(row(f"   • {k:<22} = {v}"))
        else:
            out.append(row("   (none — all params are auto-generated)"))
        out.append(row())
        out.append(row("🟢 AUTO-GENERATED (harness builds these):"))
        if auto_params:
            for k, v, _ in auto_params:
                out.append(row(f"   • {k:<22} = {v}"))
        else:
            out.append(row("   (none)"))
        out.append(row())
        out.append(bar("╠", "╣"))
        out.append(row("ASSEMBLED OPERATION:"))
        # Frame the query in an inner box with right-aligned │ borders.
        # Available content width inside one row: W. The inner box uses
        # "│ " + content + " │" so content can be up to W - 4 display chars.
        q_lines = [q.rstrip() for q in query.strip("\n").splitlines()]
        content_cap = W - 6  # leave a little slack inside the row
        capped = [truncate_to(q, content_cap) for q in q_lines]
        max_len = max((disp_len(c) for c in capped), default=0)
        out.append(row("┌" + "─" * (max_len + 2) + "┐"))
        for c in capped:
            pad = " " * (max_len - disp_len(c))
            out.append(row(f"│ {c}{pad} │"))
        out.append(row("└" + "─" * (max_len + 2) + "┘"))
        out.append(row())
        out.append(row("VARIABLES:"))
        for vline in annotated_lines:
            out.append(row("  " + truncate_to(vline.rstrip(), W - 2)))
        out.append(bar("╚", "╝"))
        return "\n".join(out)

    @staticmethod
    def edit_textfield(
        ec_id: str,
        representation_id: str,
        textfield_id: str,
        new_value: str,
    ) -> tuple[str, dict]:
        """
        Edit a textfield (form property) in a representation.

        This is the way to update individual attribute values on elements
        via the Sirius Web form/properties representation.

        Args:
            ec_id: editingContextId
            representation_id: the form representation ID containing the textfield
            textfield_id: the textfield widget ID (from form descriptions)
            new_value: the new string value
        """
        mutation = """
        mutation EditTextfield($input: EditTextfieldInput!) {
          editTextfield(input: $input) {
            __typename
            ... on SuccessPayload { id }
            ... on ErrorPayload { message }
          }
        }
        """
        variables = {
            "input": {
                "id": str(uuid.uuid4()),
                "editingContextId": ec_id,
                "representationId": representation_id,
                "textfieldId": textfield_id,
                "newValue": new_value,
            }
        }
        return mutation, variables

    @staticmethod
    def query_explorer(ec_id: str) -> tuple[str, dict]:
        """Query the explorer tree to discover tree item IDs."""
        query = """
        query Explorer($ecId: ID!) {
          viewer {
            editingContext(editingContextId: $ecId) {
              id
              explorerDescriptions { id label }
            }
          }
        }
        """
        return query, {"ecId": ec_id}

    @staticmethod
    def get_explorer_tree_items(gql: GQLClient, ec_id: str) -> list[dict]:
        """Get explorer tree description items with their IDs.

        Note: this returns tree DESCRIPTIONS (the tree widgets available),
        not the individual tree NODES. To get individual nodes you need
        to subscribe to the tree fragment subscription.
        """
        query, variables = MessageBuilder.query_explorer(ec_id)
        data, errors = gql.execute(query, variables, "Explorer")
        if errors:
            return []
        ec = (data or {}).get("viewer", {}).get("editingContext", {})
        return ec.get("explorerDescriptions", [])

    @staticmethod
    def format_explorer_items(items: list[dict]) -> str:
        """Format explorer tree items for human consumption."""
        if not items:
            return "  (no items)"
        lines = []
        for item in items:
            lines.append(f"  ├─ {item['label']}  id={item['id'][:12]}…")
        return "\n".join(lines)


# ── MODEL VERIFIER ────────────────────────────────────────────────────────


class ModelVerifier:
    """Verify model state after mutations."""

    @staticmethod
    def document_contains(project_id: str, keyword: str) -> bool:
        """Check if any document in the project contains the given keyword.

        Uses parameterized query to prevent SQL injection.
        """
        try:
            uuid.UUID(project_id)
        except (ValueError, AttributeError):
            return False

        try:
            result = _db_query(
                "select count(*) > 0 from document d "
                "join project_semantic_data psd on psd.semantic_data_id = d.semantic_data_id "
                "where psd.project_id = :'pid'::uuid and d.content::text like :'kw';",
                {"pid": project_id, "kw": f"%{keyword}%"},
            )
            return result == "t"
        except Exception:
            return False

    @staticmethod
    def element_count(project_id: str) -> int:
        """Count top-level elements in the project's document.

        Uses parameterized query to prevent SQL injection.
        """
        try:
            uuid.UUID(project_id)
        except (ValueError, AttributeError):
            return 0

        try:
            result = _db_query(
                "select jsonb_array_length(d.content::jsonb->'content') "
                "from document d "
                "join project_semantic_data psd on psd.semantic_data_id = d.semantic_data_id "
                "where psd.project_id = :'pid'::uuid and d.name like '%.sysml' limit 1;",
                {"pid": project_id},
            )
            return int(result) if result.isdigit() else 0
        except Exception:
            return 0


# ── HIGH-LEVEL OPERATIONS ─────────────────────────────────────────────────


def run_insert_test(
    gql: GQLClient, pm: ProjectManager, sysml_text: str, name: str = "test"
) -> Optional[dict]:
    """Run a full insert test: create project → warm → insert → verify → return state."""

    print(f"\n{'='*70}")
    print(f"  INSERT TEST: {name}")
    print(f"{'='*70}")

    # 1. Create project
    project_id = pm.create()
    if not project_id:
        return None
    time.sleep(1)

    # 2. Get context
    ctx = pm.get_context(project_id)
    if not ctx:
        return None

    # 3. Warm editing context (with retry)
    query, variables = MessageBuilder.warm_editing_context(ctx.ec_id)
    ec = None
    for attempt in range(3):
        data, errors = gql.execute(query, variables, "WarmEC")
        if errors and attempt == 0:
            gql.log("Warm EC had warnings (retrying)", "WARN")
        ec = (data or {}).get("viewer", {}).get("editingContext")
        if ec:
            break
        time.sleep(2)
    if not ec:
        gql.log("Failed to warm editing context after 3 attempts", "ERROR")
        return None
    gql.log(f"Editing context warmed: {ec['id'][:12]}…", "OK")

    # 3b. Discover tree representation ID now that EC is warm
    if ec.get("explorerDescriptions"):
        ctx.tree_representation_id = ec["explorerDescriptions"][0]["id"]
        gql.log(f"Tree rep ID: {ctx.tree_representation_id[:40]}…", "OK")

    # 4. Insert SysML
    print(f"\n{'─'*70}")
    print(f"  Inserting SysML v2 content ({len(sysml_text)} chars)...")
    print(f"{'─'*70}")
    query, variables = MessageBuilder.insert_sysml(ctx.ec_id, ctx.root_xmi, sysml_text)
    data, errors = gql.execute(query, variables, "InsertSysML")
    if errors:
        gql.log("Insert had GraphQL errors", "ERROR")
        return None

    result = (data or {}).get("insertTextualSysMLv2", {})
    typename = result.get("__typename")
    for m in result.get("messages", []):
        gql.log(f"  {m['level']}: {m['body']}", "DEBUG")

    if typename != "SuccessPayload":
        gql.log(f"Insert failed: {result.get('message', typename)}", "ERROR")
        return None
    gql.log("Insert succeeded", "OK")

    # 5. Rediscover elements
    time.sleep(1)
    ctx.elements = ElementCatalog.discover(project_id)

    # 6. Summary
    print(f"\n{'='*70}")
    print(f"  ✅ INSERT COMPLETE — {len(ctx.elements)} elements discovered")
    print(f"{'='*70}")
    print(f"  Project:   {ctx.project_id}")
    print(f"  EC:        {ctx.ec_id[:16]}…")
    print(f"  Root XMI:  {ctx.root_xmi[:16]}…")
    print(f"  Tree Rep:  {ctx.tree_representation_id[:40]}…")
    print(f"  Diagram:   {BASE_URL}/?projectId={ctx.project_id}")

    # Save state
    state = {
        "project_id": ctx.project_id,
        "ec_id": ctx.ec_id,
        "root_xmi": ctx.root_xmi,
        "root_element_id": ctx.root_element_id,
        "document_id": ctx.document_id,
        "tree_representation_id": ctx.tree_representation_id,
        "element_count": len(ctx.elements),
        "base_url": BASE_URL,
    }
    state_path = "/tmp/syson_harness_state.json"
    with open(state_path, "w") as f:
        json.dump(state, f, indent=2)
    gql.log(f"State saved to {state_path}", "OK")

    return state


def catalog_command(args):
    """List elements in an existing project."""
    token = login_via_api() if ADMIN_PASSWORD else make_jwt()
    gql = GQLClient(token)
    pm = ProjectManager(gql)

    if args.project_id:
        project_id = args.project_id
    else:
        projects = pm.list_projects()
        if not projects:
            print("No projects found. Use --project-id to specify one.")
            return 1
        # Find the most recently "rich" project (with content)
        project_id = projects[0]["projectId"]
        for p in projects:
            if "Cooling" in p["projectName"] or "Fan" in p["projectName"]:
                project_id = p["projectId"]
                print(f"Using project: {p['projectName']} ({project_id})")
                break
        else:
            print(f"Using first project: {projects[0]['projectName']} ({project_id})")

    # Get context with element discovery
    ctx = pm.get_context(project_id)
    if not ctx:
        print("Failed to get project context")
        return 1

    # Display element catalog
    print(f"\n{'='*70}")
    print(f"  ELEMENT CATALOG — {len(ctx.elements)} elements")
    print(f"{'='*70}")
    print(f"  Project:   {ctx.project_id}")
    print(f"  EC ID:     {ctx.ec_id}")
    print(f"  Root XMI:  {ctx.root_xmi}")
    print(f"  Root eID:  {ctx.root_element_id}")
    print(f"  Tree Rep:  {ctx.tree_representation_id}")
    print(f"\n  Element tree:")
    print(ElementCatalog.format_catalog(ctx.elements, verbose=args.verbose))

    # Also try explorer items
    print(f"\n{'─'*70}")
    print(f"  EXPLORER TREE DESCRIPTIONS (from GraphQL)")
    print(f"{'─'*70}")
    items = MessageBuilder.get_explorer_tree_items(gql, ctx.ec_id)
    print(MessageBuilder.format_explorer_items(items))

    # Detailed element table
    if args.verbose:
        print(f"\n{'='*70}")
        print(f"  DETAILED ELEMENT TABLE")
        print(f"{'='*70}")
        print(
            f"  {'DEPTH':<5} {'NAME':<30} {'ECLASS':<30} {'XMI ID':<40} {'ELEMENT ID':<40}"
        )
        print(f"  {'-'*5} {'-'*30} {'-'*30} {'-'*40} {'-'*40}")
        for e in sorted(ctx.elements, key=lambda x: (x.depth, x.path)):
            print(
                f"  {e.depth:<5} {e.name[:28]:<30} {e.eclass[:28]:<30} {e.xmi_id:<40} {e.element_id:<40}"
            )
        if ctx.elements:
            print(f"\n  Most useful XMI IDs for mutations:")
            for e in ctx.elements:
                if e.name:
                    print(f"    {e.name}: xmi={e.xmi_id}")

    return 0


def insert_command(args):
    """Insert SysML content into a new or existing project."""
    token = login_via_api() if ADMIN_PASSWORD else make_jwt()
    gql = GQLClient(token)
    pm = ProjectManager(gql)

    if args.sysml_file:
        with open(args.sysml_file) as f:
            sysml_text = f.read()
    elif args.sysml_text:
        sysml_text = args.sysml_text
    elif args.cooling_fan:
        sysml_text = COOLING_FAN_SYSML
    elif args.temp_monitor:
        sysml_text = TEMP_MONITOR_SYSML
    else:
        if args.explain:
            # Use a placeholder sample for --explain without stdin
            sysml_text = "package Example { part def X; }"
        else:
            # Interactive: read from stdin
            print("Paste SysML v2 content (Ctrl+D to finish):")
            sysml_text = sys.stdin.read()

    if not sysml_text.strip():
        print("Error: no SysML content provided")
        return 1

    # --explain: show message anatomy without executing
    if args.explain:
        query, variables = MessageBuilder.insert_sysml(
            "<editingContextId (auto-from-project)>",
            "<rootXmi (auto-from-document)>",
            sysml_text,
        )
        param_roles = {
            "input.id": "AUTO",
            "input.editingContextId": "AUTO",
            "input.objectId": "AUTO",
            "input.textualContent": "USER",
        }
        print(MessageBuilder.explain(query, variables, param_roles, op_name="InsertSysML"))
        print(
            "\nNote: --explain shows placeholder IDs. The harness auto-discovers\n"
            "editingContextId and rootXmi from the project at execution time."
        )
        return 0

    result = run_insert_test(gql, pm, sysml_text, args.name or "manual")
    return 0 if result else 1


# ── TOOLS & CREATE-CHILD COMMANDS ──────────────────────────────────────────


def _resolve_element(ctx: ProjectContext, name_or_xmi: str) -> Optional[ElementInfo]:
    """Resolve an element by name (case-insensitive) or XMI/element id prefix.

    Resolution order:
      1. Exact XMI id match
      2. XMI id prefix match
      3. Exact elementId match
      4. Exact name match (case-insensitive)
      5. Unique name substring match (case-insensitive)

    Returns None if no match, or if a substring match is ambiguous (it will
    print the candidates in that case).
    """
    # 1. Exact XMI id
    for e in ctx.elements:
        if e.xmi_id == name_or_xmi:
            return e
    # 2. XMI id prefix
    for e in ctx.elements:
        if e.xmi_id.startswith(name_or_xmi):
            return e
    # 3. Exact elementId
    for e in ctx.elements:
        if e.element_id == name_or_xmi:
            return e
    # 4. Exact name (case-insensitive)
    for e in ctx.elements:
        if e.name and e.name.lower() == name_or_xmi.lower():
            return e
    # 5. Unique substring match
    matches = [
        e for e in ctx.elements
        if e.name and name_or_xmi.lower() in e.name.lower()
    ]
    if len(matches) == 1:
        return matches[0]
    if len(matches) > 1:
        print(f"Ambiguous element name '{name_or_xmi}':")
        for m in matches:
            print(f"  {m.name} [{m.eclass}] xmi={m.xmi_id}")
    return None


def tools_command(args):
    """List available child creation tools for a parent type."""
    if not args.project_id:
        print("Error: --project-id/-p is required for tools")
        return 1

    token = login_via_api() if ADMIN_PASSWORD else make_jwt()
    gql = GQLClient(token)
    pm = ProjectManager(gql)

    ctx = pm.get_context(args.project_id)
    if not ctx:
        print("Failed to get project context")
        return 1

    # Resolve the parent kind
    if args.kind:
        kind = SYSML_KIND_PREFIX + args.kind
        print(f"Using explicit kind: {kind}")
    elif args.parent:
        parent = _resolve_element(ctx, args.parent)
        if not parent:
            print(f"Parent element not found: {args.parent}")
            print("\nAvailable elements:")
            print(ElementCatalog.format_catalog(ctx.elements, verbose=True))
            return 1
        kind = eclass_to_kind(parent.eclass)
        print(
            f"Parent '{parent.name or '(unnamed)'}' [{parent.eclass}]\n"
            f"  → kind: {kind}"
        )
    else:
        kind = SYSML_KIND_PREFIX + "Package"
        print(
            "No --parent or --kind given; defaulting to Package.\n"
            f"  → kind: {kind}"
        )

    # Warm editing context
    warm_q, warm_v = MessageBuilder.warm_editing_context(ctx.ec_id)
    gql.execute(warm_q, warm_v, "WarmEC")

    # --explain: show anatomy of the discovery query
    if args.explain:
        query, variables = MessageBuilder.discover_child_tools(ctx.ec_id, kind)
        param_roles = {
            "ecId": "AUTO",
            "kind": "AUTO",
        }
        print(MessageBuilder.explain(query, variables, param_roles, op_name="ChildTools"))
        return 0

    # Discover tools
    query, variables = MessageBuilder.discover_child_tools(ctx.ec_id, kind)
    data, errors = gql.execute(query, variables, "ChildTools")
    if errors:
        print(f"GraphQL errors: {errors}")
        return 1

    descriptions = (
        (data or {})
        .get("viewer", {})
        .get("editingContext", {})
        .get("childCreationDescriptions", [])
    )

    print(f"\n{'='*78}")
    print(f"  CHILD CREATION TOOLS — {len(descriptions)} available")
    print(f"  Parent kind: {kind}")
    print(f"{'='*78}")
    print(f"  {'LABEL':<45} {'CHILD CREATION DESCRIPTION ID'}")
    print(f"  {'-'*45} {'-'*33}")
    for d in sorted(descriptions, key=lambda x: x.get("label", "")):
        label = d.get("label", "(unlabeled)")
        cid = d.get("id", "?")
        print(f"  {label:<45} {cid}")

    if not descriptions:
        print(
            "\n  No tools returned. The parent kind may not be valid, or the\n"
            "  editing context may not be warm. Try --kind Package as a baseline."
        )
    return 0


def create_child_command(args):
    """Create a child element under a parent."""
    if not args.project_id:
        print("Error: --project-id/-p is required for create-child")
        return 1
    if not args.parent:
        print("Error: --parent is required (element name or XMI id)")
        return 1
    if not getattr(args, "child_type", None):
        print("Error: --type is required (child element type name, e.g. PartDefinition)")
        return 1

    token = login_via_api() if ADMIN_PASSWORD else make_jwt()
    gql = GQLClient(token)
    pm = ProjectManager(gql)

    ctx = pm.get_context(args.project_id)
    if not ctx:
        print("Failed to get project context")
        return 1

    # Resolve parent element to XMI id
    parent = _resolve_element(ctx, args.parent)
    if not parent:
        print(f"Parent element not found: {args.parent}")
        print("\nAvailable elements:")
        print(ElementCatalog.format_catalog(ctx.elements, verbose=True))
        return 1
    parent_object_id = parent.xmi_id
    print(
        f"Parent: '{parent.name or '(unnamed)'}' [{parent.eclass}]\n"
        f"  xmi: {parent_object_id}"
    )

    # Resolve child type to childCreationDescriptionId
    parent_kind = eclass_to_kind(parent.eclass)
    warm_q, warm_v = MessageBuilder.warm_editing_context(ctx.ec_id)
    gql.execute(warm_q, warm_v, "WarmEC")
    tool_q, tool_v = MessageBuilder.discover_child_tools(ctx.ec_id, parent_kind)
    data, errors = gql.execute(tool_q, tool_v, "ChildTools")
    if errors:
        print(f"Error discovering tools: {errors}")
        return 1
    descriptions = (
        (data or {})
        .get("viewer", {})
        .get("editingContext", {})
        .get("childCreationDescriptions", [])
    )

    # Match by label containing the type name (case-insensitive)
    target = args.child_type.lower()
    matching = [d for d in descriptions if target in d.get("label", "").lower()]
    # Fallback: match by id suffix (e.g. SysMLv2EditService-PartDefinition)
    if not matching:
        matching = [d for d in descriptions if target in d.get("id", "").lower()]
    if not matching:
        print(f"\nNo child creation tool matching type '{args.child_type}'.")
        print(f"Available tools for parent kind {parent_kind}:")
        for d in sorted(descriptions, key=lambda x: x.get("label", "")):
            print(f"  {d.get('label', '?'):<35} {d.get('id', '?')}")
        return 1
    if len(matching) > 1:
        print(f"\nMultiple tools match type '{args.child_type}':")
        for d in matching:
            print(f"  {d.get('label', '?'):<35} {d.get('id', '?')}")
        print("Please use a more specific --type value.")
        return 1

    child_description_id = matching[0]["id"]
    matched_label = matching[0].get("label", "")
    print(f"Matched tool: '{matched_label}' → {child_description_id}")

    # Build the createChild mutation
    query, variables = MessageBuilder.create_child(
        ctx.ec_id, parent_object_id, child_description_id
    )
    param_roles = {
        "input.id": "AUTO",
        "input.editingContextId": "AUTO",
        "input.objectId": "USER",
        "input.childCreationDescriptionId": "USER",
    }

    # --explain: show anatomy and exit without executing
    if args.explain:
        print()
        print(MessageBuilder.explain(query, variables, param_roles, op_name="createChild"))
        return 0

    # Execute (warm already done above)
    print(f"\n{'─'*70}\n  Sending createChild mutation...")
    data, errors = gql.execute(query, variables, "CreateChild")
    if errors:
        print(f"GraphQL errors: {errors}")
        return 1

    result = (data or {}).get("createChild", {})
    typename = result.get("__typename")
    if typename == "CreateChildSuccessPayload":
        obj = result.get("object", {}) or {}
        print(f"\n✅ Created child element:")
        print(f"  id:    {obj.get('id', '?')}")
        print(f"  label: {obj.get('label', '?')}")
        print(f"  kind:  {obj.get('kind', '?')}")
        for m in result.get("messages", []) or []:
            print(f"  {m.get('level', '?')}: {m.get('body', '')}")
        if args.label:
            print(
                f"\n  Note: --label '{args.label}' was requested but createChild does\n"
                f"  not take a name parameter; rename via the explorer or SysML text."
            )
        return 0
    else:
        print(f"\n❌ createChild failed: {result.get('message', typename)}")
        for m in result.get("messages", []) or []:
            print(f"  {m.get('level', '?')}: {m.get('body', '')}")
        return 1


# ── PRE-BUILT SYSML MODELS ────────────────────────────────────────────────

COOLING_FAN_SYSML = textwrap.dedent("""\
package CoolingFanSystem {
    private import ScalarValues::*;
    private import SysML::*;

    part def CoolingFanAssembly {
        attribute mass : Real = 2.5;
        attribute maxRPM : Real = 3000;

        part motor : FanMotor;
        part housing : FanHousing;
        part blade1 : FanBlade;
        part blade2 : FanBlade;
        part blade3 : FanBlade;
        part blade4 : FanBlade;
    }

    part def FanMotor {
        attribute voltage : Real = 12;
        attribute powerConsumption : Real = 25;
        attribute nominalSpeed : Real = 2800;
    }

    part def FanBlade {
        attribute pitchAngle : Real = 30;
        attribute length : Real = 110;
        attribute material : String = "ABS";
    }

    part def FanHousing {
        attribute diameter : Real = 120;
        attribute thickness : Real = 3;
        attribute material : String = "Aluminum";
    }

    requirement def CoolingPerformanceReq {
        doc /* The cooling fan must deliver sufficient airflow */
        attribute requiredCFM : Real = 85;
    }

    requirement CoolingReq : CoolingPerformanceReq {
        attribute actualCFM : Real = 95;
    }

    enum def FanStatus {
        enum literal OFF;
        enum literal LOW;
        enum literal MEDIUM;
        enum literal HIGH;
    }
}""")

TEMP_MONITOR_SYSML = textwrap.dedent("""\
package TemperatureMonitor {
    private import ScalarValues::*;
    private import SysML::*;

    part def TempSensor {
        attribute accuracy : Real = 0.5;
        attribute rangeMin : Real = -40;
        attribute rangeMax : Real = 125;
    }

    part def Controller {
        attribute sampleRate : Real = 10;
        attribute threshold : Real = 85;

        part sensor : TempSensor;
    }

    requirement def AccuracyReq {
        doc /* Sensor accuracy must be within ±0.5°C */
        attribute maxDeviation : Real = 0.5;
    }

    requirement def ResponseTimeReq {
        attribute maxResponseMs : Real = 100;
    }

    enum def AlertLevel {
        enum literal NORMAL;
        enum literal WARNING;
        enum literal CRITICAL;
    }
}""")


# ── MAIN ──────────────────────────────────────────────────────────────────


def main():
    parser = argparse.ArgumentParser(
        description="SysON Sirius Web Protocol Test Harness (v3.2)",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
COMMANDS:
  catalog       Discover and list all elements in a project with their IDs
  insert        Insert SysML v2 content into a project
  verify        Verify model state
  tools         List available child creation tools for a parent type
  create-child  Create a child element under a parent via createChild mutation

COMMON FLAGS (all commands):
  -p, --project-id ID   Target project UUID
  --explain             Show the message anatomy (USER vs AUTO params) but
                        DO NOT execute the mutation. Works with: insert,
                        tools, create-child.
  -v, --verbose         Verbose output

TOOLS / CREATE-CHILD FLAGS:
  --kind ENTITY         Element entity name (e.g. 'Package', 'PartDefinition')
                        for tools. Builds kind=siriusComponents://?domain=sysml&entity=ENTITY
  --parent NAME|XMI     Parent element name or XMI id. For tools: resolve kind
                        from this element. For create-child: the parent to create under.
  --type ENTITY         Child element type name (e.g. 'PartDefinition') for create-child.
                        Matched against tool labels (substring, case-insensitive).
  --label NAME          Optional requested name (advisory only; createChild does
                        not accept a name — rename via explorer or SysML text).

EXAMPLES:
  # Catalog (unchanged)
  %(prog)s catalog                           # List elements in most recent project
  %(prog)s catalog -p <project-id> -v        # Verbose catalog with all IDs

  # Insert (unchanged)
  %(prog)s insert --cooling-fan              # Insert Cooling Fan model
  %(prog)s insert --temp-monitor             # Insert Temperature Monitor model
  %(prog)s insert --sysml-text "package P { part def X; }"
  %(prog)s insert --sysml-file model.sysml   # Insert from file
  %(prog)s insert --explain                  # Show insert message anatomy

  # Tools — discover available child creation tools
  %(prog)s tools -p <pid> --kind Package                 # All tools for Package kind (~84)
  %(prog)s tools -p <pid> --parent "Package 1"           # Resolve kind from element
  %(prog)s tools -p <pid> --kind Package --explain       # Show the discovery query anatomy

  # Create-child — create an element under a parent
  %(prog)s create-child -p <pid> --parent "Package 1" --type PartDefinition --explain
  %(prog)s create-child -p <pid> --parent <xmi-id>  --type PartDefinition
        """,
    )
    parser.add_argument("command", nargs="?", default="insert", help="Command to run")
    parser.add_argument(
        "-p", "--project-id", help="Target project ID (UUID)"
    )
    parser.add_argument("-v", "--verbose", action="store_true", help="Verbose output")
    parser.add_argument(
        "--cooling-fan", action="store_true", help="Insert Cooling Fan model"
    )
    parser.add_argument(
        "--temp-monitor", action="store_true", help="Insert Temperature Monitor model"
    )
    parser.add_argument("--sysml-text", help="SysML v2 text to insert")
    parser.add_argument("--sysml-file", help="File containing SysML v2 text")
    parser.add_argument("-n", "--name", help="Test name for logging")
    # tools / create-child flags
    parser.add_argument(
        "--kind",
        help="Element entity name for tools (e.g. 'Package', 'PartDefinition'). "
        "Builds the Sirius Web kind URL sirisComponents://?domain=sysml&entity=<ENTITY>.",
    )
    parser.add_argument(
        "--parent",
        help="Parent element name or XMI id (for tools: resolve kind from element; "
        "for create-child: the parent to create under).",
    )
    parser.add_argument(
        "--type",
        dest="child_type",
        help="Child element type name for create-child (e.g. 'PartDefinition'). "
        "Matched against tool labels (substring, case-insensitive).",
    )
    parser.add_argument(
        "--label",
        help="Optional requested name for the new element (advisory only — "
        "createChild does not take a name; rename via explorer or SysML text).",
    )
    parser.add_argument(
        "--explain",
        action="store_true",
        help="Show the message anatomy (USER vs AUTO params) without executing. "
        "Applies to: insert, tools, create-child.",
    )

    args = parser.parse_args()

    if args.command == "catalog":
        return catalog_command(args)
    elif args.command == "insert":
        return insert_command(args)
    elif args.command == "tools":
        return tools_command(args)
    elif args.command == "create-child":
        return create_child_command(args)
    elif args.command == "verify":
        token = login_via_api() if ADMIN_PASSWORD else make_jwt()
        gql = GQLClient(token)
        pm = ProjectManager(gql)
        if not args.project_id:
            print("Error: --project-id required for verify")
            return 1
        ctx = pm.get_context(args.project_id)
        if not ctx:
            return 1
        print(ElementCatalog.format_catalog(ctx.elements, verbose=True))
        return 0
    else:
        print(f"Unknown command: {args.command}")
        print("Valid commands: catalog, insert, verify, tools, create-child")
        return 1


if __name__ == "__main__":
    sys.exit(main())
