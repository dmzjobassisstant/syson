# LLM Model Generation Decision Guide

**Purpose:** Provides an LLM with the decision framework to determine whether to generate a SysML model from scratch (via SysMLv2 code import) or update an existing model (via API mutation commands). Designed for history-compatible, audit-friendly operations.

**Companion documents:**
- `SYSON_API_REFERENCE.md` — Complete API reference
- `SYSML_V2_CODE_RULES.md` — SysMLv2 syntax rules
- `FORK_EXTENSIONS.md` — Fork vs upstream diff

---

## The Core Decision

```
                    ┌─────────────────────────────────┐
                    │      LLM receives a request      │
                    │   to create/modify a SysML model │
                    └──────────────┬──────────────────┘
                                   │
                    ┌──────────────▼──────────────────┐
                    │   Does the target project already │
                    │   exist with meaningful content? │
                    └──────────┬─────────┬────────────┘
                               │         │
                          YES  │         │ NO
                               ▼         ▼
                    ┌──────────────┐   ┌──────────────────┐
                    │  INCREMENTAL │   │  FRESH GENERATION │
                    │  UPDATE path │   │  via code import  │
                    └──────┬───────┘   └────────┬─────────┘
                           │                    │
                           ▼                    ▼
                    Sequence of             insertTextualSysMLv2
                    API mutations           with full .sysml code
                    (preserves history)     (new project/document)
```

---

## Decision Matrix

### Choose FRESH GENERATION when:

| Condition | Rationale |
|-----------|-----------|
| Target project does not exist | Nothing to update |
| Project exists but is empty | No history to preserve |
| User explicitly requests "generate from scratch" | User intent |
| Importing a standard library or reference model | Not an evolving model |
| Creating a template/starter model for a new system | Greenfield |
| The requested change modifies >60% of existing elements | Most of the model changes; simpler to regenerate |
| Creating a separate module/library that doesn't interact with existing elements | Isolated scope |

### Choose INCREMENTAL UPDATE when:

| Condition | Rationale |
|-----------|-----------|
| Project exists with real content | History matters |
| Model has been worked on for months/years | **Audit trail is critical** |
| The change adds, renames, or deletes a few elements | Targeted modification |
| The change modifies properties/attributes of existing elements | Precision needed |
| The change adds relationships between existing elements | Must reference real element IDs |
| The model has branches, baselines, or version history | Regeneration would destroy VC |
| Element locks exist on the model | Active collaboration |
| The change affects <40% of elements | Most of the model is preserved |

---

## Path 1: Fresh Generation via SysMLv2 Code Import

### When to use
New project, template, library import, or complete model definition from textual specification.

### Procedure

```python
# 1. Create project
project = create_project(name="MyModel", templateId="sysmlv2")

# 2. Get editing context (MUST open project to load it)
ec_id = get_editing_context(project.id)

# 3. Create document
doc = create_document(editingContextId=ec_id, name="Main")

# 4. Create root Package
root_pkg = create_root_object(
    editingContextId=ec_id,
    documentId=doc.id,
    creationDescriptionId="SysMLv2EditService-Package"
)

# 5. Import SysMLv2 code into root package
sysml_code = """
    part def Vehicle {
        attribute speed : Integer;
        attribute mass : Real;
        part engine : Engine;
    }
    part def Engine {
        attribute power : Real;
    }
    part car : Vehicle;
"""

result = insert_textual_sysmlv2(
    editingContextId=ec_id,
    objectId=root_pkg.id,
    textualContent=sysml_code
)
```

### Advantages
- Single API call for complex structures
- Human-readable SysMLv2 code
- Can express relationships, inheritance, constraints naturally
- Full structural validation by the SysMLv2 parser

### Disadvantages
- **Loses element ID stability** — new elements get fresh UUIDs on each import
- **No granular history** — appears as a single "semantic change" in audit trail
- **Cannot reference existing elements** — imported elements are new, not linked to existing model elements
- Not suitable for iterative refinement of an evolving model

### Code Validation Before Import
Always validate the SysMLv2 code before importing:
```bash
POST /api/v1/sysml/validate
{"code": "<your sysml code>"}
```

---

## Path 2: Incremental Update via API Mutation Sequence

### When to use
Modifying an existing model with history. This is the **default for production models**.

### Procedure

#### Step 1: Discover the current model state
```bash
# Get all elements
GET /api/rest/projects/{projectId}/commits/{projectId}/elements

# Get editing context ID (NOT the project ID!)
query {
  viewer {
    project(projectId: "<project-id>") {
      currentEditingContext { id }
    }
  }
}
```

