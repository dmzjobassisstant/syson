# SysMLv2 Textual Syntax Rules

Reference for generating valid SysMLv2 code. Based on the SysON ANTLR grammar, test fixtures, and the OMG SysMLv2 specification.

**Parser source:** `SysmlToAst` class uses the SysMLv2 ANTLR parser to convert text to EMF elements.
**Grammar file:** `backend/services/syson-direct-edit-grammar/src/main/resources/DirectEdit.g4`
**Test fixtures:** `backend/application/syson-sysml-import/src/test/resources/ASTTransformerTest/`

---

## 1. Core Structure

### Package Declaration
```sysml
package MyPackage {
    // contents
}
```

### Nested Packages
```sysml
package Outer {
    package Inner {
        part def Widget;
    }
}
```

### Named Package (with spaces)
```sysml
package 'My Package Name' {
    part def Widget;
}
```

### Namespace Imports
```sysml
package MyModel {
    import ScalarValues::*;
    import SomePackage::SomeElement;
    private import OtherPackage::**;  // recursive import
    public import Library::*;
}
```

---

## 2. Definitions vs Usages

### Definitions (types/classes)
```sysml
part def Vehicle {
    attribute speed : Integer;
    part engine : Engine;
}

port def PowerPort {
    in voltage : Real;
    out current : Real;
}

item def Product;

attribute def Temperature : ScalarValues::Real;

action def StartEngine {
    in ignitionKey : Boolean;
}
```

**Definition keywords:**
| Keyword | Element Type |
|---------|-------------|
| `part def` | PartDefinition |
| `item def` | ItemDefinition |
| `port def` | PortDefinition |
| `attribute def` | AttributeDefinition |
| `action def` | ActionDefinition |
| `flow def` | FlowConnectionDefinition |
| `connection def` | ConnectionDefinition |
| `interface def` | InterfaceDefinition |
| `requirement def` | RequirementDefinition |
| `constraint def` | ConstraintDefinition |
| `state def` | StateDefinition |
| `occurrence def` | OccurrenceDefinition |
| `enumeration def` | EnumerationDefinition |
| `allocation def` | AllocationDefinition |
| `use case def` | UseCaseDefinition |
| `view def` | ViewDefinition |
| `viewpoint def` | ViewpointDefinition |
| `metadata def` | MetadataDefinition |
| `case def` | CaseDefinition |

### Usages (instances/occurrences)
```sysml
part car : Vehicle;
attribute temperature : Temperature = 20.0;
port powerIn : PowerPort;
flow powerFlow : PowerFlow from powerIn to powerOut;
action start : StartEngine;
```

**Usage keywords:**
| Keyword | Element Type |
|---------|-------------|
| `part` | PartUsage |
| `item` | ItemUsage |
| `port` | PortUsage |
| `attribute` | AttributeUsage |
| `action` | ActionUsage |
| `flow` | FlowConnectionUsage |
| `connection` | ConnectionUsage |
| `interface` | InterfaceUsage |
| `requirement` | RequirementUsage |
| `constraint` | ConstraintUsage |
| `state` | StateUsage |
| `allocation` | AllocationUsage |
| `reference` | ReferenceUsage |
| `perform action` | PerformActionUsage |
| `satisfy` | SatisfyRequirementUsage |

---

## 3. Relationships

### Generalization (inheritance)
```sysml
part def Vehicle;
part def Car :> Vehicle { }          // Car is a subclass of Vehicle
part def Truck :> Vehicle, Loadable { }  // Multiple inheritance
```

### Specialization (alternative syntax)
```sysml
part def Car specializes Vehicle { }
```

### Subclassification
```sysml
part def Part2 :> Part1 {
    attribute attribute2;
}
```

### Redefinition
```sysml
part def Base {
    part wheel;
}
part frontWheel : Base {
    wheel :>> frontWheel;    // redefines the wheel feature
}
```

### Subsetting
```sysml
part vehicle {
    part frontWheel :> wheel;   // frontWheel subsets wheel
}
```

### Feature Typing
```sysml
attribute speed : Integer;        // speed is typed by Integer
part engine : Engine;             // engine is typed by Engine definition
port p : PowerPort;
```

