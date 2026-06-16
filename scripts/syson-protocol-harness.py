#!/usr/bin/env python3
"""
SysON Sirius Web Protocol Test Harness (v2)
===========================================
Communicates with SysON exactly as the AI chat module would via the Sirius Web
GraphQL + subscription protocol.

Protocol discoveries (2026-06-15):
  - editingContextId in mutations = semantic_data UUID (NOT project UUID)
  - objectId in insertTextualSysMLv2 = XMI id from document.content[0].id
  - project UUID is only used in GraphQL viewer queries, not in mutations
  - insertTextualSysMLv2 requires editing context to be warm (query it first)

Flow:
  1. Authenticate via JWT
  2. Create project from SysMLv2 template → get project UUID
  3. Query DB for semantic_data UUID and root namespace XMI id
  4. Warm the editing context (query explorerDescriptions)
  5. Send insertTextualSysMLv2 mutation with SysML text
  6. Verify via DB that document content has new elements
"""

import base64, hashlib, hmac, json, os, subprocess, time, urllib.request, uuid, sys
from datetime import datetime

# ── CONFIG ────────────────────────────────────────────────────────────────
BASE_URL = os.environ.get('SYSON_BASE_URL', 'http://localhost:8080')
GRAPHQL_URL = f'{BASE_URL}/api/graphql'
JWT_SECRET = os.environ.get('SYSON_JWT_SECRET', 'changeme-please-override-in-production')
ADMIN_EMAIL = os.environ.get('SYSON_TEST_USER', 'regression-admin')
ADMIN_PASSWORD = os.environ.get('SYSON_ADMIN_PASSWORD', os.environ.get('SYSON_TEST_PASSWORD', 'RegressionAdmin2026!'))
TEMPLATE_ID = 'sysmlv2-template'
DEBUG = os.environ.get('SYSON_DEBUG', '1') == '1'

# ── AUTH ──────────────────────────────────────────────────────────────────
def make_jwt(user_id='00000000-0000-0000-0000-000000000001'):
    key = hashlib.sha256(JWT_SECRET.encode()).digest()
    now = int(time.time())
    def b64(o):
        return base64.urlsafe_b64encode(json.dumps(o, separators=(',', ':')).encode()).rstrip(b'=').decode()
    msg = b64({'alg': 'HS256', 'typ': 'JWT'}) + '.' + b64({
        'tenantId': '00000000-0000-0000-0000-000000000001',
        'userId': user_id,
        'sub': ADMIN_EMAIL,
        'iat': now,
        'exp': now + 86400,
    })
    return msg + '.' + base64.urlsafe_b64encode(hmac.new(key, msg.encode(), hashlib.sha256).digest()).rstrip(b'=').decode()

