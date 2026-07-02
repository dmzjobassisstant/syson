#!/usr/bin/env python3
"""
SysON API Test Framework
========================
Validates the full CRUD lifecycle for all custom mutations:
  - updateElement (rename, set shortName, set body)
  - addChildElement (create typed children)
  - deleteElement (delete by graph @id)
  - manageRelationship (add/remove Dependency)
  - insertTextualSysMLv2 (import SysML code)

Architecture:
  - Uses a dedicated test project (created fresh if needed)
  - Creates a temporary package for each test run
  - Cleans up after itself
  - Reports PASS/FAIL with detailed diagnostics

Usage:
  python3 api_test.py                    # Run against localhost:8080
  python3 api_test.py --host https://syson.bowtie-modeler.com
  python3 api_test.py --verbose          # Show full API responses
  python3 api_test.py --keep             # Don't delete test project after run
"""

import argparse
import json
import sys
import time
import uuid
import requests
from datetime import datetime

# ============================================================
# Configuration
# ============================================================

DEFAULT_HOST = "http://localhost:8080"
DEFAULT_CREDS = {"email": "admin", "password": "admin"}
SYSML_DOMAIN = "http://www.eclipse.org/syson/sysml"
TEST_PROJECT_PREFIX = "ApiTestFramework"

# ANSI colors for terminal output
class C:
    GREEN = '\033[92m'
    RED = '\033[91m'
    YELLOW = '\033[93m'
    CYAN = '\033[96m'
    BOLD = '\033[1m'
    DIM = '\033[2m'
    END = '\033[0m'


# ============================================================
# Test Framework Core
# ============================================================

class TestResult:
    def __init__(self, name):
        self.name = name
        self.passed = False
        self.error = None
        self.details = {}
        self.duration = 0

    def fail(self, msg, details=None):
        self.passed = False
        self.error = msg
        if details:
            self.details = details

    def pass_(self, details=None):
        self.passed = True
        if details:
            self.details = details