### Dependency
```sysml
dependency from Car to Engine;
```

### Allocation
```sysml
allocation def A;
allocation alloc1 : A {
    part source;
}
```

---

## 4. Attributes and Values

### Simple Attributes
```sysml
attribute speed : Integer;
attribute name : String;
attribute active : Boolean;
```

### With Default Values
```sysml
attribute count : Integer := 0;
attribute pi : Real := 3.14159;
attribute active : Boolean := true;
attribute label : String := "My Label";
```

### With Multiplicity
```sysml
attribute wheels[4];               // exactly 4
attribute sensors[0..*];           // zero or more
attribute seats[2..5];             // between 2 and 5
attribute unique_id[1];            // exactly 1
```

### Measurement Units
```sysml
attribute speed = 100 [km/h];
attribute temperature = 20.0 [Celsius];
```

### Derived/Readonly
```sysml
derived ref attribute y :> init1;
readonly attribute ro;
```

### Variation
```sysml
variation part def V :> A { }
```

---

## 5. Structure and Composition

### Nested Parts
```sysml
part def Vehicle {
    part engine : Engine;
    part transmission : Transmission;
    part wheels[4] : Wheel;

    connection engineToTransmission : EngineToTransmission connect engine::output to transmission::input;
}
```

### Ports and Flows
```sysml
part def ElectricalSystem {
    port powerIn : PowerPort;
    port powerOut : PowerPort;

    flow powerFlow : PowerFlow from powerIn to powerOut;
}
```

### Directional Features
```sysml
port def PowerPort {
    in voltage : Real;
    out current : Real;
    inout dataSignal : Signal;
}
```

---

## 6. Comments and Documentation

### Line Comments
```sysml
// This is a line comment
part def Vehicle;  // inline comment
```

### Block Comments
```sysml
/* This is a
   multi-line comment */
part def Vehicle;
```

### Documentation (structured)
```sysml
part def Vehicle {
    doc /* This vehicle represents a ground transport. */
}
```

### Comment Element
```sysml
comment {
    body = "This is a structured comment";
}
```

---

## 7. Actions and Control Flow

### Action Definitions
```sysml
action def StartEngine {
    in ignitionKey : Boolean;
    out success : Boolean;

    action crank {
        assign success := true;
    }
}
```

### Assignments
```sysml
part def Counter {
    attribute count : Integer := 0;
    action incr {
        assign count := count + 1;
    }
}
```

### Perform Actions
```sysml
action main {
    perform start : StartEngine;
}
```

---

## 8. States and Transitions

### State Definitions
```sysml
state def VehicleState {
    entry; then Off;

    state Off;
    state Starting;
    state Running;

    transition first from Off to Starting when ignitionKey == true;
    transition from Starting to Running after 3 [s];
    transition from Running to Off when ignitionKey == false;
}
```

### Parallel States
```sysml
state s parallel {
    state s1;
    state s2;
}
```

### Transition Syntax
```
transition <name> from <source> to <target> [when <guard>] [/ <effect>]
```
- `trigger`: event name(s) separated by `|`
- `guard`: `[expression]`
- `effect`: `/ actionName`

---

## 9. Requirements

### Requirement Definition
```sysml
requirement def MassLimit {
    attribute maxMass : Mass;
    constraint { mass <= maxMass }
}
```

### Requirement Usage
```sysml
requirement req1 : MassLimit {
    attribute maxMass := 1500 [kg];
}
```

### Satisfaction
```sysml
part car : Vehicle {
    satisfy req1 by car;
}
```

### Assertion
```sysml
assert satisfy req1 by car;
```

---

## 10. Constraints and Expressions

### Constraint Definition
```sysml
constraint def PositiveValue {
    in x : Real;
    x > 0
}
```

### Inline Constraint
```sysml
part def Vehicle {
    attribute mass : Real;
    constraint { mass > 0 }
}
```

### Expression Operators
```
+  -  *  /  %  **       Arithmetic
==  !=  <  >  <=  >=   Comparison
and  or  not  xor       Logical
:=                      Assignment
```

---

