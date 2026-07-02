# SysML Agent — Structured Output Specification

When the agent decides to take action, it MUST respond with one of these structured XML blocks.
Only ONE block should be present per response.

## 1. Thinking / Analysis (informational, shown to user)

```xml
<thinking>
Brief reasoning about the request and what approach to take.
</thinking>
```

## 2. Execute SysML Import (load new model or library)

```xml
<syson-response>
  <action>IMPORT</action>
  <chat_feedback>Human-readable explanation of what will be imported</chat_feedback>
  <import_mode>append|replace|library</import_mode>
  <parent_element_id>PARENT_GRAPH_ID_OR_EMPTY</parent_element_id>
  <sysml_text>
package MyPackage {
    part def Vehicle {
        attribute maxSpeed : ScalarValues::Real = 200;
        part engine : Engine;
    }
}
  </sysml_text>
</syson-response>
```

- `import_mode`: "append" = add to existing model, "replace" = wipe and replace, "library" = load as reusable library
- `parent_element_id`: The graph @id of the parent element to insert into. Empty = create new root.
- `sysml_text`: Valid SysML v2 textual notation

## 3. Execute API Commands (incremental update)

```xml
<syson-response>
  <action>UPDATE</action>
  <chat_feedback>Human-readable explanation of what will be updated</chat_feedback>
  <commands>
    <command>
      <type>ADD_CHILD</type>
      <parent_element_id>GRAPH_ID_OF_PARENT</parent_element_id>
      <element_type>PartUsage</element_type>
      <name>NewPart</name>
    </command>
    <command>
      <type>UPDATE_ELEMENT</type>
      <element_id>GRAPH_ID_OF_ELEMENT</element_id>
      <new_label>RenamedElement</new_label>
      <new_short_name>shortName</new_short_name>
      <new_body>Documentation text</new_body>
    </command>
    <command>
      <type>DELETE_ELEMENT</type>
      <element_id>GRAPH_ID_OF_ELEMENT</element_id>
    </command>
    <command>
      <type>MANAGE_RELATIONSHIP</type>
      <relationship_type>Dependency</relationship_type>
      <source_element_id>GRAPH_ID</source_element_id>
      <target_element_ids>GRAPH_ID_1,GRAPH_ID_2</target_element_ids>
      <operation>ADD</operation>
    </command>
  </commands>
</syson-response>
```

## 4. Ask for Clarification

```xml
<syson-response>
  <action>CLARIFY</action>
  <chat_feedback>Question to ask the user for more information</chat_feedback>
</syson-response>
```

## Command Types Reference

| type | Required Fields | Description |
|------|----------------|-------------|
| ADD_CHILD | parent_element_id, element_type, name | Create a child element |
| UPDATE_ELEMENT | element_id, (new_label \| new_short_name \| new_body) | Rename/modify element |
| DELETE_ELEMENT | element_id | Delete element |
| MANAGE_RELATIONSHIP | relationship_type, source_element_id, target_element_ids, operation | Add/remove relationship |

## Element Types (for ADD_CHILD)
PartUsage, Package, AttributeUsage, ItemUsage, PortUsage, ActionUsage, StateUsage,
RequirementUsage, ConstraintUsage, ConnectionUsage, FlowConnectionUsage, InterfaceUsage

## Relationship Types (for MANAGE_RELATIONSHIP)
Dependency, Subclassification, Specialization