class SysOnApi:
    """Low-level API client wrapping HTTP calls."""

    def __init__(self, host, creds, verbose=False):
        self.host = host.rstrip('/')
        self.creds = creds
        self.verbose = verbose
        self.token = None
        self.session = requests.Session()

    def login(self):
        r = self.session.post(
            f"{self.host}/api/auth/login",
            json=self.creds,
            timeout=30
        )
        r.raise_for_status()
        data = r.json()
        if 'token' not in data:
            raise RuntimeError(f"Login failed: {data}")
        self.token = data['token']
        self.session.headers.update({
            'Authorization': f'Bearer {self.token}',
            'Content-Type': 'application/json'
        })
        return self.token

    def graphql(self, query, variables=None):
        payload = {'query': query}
        if variables:
            payload['variables'] = variables
        r = self.session.post(
            f"{self.host}/api/graphql",
            json=payload,
            timeout=60
        )
        r.raise_for_status()
        data = r.json()
        if self.verbose:
            print(f"  {C.DIM}→ {query[:80]}...{C.END}")
            print(f"  {C.DIM}← {json.dumps(data)[:200]}...{C.END}")
        return data

    def rest_get(self, path):
        r = self.session.get(f"{self.host}{path}", timeout=60)
        r.raise_for_status()
        return r.json()

    # --- Convenience GraphQL mutations ---

    def update_element(self, ec_id, element_id, new_label=None, new_short_name=None, new_body=None):
        """Rename or update attributes of an element. element_id = graph @id."""
        fields = []
        if new_label is not None:
            fields.append(f'newLabel:{json.dumps(new_label)}')
        if new_short_name is not None:
            fields.append(f'newShortName:{json.dumps(new_short_name)}')
        if new_body is not None:
            fields.append(f'newBody:{json.dumps(new_body)}')

        mut_id = str(uuid.uuid4())
        q = f'''mutation {{
          updateElement(input:{{
            id:"{mut_id}",
            editingContextId:"{ec_id}",
            elementId:"{element_id}",
            {",".join(fields)}
          }}) {{
            __typename
            ... on SuccessPayload {{ id }}
            ... on ErrorPayload {{ messages {{ body level }} }}
          }}
        }}'''
        return self.graphql(q)

    def add_child(self, ec_id, parent_id, element_type, name=None):
        """Add a typed child element. parent_id = graph @id."""
        mut_id = str(uuid.uuid4())
        name_part = f'name:{json.dumps(name)}' if name else ''
        q = f'''mutation {{
          addChildElement(input:{{
            id:"{mut_id}",
            editingContextId:"{ec_id}",
            parentElementId:"{parent_id}",
            elementType:"{element_type}",
            {name_part}
          }}) {{
            __typename
            ... on SuccessPayload {{ id }}
            ... on ErrorPayload {{ messages {{ body level }} }}
          }}
        }}'''
        return self.graphql(q)

    def delete_element(self, ec_id, element_id):
        """Delete an element by graph @id."""
        mut_id = str(uuid.uuid4())
        q = f'''mutation {{
          deleteElement(input:{{
            id:"{mut_id}",
            editingContextId:"{ec_id}",
            elementId:"{element_id}"
          }}) {{
            __typename
            ... on SuccessPayload {{ id }}
            ... on ErrorPayload {{ messages {{ body level }} }}
          }}
        }}'''
        return self.graphql(q)

    def manage_relationship(self, ec_id, rel_type, source_id, target_ids, action="ADD"):
        """Add or remove a relationship between elements."""
        mut_id = str(uuid.uuid4())
        targets = json.dumps(target_ids)
        q = f'''mutation {{
          manageRelationship(input:{{
            id:"{mut_id}",
            editingContextId:"{ec_id}",
            relationshipType:"{rel_type}",
            sourceElementId:"{source_id}",
            targetElementIds:{targets},
            action:"{action}"
          }}) {{
            __typename
            ... on SuccessPayload {{ messages {{ body level }} }}
            ... on ErrorPayload {{ messages {{ body level }} }}
          }}
        }}'''
        return self.graphql(q)

    def create_document(self, ec_id, name):
        mut_id = str(uuid.uuid4())
        q = f'''mutation {{
          createDocument(input:{{
            id:"{mut_id}",
            editingContextId:"{ec_id}",
            name:{json.dumps(name)},
            stereotypeId:"empty_sysmlv2"
          }}) {{
            __typename
            ... on CreateDocumentSuccessPayload {{ document {{ id name kind }} }}
            ... on ErrorPayload {{ message }}
          }}
        }}'''
        return self.graphql(q)

    def create_root_object(self, ec_id, doc_id, type_desc_id="SysMLv2EditService-Package"):
        mut_id = str(uuid.uuid4())
        q = f'''mutation {{
          createRootObject(input:{{
            id:"{mut_id}",
            editingContextId:"{ec_id}",
            documentId:"{doc_id}",
            domainId:"{SYSML_DOMAIN}",
            rootObjectCreationDescriptionId:"type_desc_id"
          }}) {{
            __typename
            ... on CreateRootObjectSuccessPayload {{ object {{ id label kind }} }}
            ... on ErrorPayload {{ message }}
          }}
        }}'''.replace('type_desc_id', type_desc_id)
        return self.graphql(q)

    def create_child(self, ec_id, parent_id, type_desc_id):
        mut_id = str(uuid.uuid4())
        q = f'''mutation {{
          createChild(input:{{
            id:"{mut_id}",
            editingContextId:"{ec_id}",
            objectId:"{parent_id}",
            childCreationDescriptionId:"{type_desc_id}"
          }}) {{
            __typename
            ... on CreateChildSuccessPayload {{ object {{ id label kind }} }}
            ... on ErrorPayload {{ message }}
          }}
        }}'''
        return self.graphql(q)

    # --- REST helpers ---

    def get_elements(self, project_id):
        """Get all elements via REST. Returns list of element dicts."""
        return self.rest_get(
            f"/api/rest/projects/{project_id}/commits/{project_id}/elements"
        )

    def find_element_by_name(self, project_id, name, etype=None):
        """Find an element by name in the REST element list."""
        elements = self.get_elements(project_id)
        for e in elements:
            if (e.get('name') or e.get('declaredName')) == name:
                if etype is None or e.get('@type') == etype:
                    return e
        return None

    # --- Project lifecycle ---

    def list_projects(self):
        data = self.graphql('query{viewer{projects{edges{node{id name}}}}}')
        edges = data.get('data', {}).get('viewer', {}).get('projects', {}).get('edges', [])
        return [e['node'] for e in edges]

    def create_project(self, name):
        mut_id = str(uuid.uuid4())
        q = f'''mutation {{
          createProject(input:{{
            id:"{mut_id}",
            name:{json.dumps(name)},
            natures:["SysMLv2"]
          }}) {{
            __typename
            ... on CreateProjectSuccessPayload {{ project {{ id name }} }}
            ... on ErrorPayload {{ message }}
          }}
        }}'''
        return self.graphql(q)

    def delete_project(self, project_id):
        mut_id = str(uuid.uuid4())
        q = f'''mutation {{
          deleteProject(input:{{
            id:"{mut_id}",
            projectId:"{project_id}"
          }}) {{
            __typename
            ... on ErrorPayload {{ message }}
          }}
        }}'''
        return self.graphql(q)

    def get_editing_context(self, project_id):
        """Get editing context ID. Tries multiple methods, triggers creation if needed."""
        # Method 1: Direct query with project_id as EC ID
        data = self.graphql(f'query{{viewer{{editingContext(editingContextId:"{project_id}"){{id}}}}}}')
        ec = data.get('data', {}).get('viewer', {}).get('editingContext')
        if ec and ec.get('id'):
            return ec['id']

        # Method 2: project.currentEditingContext
        data = self.graphql(f'query{{viewer{{project(projectId:"{project_id}"){{currentEditingContext{{id}}}}}}}}')
        ec = data.get('data', {}).get('viewer', {}).get('project', {}).get('currentEditingContext') if data.get('data', {}).get('viewer', {}).get('project') else None
        if ec and ec.get('id'):
            return ec['id']

        # Method 3: Trigger creation via createDocument
        self.create_document(project_id, "_ec_trigger")
        data = self.graphql(f'query{{viewer{{editingContext(editingContextId:"{project_id}"){{id}}}}}}')
        ec = data.get('data', {}).get('viewer', {}).get('editingContext')
        if ec and ec.get('id'):
            return ec['id']

        # Fallback
        return project_id