#### Step 2: Identify what needs to change
Map each desired change to a specific API mutation:

| Desired Change | API Mutation | Example |
|---------------|-------------|---------|
| Rename an element | `updateElement` | `newLabel: "NewName"` |
| Change short name | `updateElement` | `newShortName: "nn"` |
| Add documentation | `updateElement` | `newBody: "Description..."` |
| Add a child element | `addChildElement` | `elementType: "PartUsage", name: "Engine"` |
| Delete an element | `deleteElement` | `elementId: "<id>"` |
| Add a dependency | `manageRelationship` | `relationshipType: "Dependency", action: "ADD"` |
| Add inheritance | `manageRelationship` | `relationshipType: "Subclassification", action: "ADD"` |
| Remove a relationship | `manageRelationship` | `action: "REMOVE"` |
| Import SysML text block | `insertTextualSysMLv2` | For bulk sub-tree creation within existing model |

#### Step 3: Execute mutations as a sequence

```python
EC_ID = "ab0c1251-c999-43fd-ad91-f84d03e65b21"  # editing context ID

# Example: Add a new subsystem to an existing vehicle model

# 1. Find the parent package
parent_id = find_element_by_name(EC_ID, "VehicleSystems")

# 2. Add a new PartDefinition for the subsystem
result = add_child_element(
    editingContextId=EC_ID,
    parentElementId=parent_id,
    elementType="PartDefinition",
    name="BrakingSystem"
)
braking_id = get_created_element_id(result)

# 3. Add attributes to the new definition
add_child_element(
    editingContextId=EC_ID,
    parentElementId=braking_id,
    elementType="AttributeUsage",
    name="brakingForce"
)

# 4. Make it inherit from an existing base class
base_class_id = find_element_by_name(EC_ID, "VehicleSubsystem")
manage_relationship(
    editingContextId=EC_ID,
    relationshipType="Subclassification",
    sourceElementId=braking_id,
    targetElementIds=[base_class_id],
    action="ADD"
)

# 5. Add a dependency from Vehicle to BrakingSystem
vehicle_id = find_element_by_name(EC_ID, "Vehicle")
manage_relationship(
    editingContextId=EC_ID,
    relationshipType="Dependency",
    sourceElementId=vehicle_id,
    targetElementIds=[braking_id],
    action="ADD"
)
```

#### Step 4: Save with commit message (preserves history)
```bash
POST /api/v1/projects/{projectId}/save
{
  "message": "Added BrakingSystem subsystem with inheritance from VehicleSubsystem",
  "branchId": "<branch-id>"
}
```

### Advantages
- **Preserves element ID stability** — existing elements keep their UUIDs
- **Granular audit trail** — each mutation is a separate semantic change
- **Version control compatible** — works with branches, baselines, locks
- **Can reference existing elements** — relationships link to real model elements
- **Safe for production models** — no risk of destroying years of work

### Disadvantages
- More API calls for complex changes
- Need to discover element IDs before modifying
- Cannot easily express complex nested structures in a single call

---

## Hybrid Approach: Best of Both Worlds

For complex additions to an existing model, use **insertTextualSysMLv2 for new sub-trees** combined with **API mutations for linking to existing elements**:

```python
# 1. Find the parent package in the existing model
parent_id = find_element_by_name(EC_ID, "PowerSystems")

# 2. Import a new sub-system as SysMLv2 code (creates the sub-tree)
insert_textual_sysml_v2(
    editingContextId=EC_ID,
    objectId=parent_id,
    textualContent="""
        part def PowerPlant {
            attribute capacity : Real;
            part generator : Generator;
            part battery : Battery;
        }
        part def Generator {
            attribute maxOutput : Real;
        }
        part def Battery {
            attribute capacity : Real;
        }
    """
)

# 3. Link the new elements to existing model elements
power_plant_id = find_element_by_name(EC_ID, "PowerPlant")
vehicle_id = find_element_by_name(EC_ID, "Vehicle")

manage_relationship(
    editingContextId=EC_ID,
    relationshipType="Dependency",
    sourceElementId=vehicle_id,
    targetElementIds=[power_plant_id],
    action="ADD"
)
```

---

## Element ID Discovery

Before performing incremental updates, you must discover element IDs. Three methods:

### Method 1: REST API (fastest for bulk)
```bash
GET /api/rest/projects/{projectId}/commits/{projectId}/elements
```
Returns JSON-LD array of all elements with `elementId`, `declaredName`, `eClass`.