## 11. Enumerations

```sysml
enumeration def Color {
    Red;
    Green;
    Blue;
}
```

### Usage
```sysml
attribute color : Color := Color::Red;
```

---

## 12. Aliases

```sysml
package Definitions {
    part def Vehicle;
    alias Car for Vehicle;
}

package Usages {
    public import Definitions::Car;
    part vehicle : Car;
}
```

---

## 13. Visibility Modifiers

```sysml
package VisibilityTest {
    private part def InternalPart { }
    public part def ExportedPart { }
    protected part def BasePart {
        private in y : SomeType;
    }
}
```

---

## 14. Abstract, Variation, Individual

```sysml
abstract part def Shape;              // Cannot be instantiated directly
variation part def VehicleVariant :> Vehicle { }
individual occurrence def SerialNumber001 { }
```

### Keywords
| Keyword | Purpose |
|---------|---------|
| `abstract` | Type cannot be instantiated |
| `variation` | Type is a variation point |
| `variant` | Element is a variant |
| `individual` | Unique instance |
| `occurrence` | Time-based occurrence |
| `readonly` | Feature cannot be modified |
| `derived` | Feature is computed |
| `composite` | Feature is composite (owned) |
| `ref` | Feature is reference (not owned) |

---

## 15. Complete Model Example

```sysml
package Transportation {
    import ScalarValues::*;

    // Definitions
    part def Vehicle {
        attribute maxSpeed : Integer;
        attribute mass : Real;
        part engine : Engine;
        part wheels[4] : Wheel;

        constraint { mass > 0 }
    }

    part def Engine {
        attribute power : Real;
        attribute fuelType : String;
    }

    part def Wheel {
        attribute diameter : Real;
    }

    part def Car :> Vehicle {
        attribute doorCount : Integer;
        port fuelPort : FuelPort;
    }

    port def FuelPort {
        in fuelFlow : Real;
    }

    // Requirements
    requirement def SpeedLimit {
        attribute maxSpeed : Integer;
        constraint { maxSpeed <= 200 }
    }

    // Usages
    part myCar : Car {
        attribute maxSpeed := 180;
        attribute doorCount := 4;
        satisfy speedReq by myCar;
    }

    requirement speedReq : SpeedLimit {
        attribute maxSpeed := 200;
    }

    // Dependency
    dependency from myCar to myCar.engine;
}
```

---

## 16. Parser Validation Rules

Based on `ASTTransformerTest.java` test cases:

### Valid constructs:
- Nested packages with imports
- Forward references within the same package
- Circular package imports (Pkg1 imports Pkg2, Pkg2 imports Pkg1)
- Aliases to qualified names
- Feature typing on port definitions
- Redefinition of inherited features (`:>>`)
- Multiplicity with nonunique/ordered modifiers

### Will produce errors:
- Referencing a type that doesn't exist anywhere (`part p : FakeType;` → proxy resolution error)
- Missing semicolons between top-level declarations within a package body
- Invalid keyword usage (e.g., `partdef` instead of `part def`)

### SysMLv2 validation endpoint:
```bash
POST /api/v1/sysml/validate
Content-Type: application/json

{"code": "package Test { part def Vehicle; }"}
```

---

## 17. Qualified Names

Use `::` for namespace separation:

```sysml
import ScalarValues::Integer;
part engine : Transportation::Engine;
part wheel : 'Transportation'::'Wheel';
```

Use single quotes for names with spaces:
```sysml
package 'My Package' {
    part def 'My Part Definition';
}
```

---

## 18. Operator Reference

| Operator | Meaning | Example |
|----------|---------|---------|
| `:` | Type binding | `part x : Type` |
| `:>` | Subsets / generalization | `part def B :> A` |
| `:>>` | Redefines | `b :>> a` |
| `:=` | Assignment | `assign x := 5` |
| `=` | Default value | `attribute x := 5` |
| `[0..*]` | Multiplicity bounds | `attribute items[0..*]` |
| `::` | Namespace separator | `Pkg::Element` |
| `.` | Feature chain | `car.engine.power` |
| `<shortName>` | Short name | `<SN> Vehicle` |