# ── GRAPHQL CLIENT ────────────────────────────────────────────────────────
class SysONClient:
    def __init__(self, token):
        self.token = token
        self.log_lines = []
    
    def log(self, msg, level='INFO'):
        ts = datetime.now().strftime('%H:%M:%S.%f')[:12]
        line = f'[{level:7s}] [{ts}] {msg}'
        print(line)
        self.log_lines.append(line)
    
    def gql(self, query, variables=None, op_name=None):
        """Execute a GraphQL request. Returns (data, errors) tuple."""
        body = {'query': query}
        if variables:
            body['variables'] = variables
        if op_name:
            body['operationName'] = op_name
        if DEBUG:
            self.log(f'GQL: {op_name or query[:80].strip()}')
        req = urllib.request.Request(GRAPHQL_URL,
            data=json.dumps(body).encode(),
            headers={
                'content-type': 'application/json',
                'authorization': f'Bearer {self.token}',
            },
            method='POST')
        try:
            resp = json.loads(urllib.request.urlopen(req).read())
        except Exception as e:
            return None, [{'message': str(e)}]
        return resp.get('data'), resp.get('errors')
    
    # ── PROJECT ───────────────────────────────────────────────────────
    def create_project(self):
        """Create a new project from the SysMLv2 template."""
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
        variables = {"input": {
            "id": str(uuid.uuid4()),
            "templateId": TEMPLATE_ID,
        }}
        data, errors = self.gql(mutation, variables, 'CreateProject')
        if errors:
            self.log(f'Create project GraphQL errors: {errors}', 'ERROR')
            return None
        result = data.get('createProjectFromTemplate', {})
        if result.get('__typename') == 'CreateProjectFromTemplateSuccessPayload':
            pid = result['project']['id']
            self.log(f'Project created: {pid}', 'OK')
            return pid
        self.log(f'Create project failed: {result}', 'ERROR')
        return None
    
    # ── CONTEXT ──────────────────────────────────────────────────────
    def get_context_ids(self, project_id):
        """Get the semantic_data UUID and root namespace XMI id from the DB."""
        try:
            r = subprocess.run([
                'sudo', '-u', 'postgres', 'psql', '-d', 'syson', '-At', '-c',
                f"select psd.semantic_data_id, d.content::jsonb #>> '{{content,0,id}}' "
                f"from document d "
                f"join project_semantic_data psd on psd.semantic_data_id = d.semantic_data_id "
                f"where psd.project_id = '{project_id}' and d.name like '%.sysml' limit 1;"
            ], capture_output=True, text=True, timeout=10)
            ec_id, root_xmi = r.stdout.strip().split('|')
            self.log(f'Semantic data ID: {ec_id}', 'OK')
            self.log(f'Root XMI id: {root_xmi}', 'OK')
            return ec_id, root_xmi
        except Exception as e:
            self.log(f'Failed to get context IDs: {e}', 'ERROR')
            return None, None
    
    def warm_editing_context(self, ec_id):
        """Force the editing context to load by querying it."""
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
        data, errors = self.gql(query, {'ecId': ec_id}, 'WarmEC')
        if errors:
            self.log(f'Warm EC warnings: {errors}', 'WARN')
        ec = (data or {}).get('viewer', {}).get('editingContext')
        if ec:
            self.log(f'Editing context warmed: {ec["id"]}', 'OK')
            return True
        self.log('Failed to warm editing context', 'ERROR')
        return False
    
    # ── INSERT TEXTUAL SYSM ───────────────────────────────────────────
    def insert_sysml(self, ec_id, root_xmi, sysml_text):
        """Insert textual SysML into the model via insertTextualSysMLv2 mutation."""
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
        variables = {"input": {
            "id": str(uuid.uuid4()),
            "editingContextId": ec_id,
            "objectId": root_xmi,
            "textualContent": sysml_text,
        }}
        data, errors = self.gql(mutation, variables, 'InsertSysML')
        if errors:
            self.log(f'Insert GraphQL errors: {errors}', 'ERROR')
            return None
        result = data.get('insertTextualSysMLv2', {})
        typename = result.get('__typename')
        messages = result.get('messages', [])
        for m in messages:
            self.log(f'  {m["level"]}: {m["body"]}', 'DEBUG')
        if typename == 'SuccessPayload':
            self.log(f'Insert succeeded (id={result["id"]})', 'OK')
            return True
        self.log(f'Insert failed: {result.get("message", typename)}', 'ERROR')
        return False
    
    def verify_model(self, project_id, expected_keyword):
        """Verify the document content contains expected elements."""
        try:
            r = subprocess.run([
                'sudo', '-u', 'postgres', 'psql', '-d', 'syson', '-At', '-c',
                f"select length(d.content), d.content::jsonb #>> '{{content,1,eClass}}' "
                f"from document d "
                f"join project_semantic_data psd on psd.semantic_data_id = d.semantic_data_id "
                f"where psd.project_id = '{project_id}' and d.name like '%.sysml' limit 1;"
            ], capture_output=True, text=True, timeout=10)
            size, second_elem = r.stdout.strip().split('|')
            self.log(f'Document size: {size} chars, element[1]: {second_elem}', 'OK')
            # Check content
            r2 = subprocess.run([
                'sudo', '-u', 'postgres', 'psql', '-d', 'syson', '-At', '-c',
                f"select count(*) > 0 from document d "
                f"join project_semantic_data psd on psd.semantic_data_id = d.semantic_data_id "
                f"where psd.project_id = '{project_id}' and d.content::text like '%{expected_keyword}%';"
            ], capture_output=True, text=True, timeout=10)
            if r2.stdout.strip() == 't':
                self.log(f'Model verified: found "{expected_keyword}"', 'OK')
                return True
            self.log(f'Model NOT verified: "{expected_keyword}" not found', 'ERROR')
            return False
        except Exception as e:
            self.log(f'Verify failed: {e}', 'ERROR')
            return False


# ── SYSML MODEL DEFINITION ────────────────────────────────────────────────
COOLING_FAN_SYSML = r"""package CoolingFanSystem {
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
}"""


# ── MAIN ──────────────────────────────────────────────────────────────────
def main():
    print('=' * 70)
    print('SysON Sirius Web Protocol Test Harness (v2)')
    print('=' * 70)
    
    client = SysONClient(make_jwt())
    
    # 1. Create project
    project_id = client.create_project()
    if not project_id:
        return 1
    
    # 2. Get semantic_data UUID and root XMI id  
    time.sleep(1)
    ec_id, root_xmi = client.get_context_ids(project_id)
    if not ec_id or not root_xmi:
        return 1
    
    # 3. Warm the editing context
    if not client.warm_editing_context(ec_id):
        return 1
    
    # 4. Insert SysML model
    print()
    print('─' * 70)
    print(f'Inserting Cooling Fan System model...')
    print('─' * 70)
    if not client.insert_sysml(ec_id, root_xmi, COOLING_FAN_SYSML):
        return 1
    
    # 5. Verify model in DB
    time.sleep(1)
    print()
    print('─' * 70)
    print('Verifying model...')
    print('─' * 70)
    if not client.verify_model(project_id, 'CoolingFanAssembly'):
        return 1
    
    # 6. Summary
    print()
    print('=' * 70)
    print('✅ HARNESS COMPLETE')
    print('=' * 70)
    print(f'  Project ID:     {project_id}')
    print(f'  EC UUID:        {ec_id}')
    print(f'  Root XMI:       {root_xmi}')
    print(f'  Diagram:        http://localhost:8080/?projectId={project_id}')
    print(f'  Live:           https://syson.damuza-consulting.com/?projectId={project_id}')
    print()
    
    # Save state for screenshot script
    state = {
        'project_id': project_id,
        'ec_id': ec_id,
        'root_xmi': root_xmi,
        'base_url': BASE_URL,
        'live_url': 'https://syson.damuza-consulting.com',
    }
    with open('/tmp/syson_harness_state.json', 'w') as f:
        json.dump(state, f, indent=2)
    client.log('State saved to /tmp/syson_harness_state.json', 'OK')
    
    return 0


if __name__ == '__main__':
    sys.exit(main())