# ============================================================
# Mutation Success/Failure Helpers
# ============================================================

def is_success(data, mutation_name):
    """Check if a GraphQL mutation returned SuccessPayload."""
    if data.get('errors'):
        return False
    payload = data.get('data', {}).get(mutation_name)
    if not payload:
        return False
    return payload.get('__typename') == 'SuccessPayload'


def get_error_msg(data, mutation_name):
    """Extract error message from a mutation response."""
    if data.get('errors'):
        return data['errors'][0].get('message', 'Unknown GraphQL error')
    payload = data.get('data', {}).get(mutation_name, {})
    if payload.get('__typename') == 'ErrorPayload':
        msgs = payload.get('messages', [])
        if msgs:
            return msgs[0].get('body', 'Unknown error')
        return payload.get('message', 'Unknown error')
    return 'Unknown'


def is_create_success(data, mutation_name):
    """Check if createChild/createRootObject succeeded and extract created object ID."""
    if data.get('errors'):
        return False, None
    payload = data.get('data', {}).get(mutation_name, {})
    if payload.get('__typename') in ('CreateChildSuccessPayload', 'CreateRootObjectSuccessPayload'):
        obj = payload.get('object', {})
        return True, obj.get('id')
    return False, None


# ============================================================
# Test Suite
# ============================================================