### Method 2: GraphQL object() query (for individual elements)
```graphql
query {
  viewer {
    editingContext(editingContextId: "<ec-id>") {
      object(objectId: "<element-id>") {
        __typename
        ... on Object { label }
      }
    }
  }
}
```

### Method 3: Explorer tree subscription (via WebSocket)
Not recommended for LLM use — requires active WebSocket subscription.

---

## History and Audit Trail

### What gets recorded in the audit trail

| Operation | Audit Entry |
|-----------|------------|
| `updateElement` | `SEMANTIC_CHANGE` — element modification |
| `deleteElement` | `SEMANTIC_CHANGE` — element deletion |
| `addChildElement` | `SEMANTIC_CHANGE` — element creation |
| `manageRelationship` | `SEMANTIC_CHANGE` — relationship change |
| `insertTextualSysMLv2` | `SEMANTIC_CHANGE` — text import (bulk) |
| `createChild` | `SEMANTIC_CHANGE` — palette creation |
| `createRootObject` | `SEMANTIC_CHANGE` — root creation |

Each mutation emits a `ChangeDescription(ChangeKind.SEMANTIC_CHANGE, ...)` which triggers:
1. **Sirius Web persistence** — saves the EMF model to `document.content`
2. **Shadow-mode extraction** — `SemanticDataSaveListener` extracts to `syson_head_elements` for history
3. **Audit logging** — if performed by an authenticated user, the action is logged to `syson_audit_events`

### Why regeneration destroys history

When you regenerate a model via `insertTextualSysMLv2`:
- All elements get **new UUIDs** — the old element IDs no longer exist
- The audit trail shows the old elements as deleted and new elements as created
- **Element-level history is broken** — you can no longer trace "this attribute was changed on date X by user Y"
- Branch/baseline references to old element IDs become dangling

### The golden rule

> **Never regenerate a model that has history you care about.**
> For production models with audit/compliance requirements, always use incremental API mutations.
> Reserve SysMLv2 code import for new projects, new sub-trees, or non-evolving reference models.

---

## LLM Prompt Template

When given a model modification request, the LLM should follow this decision process:

```
GIVEN: A request to create or modify a SysML model
AND: The SysON API is available at <base_url> with auth token <token>

STEP 1 - DISCOVER:
  - Query existing projects: GET /api/graphql → viewer.projects
  - If target project exists:
    - Get editing context ID (NOT project ID)
    - Get current elements via REST API
    - Count existing elements and check for branches/baselines
  - If target project does not exist:
    → DECISION: FRESH GENERATION

STEP 2 - CLASSIFY:
  - What percentage of elements will change?
    - >60% changed AND no critical history → FRESH GENERATION
    - <40% changed OR critical history exists → INCREMENTAL UPDATE
    - 40-60% → Use HYBRID (import new sub-trees + link to existing)

STEP 3 - VALIDATE (for code import):
  - POST /api/v1/sysml/validate with the SysMLv2 code
  - Fix any parse errors before importing

STEP 4 - EXECUTE:
  - FRESH: createProject → createDocument → createRootObject → insertTextualSysMLv2
  - INCREMENTAL: For each change, call the appropriate mutation (updateElement, addChildElement, deleteElement, manageRelationship)
  - HYBRID: insertTextualSysMLv2 for new sub-trees + API mutations for linking

STEP 5 - VERIFY:
  - Query the model via REST API to confirm changes
  - Save with commit message: POST /api/v1/projects/{id}/save
```

---

## Quick Reference: Mutation Selection

| I want to... | Use this mutation |
|-------------|-------------------|
| Create a new project | `createProject` |
| Create a new document | `createDocument` |
| Create a root Package in a document | `createRootObject` |
| Add a typed child element | `addChildElement` |
| Add a child via palette tool ID | `createChild` |
| Rename an element | `updateElement` with `newLabel` |
| Change short name | `updateElement` with `newShortName` |
| Add/update documentation | `updateElement` with `newBody` |
| Delete an element | `deleteElement` |
| Add a dependency | `manageRelationship` (type=Dependency, action=ADD) |
| Add inheritance | `manageRelationship` (type=Subclassification, action=ADD) |
| Remove a relationship | `manageRelationship` (action=REMOVE) |
| Import SysMLv2 code block | `insertTextualSysMLv2` |
| Create a diagram | `createRepresentation` |
| Save with commit message | `POST /api/v1/projects/{id}/save` |
| Undo last change | `undo` (requires WS) |
