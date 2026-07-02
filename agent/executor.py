"""
SysON Agent — Command Executor

Executes validated structured output commands against the SysON GraphQL API.
Each command type maps to a specific mutation.
"""

import json
import uuid
import logging
from typing import Optional
from validator import StructuredResponse, Command, ActionType

logger = logging.getLogger(__name__)


class SysOnApiClient:
    """Low-level client for the SysON GraphQL + REST API."""

    def __init__(self, base_url: str, token: str):
        self.base_url = base_url.rstrip('/')
        self.token = token
        import requests
        self._session = requests.Session()
        self._session.headers.update({
            'Authorization': f'Bearer {token}',
            'Content-Type': 'application/json'
        })

    def graphql(self, query: str, variables: dict = None) -> dict:
        payload = {'query': query}
        if variables:
            payload['variables'] = variables
        r = self._session.post(
            f"{self.base_url}/api/graphql",
            json=payload,
            timeout=120
        )
        r.raise_for_status()
        return r.json()

    def rest_get(self, path: str) -> dict:
        r = self._session.get(f"{self.base_url}{path}", timeout=120)
        r.raise_for_status()
        return r.json()

    def get_elements(self, project_id: str) -> list[dict]:
        return self.rest_get(f"/api/rest/projects/{project_id}/commits/{project_id}/elements")

    def get_editing_context(self, project_id: str) -> str:
        """Resolve editing context ID from project ID."""
        # Try project.currentEditingContext first
        data = self.graphql(f'query{{viewer{{project(projectId:"{project_id}"){{currentEditingContext{{id}}}}}}}}')
        ec = data.get('data', {}).get('viewer', {}).get('project', {}).get('currentEditingContext')
        if ec and ec.get('id'):
            return ec['id']
        # Try direct
        data = self.graphql(f'query{{viewer{{editingContext(editingContextId:"{project_id}"){{id}}}}}}')
        ec = data.get('data', {}).get('viewer', {}).get('editingContext')
        if ec and ec.get('id'):
            return ec['id']
        # Fallback
        return project_id

    def get_model_structure(self, project_id: str) -> dict:
        """
        Get current model structure as a tree for LLM context.
        Returns dict with elements list and tree structure.
        """
        elements = self.get_elements(project_id)
        
        # Build a map
        by_id = {e['@id']: e for e in elements}
        
        # Build parent-child relationships
        children_map: dict[str, list] = {}
        roots = []
        for e in elements:
            owner = e.get('owner') or {}
            owner_id = owner.get('@id') if isinstance(owner, dict) else None
            if owner_id and owner_id in by_id:
                children_map.setdefault(owner_id, []).append(e)
            else:
                roots.append(e)

        # Get element IDs for validation
        all_ids = set(by_id.keys())

        # Build a simplified tree (skip relationship/membership types)
        HIDDEN_TYPES = {
            'OwningMembership', 'FeatureMembership', 'Membership', 'NamespaceImport', 'Import',
            'FeatureTyping', 'Subsetting', 'Subclassification', 'Redefinition', 'FeatureValue',
            'FeatureChaining', 'FeatureChainExpression', 'OperatorExpression', 'LiteralInteger',
            'LiteralString', 'LiteralInfinity', 'FeatureReferenceExpression', 'ParameterMembership',
            'EndFeatureMembership', 'MultiplicityRange', 'ReferenceSubsetting', 'NullExpression',
            'MembershipExpose', 'TransitionFeatureMembership', 'ConjugatedPortTyping',
            'PortConjugation', 'ReturnParameterMembership', 'ElementFilterMembership',
            'VariantMembership', 'FeatureMembership', 'SubjectMembership', 'ActorMembership',
            'ObjectiveMembership', 'StakeholderMembership', 'RequirementConstraintMembership',
            'ResultExpressionMembership', 'ViewRenderingMembership', 'FramedConcernMembership',
            'Documentation', 'SuccessionAsUsage', 'OccurrenceUsage', 'Parameter', 'Feature',
            'ReferenceUsage'
        }

        # Build named elements list for ID reference lookups
        named_elements = []
        for e in elements:
            etype = e['@type']
            if etype not in HIDDEN_TYPES:
                name = e.get('name') or e.get('declaredName') or ''
                if name:
                    # Resolve parent name
                    owner = e.get('owner') or {}
                    owner_id = owner.get('@id') if isinstance(owner, dict) else None
                    parent_name = ''
                    if owner_id and owner_id in by_id:
                        p = by_id[owner_id]
                        parent_name = p.get('name') or p.get('declaredName') or ''
                    named_elements.append((name, e['@id'], etype, parent_name))

        def build_tree(node, depth=0, max_depth=5):
            if depth > max_depth:
                return None
            children = children_map.get(node['@id'], [])
            visible_children = []
            for child in children:
                if child['@type'] in HIDDEN_TYPES:
                    # Hoist grandchildren
                    gc = children_map.get(child['@id'], [])
                    for g in gc:
                        t = build_tree(g, depth + 1, max_depth)
                        if t:
                            visible_children.append(t)
                else:
                    t = build_tree(child, depth + 1, max_depth)
                    if t:
                        visible_children.append(t)
            
            name = node.get('name') or node.get('declaredName') or '(unnamed)'
            return {
                'id': node['@id'],
                'type': node['@type'],
                'name': name,
                'children': visible_children
            }

        tree = []
        for root in roots:
            if root['@type'] in HIDDEN_TYPES:
                # Hoist children of hidden roots
                for child in children_map.get(root['@id'], []):
                    t = build_tree(child)
                    if t:
                        tree.append(t)
            else:
                t = build_tree(root)
                if t:
                    tree.append(t)

        return {
            'elements': tree,
            'all_ids': list(all_ids),
            'total_count': len(elements),
            'named_elements': named_elements,
        }

    def model_tree_to_text(self, tree: list[dict], indent: int = 0) -> str:
        """Convert model tree to readable text for LLM context."""
        lines = []
        for node in tree:
            prefix = '  ' * indent
            children = node.get('children', [])
            marker = '+' if children else '-'
            lines.append(f"{prefix}{marker} {node['type']}: {node['name']} [id:{node['id']}]")
            if children:
                lines.append(self.model_tree_to_text(children, indent + 1))
        return '\n'.join(lines)