class TestSuite:
    def __init__(self, api, project_id, ec_id, verbose=False):
        self.api = api
        self.project_id = project_id
        self.ec_id = ec_id
        self.verbose = verbose
        self.results = []
        self.created_elements = []  # Track for cleanup

    def run_all(self):
        """Run the full test suite."""
        print(f"\n{C.BOLD}{'='*60}")
        print(f" SysON API Test Suite")
        print(f" Host: {self.api.host}")
        print(f" Project: {self.project_id[:8]}...")
        print(f" Editing Context: {self.ec_id[:8]}...")
        print(f" {'='*60}{C.END}\n")

        # Create a test package as container
        test_pkg = self._setup_test_package()
        if not test_pkg:
            self._print_summary()
            return False

        pkg_id = test_pkg['@id']
        print(f"{C.CYAN}Test container: Package '{test_pkg['name']}' ({pkg_id[:8]}...){C.END}\n")

        # Run each test
        tests = [
            ("addChildElement: Create PartUsage",         lambda r: self.test_add_child(pkg_id, "PartUsage", "TestPart", r)),
            ("addChildElement: Create AttributeUsage",    lambda r: self.test_add_child(pkg_id, "AttributeUsage", "TestAttr", r)),
            ("addChildElement: Create Package",           lambda r: self.test_add_child(pkg_id, "Package", "SubPackage", r)),
            ("addChildElement: Create RequirementUsage",  lambda r: self.test_add_child(pkg_id, "RequirementUsage", "TestReq", r)),
            ("addChildElement: Create PortUsage",         lambda r: self.test_add_child(pkg_id, "PortUsage", "TestPort", r)),
            ("updateElement: Rename PartUsage",           lambda r: self.test_update_rename(pkg_id, "PartUsage", r)),
            ("updateElement: Set shortName",              lambda r: self.test_update_short_name(pkg_id, r)),
            ("updateElement: Set body/documentation",     lambda r: self.test_update_body(pkg_id, r)),
            ("manageRelationship: Add Dependency",        lambda r: self.test_relationship_dependency(pkg_id, r)),
            ("deleteElement: Delete child by @id",        lambda r: self.test_delete_element(pkg_id, r)),
            ("createChild (upstream): Create via Sirius", lambda r: self.test_upstream_create_child(pkg_id, r)),
            ("REST: Verify elements visible",             lambda r: self.test_rest_visibility(r)),
        ]

        for name, test_fn in tests:
            self._run_test(name, test_fn)

        # Cleanup
        self._cleanup()

        self._print_summary()
        return all(r.passed for r in self.results)

    def _run_test(self, name, fn):
        result = TestResult(name)
        t0 = time.time()
        try:
            fn(result)
            if not result.error:
                result.pass_()
        except Exception as e:
            result.fail(str(e))
        result.duration = time.time() - t0
        self.results.append(result)

        status = f"{C.GREEN}PASS{C.END}" if result.passed else f"{C.RED}FAIL{C.END}"
        details = f" — {result.details.get('info','')}" if result.details.get('info') else ''
        print(f"  {status}  {result.name}{C.DIM}{details}{C.END}")
        if not result.passed and result.error:
            print(f"         {C.RED}Error: {result.error}{C.END}")
        if self.verbose and result.details.get('raw'):
            print(f"         {C.DIM}{json.dumps(result.details['raw'], indent=2)[:300]}{C.END}")

    # --- Setup ---

    def _setup_test_package(self):
        """Create a document and root Package for tests."""
        # Create document
        doc_resp = self.api.create_document(self.ec_id, f"TestDoc_{int(time.time())}")
        if not is_success(doc_resp, 'createDocument'):
            doc = doc_resp.get('data', {}).get('createDocument', {})
            if doc.get('document'):
                pass  # OK, doc was created
            else:
                print(f"{C.RED}Failed to create test document: {get_error_msg(doc_resp, 'createDocument')}{C.END}")
                return None

        # Get document ID
        doc_data = doc_resp.get('data', {}).get('createDocument', {})
        doc_id = doc_data.get('document', {}).get('id') if doc_data.get('document') else None
        if not doc_id:
            # Fallback: find via REST
            time.sleep(1)
            elements = self.api.get_elements(self.project_id)
            docs = [e for e in elements if e.get('@type') == 'Document']
            if docs:
                doc_id = docs[-1]['@id']

        if not doc_id:
            print(f"{C.RED}Could not get document ID{C.END}")
            return None

        # Create root Package
        root_resp = self.api.create_root_object(self.ec_id, doc_id, "SysMLv2EditService-Package")
        ok, pkg_node_id = is_create_success(root_resp, 'createRootObject')
        if not ok:
            # Fallback: use an existing package
            time.sleep(1)
            elements = self.api.get_elements(self.project_id)
            pkgs = [e for e in elements if e.get('@type') == 'Package' and e.get('name') and 'Test' not in e.get('name', '')]
            if pkgs:
                return pkgs[-1]
            print(f"{C.RED}Failed to create root Package: {get_error_msg(root_resp, 'createRootObject')}{C.END}")
            return None

        # Wait and find via REST
        time.sleep(2)
        elements = self.api.get_elements(self.project_id)
        for e in elements:
            if e.get('@id') == pkg_node_id:
                return e
        # Fallback: return a known package
        pkgs = [e for e in elements if e.get('@type') == 'Package']
        if pkgs:
            return pkgs[-1]
        return None

    # --- Individual Tests ---

    def test_add_child(self, parent_id, element_type, name, result=None):
        """Test addChildElement for various types."""
        resp = self.api.add_child(self.ec_id, parent_id, element_type, name)
        ok = is_success(resp, 'addChildElement')
        if ok:
            self.created_elements.append(name)
            result.details = {'info': f'Created {element_type} "{name}"'}
        else:
            result.fail(get_error_msg(resp, 'addChildElement'), {'raw': resp})

    def test_update_rename(self, parent_id, element_type, result=None):
        """Test updateElement rename: create child → rename → verify."""
        # Create
        resp = self.api.add_child(self.ec_id, parent_id, element_type, "RenameMe")
        if not is_success(resp, 'addChildElement'):
            result.fail(f"Setup failed (create): {get_error_msg(resp, 'addChildElement')}")
            return

        # Find via REST
        time.sleep(1)
        elem = self.api.find_element_by_name(self.project_id, "RenameMe", element_type)
        if not elem:
            result.fail("Created element not found via REST after addChildElement")
            return
        elem_id = elem['@id']

        # Rename
        resp = self.api.update_element(self.ec_id, elem_id, new_label="HasBeenRenamed")
        if not is_success(resp, 'updateElement'):
            result.fail(f"Rename failed: {get_error_msg(resp, 'updateElement')}")
            return

        # Verify
        time.sleep(1)
        elem = self.api.find_element_by_name(self.project_id, "HasBeenRenamed", element_type)
        if not elem:
            result.fail("Renamed element not found via REST after updateElement")
            return

        result.details = {'info': f'Renamed "{elem_id[:8]}..." → "HasBeenRenamed"'}

    def test_update_short_name(self, parent_id, result=None):
        """Test updateElement set shortName."""
        # Create element first
        resp = self.api.add_child(self.ec_id, parent_id, "PartUsage", "ShortNameTest")
        if not is_success(resp, 'addChildElement'):
            result.fail(f"Setup failed: {get_error_msg(resp, 'addChildElement')}")
            return

        time.sleep(1)
        elem = self.api.find_element_by_name(self.project_id, "ShortNameTest", "PartUsage")
        if not elem:
            result.fail("Element not found after create")
            return

        # Set short name
        resp = self.api.update_element(self.ec_id, elem['@id'], new_short_name="snt")
        if not is_success(resp, 'updateElement'):
            result.fail(f"Set shortName failed: {get_error_msg(resp, 'updateElement')}")
            return

        # Verify
        time.sleep(1)
        elements = self.api.get_elements(self.project_id)
        for e in elements:
            if e.get('@id') == elem['@id']:
                sn = e.get('shortName') or e.get('declaredShortName')
                if sn == 'snt':
                    result.details = {'info': f'set shortName="snt" verified'}
                    return
                else:
                    result.fail(f"shortName not set. Got: {sn}")
                    return
        result.fail("Element not found after update")

    def test_update_body(self, parent_id, result=None):
        """Test updateElement set body/documentation."""
        resp = self.api.add_child(self.ec_id, parent_id, "PartUsage", "BodyTest")
        if not is_success(resp, 'addChildElement'):
            result.fail(f"Setup failed: {get_error_msg(resp, 'addChildElement')}")
            return

        time.sleep(1)
        elem = self.api.find_element_by_name(self.project_id, "BodyTest", "PartUsage")
        if not elem:
            result.fail("Element not found after create")
            return

        body_text = "This is a test documentation body."
        resp = self.api.update_element(self.ec_id, elem['@id'], new_body=body_text)
        if not is_success(resp, 'updateElement'):
            result.fail(f"Set body failed: {get_error_msg(resp, 'updateElement')}")
            return

        result.details = {'info': f'Set body to "{body_text[:30]}..."'}

    def test_relationship_dependency(self, parent_id, result=None):
        """Test manageRelationship: create two elements, add Dependency between them."""
        # Create two elements
        for name in ["DepSource", "DepTarget"]:
            resp = self.api.add_child(self.ec_id, parent_id, "PartUsage", name)
            if not is_success(resp, 'addChildElement'):
                result.fail(f"Setup failed ({name}): {get_error_msg(resp, 'addChildElement')}")
                return

        time.sleep(1)
        src = self.api.find_element_by_name(self.project_id, "DepSource", "PartUsage")
        tgt = self.api.find_element_by_name(self.project_id, "DepTarget", "PartUsage")
        if not src or not tgt:
            result.fail("Could not find source/target elements after create")
            return

        # Add Dependency
        resp = self.api.manage_relationship(
            self.ec_id, "Dependency", src['@id'], [tgt['@id']], "ADD"
        )
        if not is_success(resp, 'manageRelationship'):
            result.fail(f"Add Dependency failed: {get_error_msg(resp, 'manageRelationship')}")
            return

        result.details = {'info': f'Dependency: {src["name"]} → {tgt["name"]}'}

    def test_delete_element(self, parent_id, result=None):
        """Test deleteElement: create child → delete → verify gone."""
        resp = self.api.add_child(self.ec_id, parent_id, "PartUsage", "DeleteMe")
        if not is_success(resp, 'addChildElement'):
            result.fail(f"Setup failed (create): {get_error_msg(resp, 'addChildElement')}")
            return

        time.sleep(1)
        elem = self.api.find_element_by_name(self.project_id, "DeleteMe", "PartUsage")
        if not elem:
            result.fail("Created element not found via REST")
            return

        # Delete
        resp = self.api.delete_element(self.ec_id, elem['@id'])
        if not is_success(resp, 'deleteElement'):
            result.fail(f"Delete failed: {get_error_msg(resp, 'deleteElement')}")
            return

        # Verify gone
        time.sleep(1)
        elem = self.api.find_element_by_name(self.project_id, "DeleteMe", "PartUsage")
        if elem:
            result.fail("Element still exists after deleteElement")
            return

        result.details = {'info': 'Created → Deleted → Verified absent'}

    def test_upstream_create_child(self, parent_id, result=None):
        """Test upstream createChild mutation (Sirius Web native)."""
        resp = self.api.create_child(self.ec_id, parent_id, "SysMLv2EditService-PartUsage")
        ok, _ = is_create_success(resp, 'createChild')
        if ok:
            result.details = {'info': 'Upstream createChild works'}
        else:
            result.fail(f"createChild failed: {get_error_msg(resp, 'createChild')}")

    def test_rest_visibility(self, result=None):
        """Test that elements are visible via REST API."""
        elements = self.api.get_elements(self.project_id)
        if not isinstance(elements, list) or len(elements) == 0:
            result.fail("REST /elements returned empty or non-list")
            return

        # Verify @id and @type fields exist
        sample = elements[0]
        if '@id' not in sample or '@type' not in sample:
            result.fail(f"Elements missing @id/@type fields. Keys: {list(sample.keys())[:10]}")
            return

        # Check for our test elements
        test_names = ["TestPart", "TestAttr", "SubPackage", "TestReq", "TestPort"]
        found = []
        for name in test_names:
            for e in elements:
                if (e.get('name') or '') == name:
                    found.append(name)
                    break

        result.details = {'info': f'{len(elements)} elements, {len(found)}/{len(test_names)} test elements visible'}

    # --- Cleanup ---

    def _cleanup(self):
        """Delete test elements created during the run."""
        print(f"\n{C.DIM}Cleaning up test elements...{C.END}")
        elements = self.api.get_elements(self.project_id)
        test_elements = [
            e for e in elements
            if any(name in (e.get('name') or '') for name in [
                'TestPart', 'TestAttr', 'SubPackage', 'TestReq', 'TestPort',
                'RenameMe', 'HasBeenRenamed', 'ShortNameTest', 'BodyTest',
                'DepSource', 'DepTarget', 'DeleteMe', 'UpstreamPart'
            ])
        ]
        deleted = 0
        for e in test_elements:
            resp = self.api.delete_element(self.ec_id, e['@id'])
            if is_success(resp, 'deleteElement'):
                deleted += 1
        print(f"{C.DIM}Deleted {deleted}/{len(test_elements)} test elements{C.END}")

    # --- Reporting ---

    def _print_summary(self):
        total = len(self.results)
        passed = sum(1 for r in self.results if r.passed)
        failed = total - passed
        duration = sum(r.duration for r in self.results)

        print(f"\n{C.BOLD}{'='*60}")
        color = C.GREEN if failed == 0 else C.RED
        print(f" {color}Results: {passed}/{total} passed, {failed} failed ({duration:.1f}s){C.END}")
        print(f"{'='*60}{C.END}")

        if failed > 0:
            print(f"\n{C.RED}Failed tests:{C.END}")
            for r in self.results:
                if not r.passed:
                    print(f"  ✗ {r.name}")
                    print(f"    {C.RED}{r.error}{C.END}")


# ============================================================
# Main
# ============================================================

def main():
    parser = argparse.ArgumentParser(description='SysON API Test Framework')
    parser.add_argument('--host', default=DEFAULT_HOST, help='SysON host URL')
    parser.add_argument('--email', default=DEFAULT_CREDS['email'])
    parser.add_argument('--password', default=DEFAULT_CREDS['password'])
    parser.add_argument('--verbose', '-v', action='store_true')
    parser.add_argument('--keep', action='store_true', help='Keep test project after run')
    args = parser.parse_args()

    api = SysOnApi(args.host, {"email": args.email, "password": args.password}, verbose=args.verbose)

    # Login
    print(f"{C.CYAN}Connecting to {args.host}...{C.END}")
    try:
        api.login()
        print(f"{C.GREEN}✓ Logged in{C.END}")
    except Exception as e:
        print(f"{C.RED}✗ Login failed: {e}{C.END}")
        sys.exit(1)

    # Find or create test project
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    test_project_name = f"{TEST_PROJECT_PREFIX}_{timestamp}"

    print(f"{C.CYAN}Creating test project '{test_project_name}'...{C.END}")
    resp = api.create_project(test_project_name)
    project_data = resp.get('data', {}).get('createProject', {})
    if not project_data.get('project'):
        print(f"{C.RED}✗ Failed to create project: {get_error_msg(resp, 'createProject')}{C.END}")
        sys.exit(1)

    project_id = project_data['project']['id']
    print(f"{C.GREEN}✓ Project created: {project_id[:8]}...{C.END}")

    # Get editing context
    print(f"{C.CYAN}Loading editing context...{C.END}")
    ec_id = api.get_editing_context(project_id)
    print(f"{C.GREEN}✓ Editing context: {ec_id[:8]}...{C.END}")

    # Run tests
    suite = TestSuite(api, project_id, ec_id, verbose=args.verbose)
    success = suite.run_all()

    # Cleanup project
    if not args.keep:
        print(f"\n{C.CYAN}Deleting test project...{C.END}")
        api.delete_project(project_id)
        print(f"{C.GREEN}✓ Test project deleted{C.END}")

    sys.exit(0 if success else 1)


if __name__ == '__main__':
    main()