class CommandExecutor:
    """Executes validated commands against the SysON API."""

    def __init__(self, client: SysOnApiClient):
        self.client = client

    def execute(self, response: StructuredResponse, ec_id: str, project_id: str = None, model: dict = None) -> dict:
        """
        Execute a structured response.
        
        Returns dict with:
          - success: bool
          - action: str
          - results: list of per-command results
          - error: str (if failed)
        """
        if response.action == ActionType.IMPORT:
            return self._execute_import(response, ec_id, project_id, model)
        elif response.action == ActionType.UPDATE:
            return self._execute_update(response, ec_id, model)
        elif response.action == ActionType.CLARIFY:
            return {"success": True, "action": "CLARIFY", "message": response.chat_feedback}
        else:
            return {"success": False, "error": f"Cannot execute action: {response.action}"}

    def _execute_import(self, response: StructuredResponse, ec_id: str, project_id: str = None, model: dict = None) -> dict:
        """Execute IMPORT action via insertTextualSysMLv2."""
        mut_id = str(uuid.uuid4())
        parent_id = response.parent_element_id

        # If no parent specified, auto-select first root Package element
        if not parent_id:
            # Use cached model if available, else fetch
            if model is None and project_id:
                try:
                    model = self.client.get_model_structure(project_id)
                except Exception:
                    model = {"elements": []}
            if model:
                for elem in model.get("elements", []):
                    if elem.get("type") in ("Package", "Namespace"):
                        parent_id = elem["id"]
                        break
                if not parent_id and model.get("elements"):
                    parent_id = model["elements"][0]["id"]
        
        if not parent_id:
            return {"success": False, "action": "IMPORT", "error": "No parent element found to insert into. Specify parent_element_id."}

        query = f'''mutation {{
          insertTextualSysMLv2(input:{{
            id:"{mut_id}",
            editingContextId:"{ec_id}",
            objectId:"{parent_id}",
            textualContent:{json.dumps(response.sysml_text)}
          }}) {{
            __typename
            ... on SuccessPayload {{ id }}
            ... on ErrorPayload {{ messages {{ body level }} }}
          }}
        }}'''
        
        result = self.client.graphql(query)
        payload = result.get('data', {}).get('insertTextualSysMLv2', {})
        
        if payload.get('__typename') == 'SuccessPayload':
            return {
                "success": True,
                "action": "IMPORT",
                "mode": response.import_mode,
                "message": response.chat_feedback
            }
        else:
            msgs = payload.get('messages', [])
            err = msgs[0].get('body', 'Unknown error') if msgs else 'Unknown error'
            return {"success": False, "action": "IMPORT", "error": err}

    def _execute_update(self, response: StructuredResponse, ec_id: str, model: dict = None) -> dict:
        """Execute UPDATE action — run each command sequentially."""
        results = []
        all_success = True

        # Build a name→id lookup from the model for smart resolution
        named_by_id = {}
        if model and model.get('named_elements'):
            for name, eid, etype, parent in model['named_elements']:
                named_by_id[eid] = (name, etype, parent)

        for i, cmd in enumerate(response.commands):
            try:
                # SMART RESOLUTION: if updating new_body on a RequirementUsage,
                # auto-redirect to its text child attribute (handler detects AttributeUsage)
                if cmd.type == "UPDATE_ELEMENT" and cmd.new_body and cmd.element_id in named_by_id:
                    target_name, target_type, target_parent = named_by_id[cmd.element_id]
                    if target_type == "RequirementUsage":
                        for name, eid, etype, parent in model['named_elements']:
                            if etype == "AttributeUsage" and name == "text" and parent == target_name:
                                logger.info(f"Auto-resolving RequirementUsage '{target_name}' → text child {eid}")
                                cmd.element_id = eid
                                cmd.new_label = ""
                                break

                if cmd.type == "ADD_CHILD":
                    r = self._exec_add_child(cmd, ec_id)
                elif cmd.type == "UPDATE_ELEMENT":
                    r = self._exec_update_element(cmd, ec_id)
                elif cmd.type == "DELETE_ELEMENT":
                    r = self._exec_delete_element(cmd, ec_id)
                elif cmd.type == "MANAGE_RELATIONSHIP":
                    r = self._exec_manage_relationship(cmd, ec_id)
                else:
                    r = {"success": False, "error": f"Unknown command type: {cmd.type}"}
                
                r['command_index'] = i
                r['command_type'] = cmd.type
                results.append(r)
                if not r.get('success'):
                    all_success = False
            except Exception as e:
                results.append({"success": False, "error": str(e), "command_index": i, "command_type": cmd.type})
                all_success = False

        return {
            "success": all_success,
            "action": "UPDATE",
            "results": results,
            "message": response.chat_feedback if all_success else f"Some commands failed ({sum(1 for r in results if not r.get('success'))}/{len(results)})"
        }

    def _exec_add_child(self, cmd: Command, ec_id: str) -> dict:
        mut_id = str(uuid.uuid4())
        name_part = f',name:{json.dumps(cmd.name)}' if cmd.name else ''
        query = f'''mutation {{
          addChildElement(input:{{
            id:"{mut_id}",
            editingContextId:"{ec_id}",
            parentElementId:"{cmd.parent_element_id}",
            elementType:"{cmd.element_type}"{name_part}
          }}) {{
            __typename
            ... on SuccessPayload {{ id }}
            ... on ErrorPayload {{ messages {{ body level }} }}
          }}
        }}'''
        return self._check_success(query, 'addChildElement')

    def _exec_update_element(self, cmd: Command, ec_id: str) -> dict:
        fields = []
        if cmd.new_label:
            fields.append(f'newLabel:{json.dumps(cmd.new_label)}')
        if cmd.new_short_name:
            fields.append(f'newShortName:{json.dumps(cmd.new_short_name)}')
        if cmd.new_body:
            fields.append(f'newBody:{json.dumps(cmd.new_body)}')
        if cmd.new_value:
            fields.append(f'properties:[{{key:"value",value:{json.dumps(cmd.new_value)}}}]')
        
        mut_id = str(uuid.uuid4())
        query = f'''mutation {{
          updateElement(input:{{
            id:"{mut_id}",
            editingContextId:"{ec_id}",
            elementId:"{cmd.element_id}"{',' + ','.join(fields) if fields else ''}
          }}) {{
            __typename
            ... on SuccessPayload {{ id }}
            ... on ErrorPayload {{ messages {{ body level }} }}
          }}
        }}'''
        return self._check_success(query, 'updateElement')

    def _exec_delete_element(self, cmd: Command, ec_id: str) -> dict:
        mut_id = str(uuid.uuid4())
        query = f'''mutation {{
          deleteElement(input:{{
            id:"{mut_id}",
            editingContextId:"{ec_id}",
            elementId:"{cmd.element_id}"
          }}) {{
            __typename
            ... on SuccessPayload {{ id }}
            ... on ErrorPayload {{ messages {{ body level }} }}
          }}
        }}'''
        return self._check_success(query, 'deleteElement')

    def _exec_manage_relationship(self, cmd: Command, ec_id: str) -> dict:
        mut_id = str(uuid.uuid4())
        targets = json.dumps([t.strip() for t in cmd.target_element_ids.split(',') if t.strip()])
        query = f'''mutation {{
          manageRelationship(input:{{
            id:"{mut_id}",
            editingContextId:"{ec_id}",
            relationshipType:"{cmd.relationship_type}",
            sourceElementId:"{cmd.source_element_id}",
            targetElementIds:{targets},
            action:"{cmd.operation}"
          }}) {{
            __typename
            ... on SuccessPayload {{ messages {{ body level }} }}
            ... on ErrorPayload {{ messages {{ body level }} }}
          }}
        }}'''
        return self._check_success(query, 'manageRelationship')

    def _check_success(self, query: str, mutation_name: str) -> dict:
        """Execute a GraphQL mutation and check for success."""
        result = self.client.graphql(query)
        
        if result.get('errors'):
            return {"success": False, "error": result['errors'][0].get('message', 'GraphQL error')}
        
        payload = result.get('data', {}).get(mutation_name, {})
        if payload.get('__typename') == 'SuccessPayload':
            return {"success": True}
        else:
            msgs = payload.get('messages', [])
            err = msgs[0].get('body', 'Unknown error') if msgs else payload.get('message', 'Unknown error')
            return {"success": False, "error": err}
