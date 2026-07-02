# SysMLv2 Textual Syntax — Comprehensive Reference for LLM Code Generation

> Extracted from the **Eclipse SysON** codebase (`/root/syson-fork/backend`).
> This document is designed to help an LLM generate **syntactically valid** SysMLv2 textual code.

---

## 1. Architecture: How SysON Parses SysMLv2

SysON does **not** embed its own SysMLv2 grammar (ANTLR `.g4`). Instead it uses a **two-phase pipeline**:

| Phase | Component | Location |
|-------|-----------|----------|
| 1. Text → JSON AST | External Node.js tool (`syside-cli.js`) invoked via `SysmlToAst.java` | `syson-sysml-import/.../SysmlToAst.java` |
| 2. JSON AST → EMF model | `AstTreeParser` + `EClassifierTranslator` + `EAttributeTranslator` + `EReferenceComputer` | `syson-sysml-import/.../parser/` |

**Key files:**
- `syson-sysml-import/src/main/java/org/eclipse/syson/sysml/SysmlToAst.java` — shells out to `node syside-cli.js dump <file>`, captures JSON stdout
- `syson-sysml-import/src/main/java/org/eclipse/syson/sysml/ASTTransformer.java` — orchestrates the JSON→EMF conversion, runs fixing phases
- `syson-sysml-import/src/main/java/org/eclipse/syson/sysml/parser/AstTreeParser.java` — walks JSON tree, creates EMF objects
- `syson-sysml-import/src/main/java/org/eclipse/syson/sysml/parser/translation/EClassifierTranslator.java` — maps `$type` strings to EClasses (e.g. `"PartDefinition"` → `PartDefinition`)
- `syson-sysml-import/src/main/java/org/eclipse/syson/sysml/parser/translation/EAttributeTranslator.java` — maps JSON keys to EAttributes, handles special boolean/string translations
- `services/syson-direct-edit-grammar/src/main/resources/DirectEdit.g4` — ANTLR grammar for **direct editing of labels** (a subset of the full syntax)
- `metamodel/syson-sysml-metamodel/src/main/resources/model/sysml.ecore` — the full EMF metamodel
- `application/syson-sysml-validation/src/main/java/org/eclipse/syson/sysml/validation/SysMLv2ValidationRules.java` — ~399 OMG validation rules in AQL
- `application/syson-application/src/main/java/org/eclipse/syson/chat/SysmlSyntaxValidator.java` — lightweight regex validator used by the AI chat feature

**The authoritative grammar** for the full language is the OMG SysMLv2 textual notation, implemented by the `syside-cli.js` tool (from the [sysml-2ls](https://github.com/sensmetry/sysml-2ls) project). SysON relies on it for parsing and only implements the EMF model construction layer on top.

---

## 2. Complete Keyword List

Keywords are scattered across three sources. The most complete set is in `SysmlSyntaxValidator.java`:

### Core keywords (from `SysmlSyntaxValidator.KNOWN_KEYWORDS`)

```
abstract accept action actor alias all allocate analysis and as assert assign assume attribute
becoming binding block by calc case classifier comment compose conjugate connect connection
constraint decision def default define dependency derivation derived determination difference
disjoint do doc documentation else end enum equals exhibit existential exit expression feature
filter first flow for forall fork function hastype if import in include individual inout
intersection invariant is item join language loop member merge metadata model namespace nonunique
not occurrence of or ordered out package part perform port portion private public readonly real
redefines reduction ref reference refine require requirement return satisfy send sequence
snapshot source specialization state step struct subclassification subject subsets succession
symmetric target then time to trace transition true type union use useCase value variation
verify view viewpoint while
```

### Prefix keywords (from `DirectEdit.g4` + `LabelConstants.java`)

```
abstract    variation    variant    readonly    derived    end    ref    in    out    inout
default     ordered      nonunique
```

### Reserved relationship/operators (from `DirectEdit.g4` lexer)

| Operator | Symbol | Usage |
|----------|--------|-------|
| `:>` | SUBSETS_OP / SUBCLASSIFICATION | subsetting, generalization (`:> ParentType`) |
| `:>>` | REDEFINES_OP | redefinition (`:>> featureName`) |
| `:=` | ASSIGN_OP | assignment (`:= value`) |
| `::` | NAMESPACE_SEP | qualified name separator (`Pkg::Element`) |
| `::>` | REFERENCES | reference subsetting |
| `..` | DOTDOT | multiplicity range (`[1..*]`) |
| `~` | CONJUGATED | conjugated port (`~PortType`) |
| `=` | EQUALS | default value (`= 0`) |
| `==` `!=` `<` `>` `<=` `>=` | comparison | expression operators |
| `+` `-` `*` `/` `%` `**` | arithmetic | expression operators |
| `&` `\|` `xor` | bitwise/logical | expression operators |

### Visibility modifiers
```
private  public  protected
```

---

## 3. Element Kinds: Definitions vs Usages

SysMLv2 has a **definition/usage duality**. Every major concept has a `<concept> def` (definition) and a bare `<concept>` (usage).

### Full element kind list (from `sysml.ecore`)

| Definition keyword | Usage keyword | EClass(es) |
|-------------------|---------------|------------|
| `part def` | `part` | PartDefinition, PartUsage |
| `item def` | `item` | ItemDefinition, ItemUsage |
| `attribute def` | `attribute` | AttributeDefinition, AttributeUsage |
| `port def` | `port` | PortDefinition, PortUsage |
| `interface def` | `interface` | InterfaceDefinition, InterfaceUsage |
| `connection def` | `connection` | ConnectionDefinition, ConnectionUsage |
| `flow def` / `flow ... of` | `flow` | FlowConnectionDefinition, FlowConnectionUsage |
| `action def` | `action` | ActionDefinition, ActionUsage |
| `state def` | `state` | StateDefinition, StateUsage |
| `requirement def` | `requirement` | RequirementDefinition, RequirementUsage |
| `constraint def` | `constraint` | ConstraintDefinition, ConstraintUsage |
| `use case def` | `use case` | UseCaseDefinition, UseCaseUsage |
| `enum def` | `enum` (literals) | EnumerationDefinition, EnumerationUsage |
| `allocation def` | `allocation` | AllocationDefinition, AllocationUsage |
| `analysis case def` | `analysis case` | AnalysisCaseDefinition, AnalysisCaseUsage |
| `verification case def` | `verification case` | VerificationCaseDefinition, VerificationCaseUsage |
| `calculation def` | `calculation` / `calc` | CalculationDefinition, CalculationUsage |
| `concern def` | `concern` | ConcernDefinition, ConcernUsage |
| `view def` | `view` | ViewDefinition, ViewUsage |
| `viewpoint def` | `viewpoint` | ViewpointDefinition, ViewpointUsage |
| `rendering def` | `rendering` | RenderingDefinition, RenderingUsage |
| `occurrence def` | `occurrence` | OccurrenceDefinition, OccurrenceUsage |
| `metadata def` | `metadata` | MetadataDefinition, MetadataUsage |
| `classifier def` | `classifier` | (metaclass level) |
| — | `perform action` | PerformActionUsage |
| — | `exhibit state` | ExhibitStateUsage |
| — | `snapshot` | OccurrenceUsage (portion) |
| — | `timeslice` | OccurrenceUsage (portion) |
| — | `include use case` | IncludeUseCaseUsage |
| — | `satisfy ... by` | SatisfyRequirementUsage |
| — | `transition` | TransitionUsage |
| — | `alias ... for` | (Membership alias) |

---

## 4. Core Syntax Patterns

### 4.1 Package & Namespace

```
package PackageName {
    // members go here, each terminated with ;
}
```

- **Anonymous package** is allowed: `package { ... }`
- **Names with spaces** use single quotes: `package 'My Package' { ... }`
- Packages can be **nested**: `package Outer { package Inner { ... } }`

*Source: `convertImportTest/import.sysml`, `convertSubclassificationTest/subclassification.sysml`*

### 4.2 Definitions (the `def` pattern)

```
part def Name {
    // features (nested usages)
}

part def Name :> Parent1, Parent2 {
    // features
}

abstract part def Name { ... }

variation part def Name :> Parent { ... }
```

**General pattern:**
```
[modifiers] <kind> def <Name> [:> <Supertypes>] [{ body }]
```

*Source: `convertSubclassificationTest/subclassification.sysml`, Batmobile.sysml*

### 4.3 Usages (composite features)

```
part myPart : PartDefinition;
part myPart { ... }     // anonymous-typed composite part
part wheels : Wheel [4];  // multiplicity
```

**General pattern:**
```
[modifiers] <kind> <name> [: <Type>] [multiplicity] [:> <subsettedFeature>] [{ body }] ;
```

*Source: Batmobile.sysml lines 71-87*

### 4.4 Feature Typing (`:`)

```
attribute speed : Real;
part engine : Engine;
port powerPort : PowerIP;
```

### 4.5 Specialization / Generalization (`:>`)

```
part def Batmobile :> Vehicle, System { ... }
part def BatmobileNG :> Batmobile { ... }
```

Multiple supertypes separated by commas.

**IMPORTANT:** Use `:>` for specialization, NOT `:` (single colon is for typing).

*Source: `SysmlSyntaxValidator` Rule 4*

### 4.6 Subsetting (`:>`)

```
part frontLeftWheel :> wheels;
attribute actualSpeed :> ISQ::speed;
```

Subsetting means "this feature subsets/extends that feature."

### 4.7 Redefinition (`:>>`)

```
b :>> a;                          // redefines feature a from supertype
attribute :>> length :> ISQ::length = 80 [SI::cm];
part :>> batmobileEngine : EngineChoices;
```

*Source: `convertRedefinesTest/redefines.sysml`, `convertInheritanceTest/inheritance.sysml`, Batmobile.sysml*

### 4.8 Reference Subsetting (`:>>` / `::>`)

```
attribute packetSecondaryHeader' redefines packetHeader;
```

*Source: `convertRedefinesTest/redefines.sysml`*

### 4.9 Multiplicity

```
part seat [2];              // exactly 2
part wheels : Wheel [4];    // 4 of type Wheel
part systems : System [*];  // unbounded
attribute items : Type [0..*]; // range
part p [1..10];             // bounded range
```

Bracket syntax: `[lowerBound..upperBound]` where bounds are integers or `*`.

### 4.10 Feature Direction (in/out/inout)

```
in item cmd : EngineCommand;
out item status : StatusKind;
in port powerIn : Real;
out port result : Real;
inout port dataPort : DataType;
```

**IMPORTANT:** Direction comes BEFORE the element kind: `in port`, NOT `port in`.

*Source: `SysmlSyntaxValidator` Rule 5, Batmobile.sysml*

### 4.11 Conjugated Ports (`~`)

```
end consumerPort : ~PowerIP;
port enginePort : ~PowerIP;
```

The `~` prefix denotes the conjugated (direction-reversed) port definition.

*Source: Batmobile.sysml*

---

## 5. Attributes & Values

### 5.1 Attribute Definition & Usage

```
attribute def Mass {
    attribute <nm> num;     // attribute with short name
}

attribute actualSpeed :> ISQ::speed;
attribute count : ScalarValues::Integer := 0;
attribute realName = "Jon Holt";
attribute default1 default = 10;
readonly attribute ro;
derived ref attribute y :> init1;
```

### 5.2 Value Assignment

| Operator | Meaning | Example |
|----------|---------|---------|
| `=` | default value | `attribute x = 5;` |
| `:=` | assignment (initial/runtime) | `assign count := 1;` |
| `default =` | explicit default | `attribute x default = 10;` |

### 5.3 Measurement Units

```
attribute :>> length :> ISQ::length = 80 [SI::cm];
attribute :>> battery.capacity = 40000 [SI::'watt hour'];
```

Units in square brackets `[unit]` after the value.

*Source: Batmobile.sysml, `assignment*.sysml`*

---

## 6. Documentation & Comments

### 6.1 Line Comments
```
// This is a line comment
```

### 6.2 Block Comments / Documentation (`doc`)

```
part def Vehicle {
    doc
    /*
     * Multi-line documentation text.
     */
}
```

The `doc` keyword introduces a `Documentation` element. The body is wrapped in `/* ... */`.

### 6.3 Triple-slash comments
```
/// Documentation comment (doc form)
```

*Source: Batmobile.sysml (lines 4-13, 52, 176-181, 203-207), `convertDocumentationTest`*

---

## 7. Import & Namespace Usage

### 7.1 Namespace Import (import all members)

```
private import ScalarValues::*;
public import Definitions::Car;
private import Pkg2::Pkg21::Pkg211::P211;
private import Pkg2::Pkg21::*;
private import Pkg211::*::**;     // recursive import
private import q::**;             // recursive with :: syntax
```

### 7.2 Import syntax pattern

```
[visibility] import <QualifiedName>[::* | ::** | ::SpecificName] ;
```

- `::*` — import all public members
- `::**` — recursive import (includes nested packages)
- Visibility: `private` (default for top-level), `public`, `protected`

**IMPORTANT:** Top-level imports MUST be `private` (validation rule `validateImportTopLevelVisibility`).

*Source: `convertImportTest/import.sysml`, `convertNamespaceImportTest/`*

### 7.3 Alias

```
alias Car for Vehicle;
alias alias1 for q::req1;
```

*Source: `convertAliasTest/alias.sysml`*

---

## 8. Relationships

### 8.1 Dependency
```
dependency DepName from Source to Target;
```

### 8.2 Generalization (in definition)
```
part def Child :> Parent { }
```

### 8.3 Succession (temporal ordering)

Used in actions/states for flow control:
```
first start;
then action step1;
then action step2;
then done;
```

### 8.4 Connection / Interface connection

```
interface bat2eng : PowerInterface connect battery.powerPort to batmobileEngine.enginePort;
```

```
interface def PowerInterface {
    end supplierPort : PowerIP;
    end consumerPort : ~PowerIP;
    flow of Power from supplierPort.power to consumerPort.power;
}
```

### 8.5 Flow

```
flow of Power from supplierPort.power to consumerPort.power;
flow powerFlow : Power from a to b;
```

*Source: Batmobile.sysml (lines 27-31, 86)*

### 8.6 Satisfy Requirement

```
satisfy batmobileSpecification by batmobileDesignV23;
not satisfy req2 by p;
assert satisfy req1 by q;
```

*Source: `convertBooleanTest/boolean.sysml`*

---

## 9. Enumeration

```
enum def StatusKind {
    enum safe;
    enum alert;
}
```

**IMPORTANT:** Use `enum def Name { ... }` with `enum` literals. Do NOT use `enum Name { a, b, c }` (validated and rejected).

*Source: Batmobile.sysml (lines 144-147), `SysmlSyntaxValidator` Rule 3*

---

## 10. Actions & Performance

### 10.1 Action Definition with control flow

```
action def 'Drive Batmobile' {
    first start;
    then action startBatmobile;
    then action scanEnvironment {
        out status : StatusKind;
    }
    then decide;
        if scanEnvironment.status == StatusKind::safe then 'Switch to standard mode';
        if scanEnvironment.status == StatusKind::alert then 'Switch to alert mode';
    action 'Switch to standard mode';
    then endOfStatusCheck;
    action 'Switch to alert mode';
    then endOfStatusCheck;
    merge endOfStatusCheck;
    then done;
}
```

### 10.2 Perform Action

```
part def BatmobileEngine {
    perform action rocketBoost {
        in cmd : EngineCommand = engineControl.cmd;
    }
}
```

### 10.3 Assignment Action

```
action incr {
    assign count := 1;
}
```

### 10.4 Simple action succession

```
action def ActivateRocketBooster :> 'Activate rocket booster' {
    first start;
    then action prepareBoost;
    then action activateBoost;
    then done;
}
```

*Source: Batmobile.sysml (lines 128-169), `assignment*.sysml`*

---

## 11. States & Transitions

### 11.1 State Definition with substates

```
state def StateMachine {
    entry action;
    do action;
    exit action;

    state s1;
    state s2;

    transition t1 first s1 then s2;
    transition t2 first s2 then s1;
}
```

### 11.2 Parallel states

```
state s parallel {
    state s1;
    state s2;
}
```

### 11.3 Transition with trigger/guard/effect

```
transition first sourceState accept Signal then targetState;
transition first sourceState [guardExpression] then targetState;
transition first sourceState do effectAction then targetState;
transition first sourceState accept Signal [guard] do effect then targetState;
```

Transition features: `accept` (trigger), `[expr]` (guard), `do` (effect).

**IMPORTANT:** Transitions with `accept Signal` require properly modeled trigger definitions.

*Source: `convertBooleanTest/boolean.sysml`, Batmobile.sysml, `SysmlSyntaxValidator` Rule 10*

---

## 12. Constraints & Requirements

### 12.1 Constraint Usage

```
constraint {
    actualWeight <= 0.25 [nm]
}
```

```
assert constraint {
    batmobileEngine == batmobileEngine::xEngine and wheels == wheels.xWheel
}
```

### 12.2 Requirement Definition

```
requirement def VehicleMaxSpeed {
    doc
    /*
     * The actual speed of the vehicle shall
     * be less or equal than the maximum speed.
     */
    subject vehicle : Vehicle;
    stakeholder pm : ProductManagement;
    attribute maxSpeed :> ISQ::speed;
    require constraint { vehicle.actualSpeed <= maxSpeed }
}
```

### 12.3 Requirement Usage with identification

```
requirement batmobileSpecification {
    requirement <REQ42> batmobileMaxSpeed : VehicleMaxSpeed {
        attribute :>> maxSpeed = 230 [SI::'km/h'];
    }
    requirement <REQ43> batmobileAcceleration;
}
```

The `<REQ42>` is a **requirement identifier** (alias/short name).

### 12.4 Subject, Stakeholder, Actor

```
subject vehicle : Vehicle;
stakeholder pm : ProductManagement;
actor driver : Batman;
```

### 12.5 Assume / Assert

```
assume constraint { ... }
assert constraint { ... }
require constraint { ... }
```

*Source: Batmobile.sysml (lines 175-196)*

---

## 13. Use Cases & Concerns

### 13.1 Use Case Definition

```
use case def 'Activate rocket booster' {
    subject bm : Batmobile;
    actor driver : Batman;
    objective {
        doc
        /*
         * The driver wants to activate
         * the rocket booster to increase
         * the speed extremely.
         */
    }
}
```

### 13.2 Concern

```
concern 'Reduce the number of special parts' {
    doc
    /*
     * Reduce the number of special parts...
     */
    stakeholder heroAss : HeroAssociation;
}
```

### 13.3 Viewpoint & View

```
viewpoint 'system components' {
    frame 'Reduce the number of special parts';
    require constraint {
        doc /* ... */
    }
}

view def 'Part list' {
    satisfy 'system components';
    filter @ SysML::PartUsage;
}

view batmobileParts : 'Part list' {
    expose Dont_Panic_Batmobile::**;
    render Views::asElementTable;
}
```

*Source: Batmobile.sysml (lines 152-354)*

---

## 14. Variability Modeling

### 14.1 Variation definition

```
variation part def EngineChoices :> BatmobileEngine {
    variant part sEngine : StandardEngine;
    variant part xEngine : XtremeEngine;
}
```

### 14.2 Configuration

```
part def BatmobileConfigurations :> Batmobile {
    part :>> batmobileEngine : EngineChoices;
    part :>> wheels [4] : WheelChoices;

    assert constraint {
        batmobileEngine == batmobileEngine::xEngine and wheels == wheels.xWheel
    }
}
```

### 14.3 Individual (occurrence)

```
individual occurrence def Ind {
    snapshot snapshot1;
    timeslice timeslice1;
}

individual item def Batman :> Hero {
    attribute realName = "Jon Holt";
}
```

*Source: `convertBooleanTest/boolean.sysml`, Batmobile.sysml (lines 99-101, 270-293)*

---

## 15. Allocation

```
allocation def A;

allocation allocation1 : A {
    part source;
}
```

*Source: `convertAllocationTest/allocation.sysml`*

---

## 16. Visibility Modifiers

```
private import Definitions::Car;
public import ScalarValues::*;
protected part c : C;
private part def C { ... }
private in y : A;
```

Visibility applies to memberships (imports and features), not to the elements themselves.

*Source: `convertVisibilityTest/visibility.sysml`*

---

## 17. Boolean/Modifier Prefix Summary

These prefixes can appear before the element kind keyword:

| Prefix | Effect | Example |
|--------|--------|---------|
| `abstract` | isAbstract=true | `abstract item def B;` |
| `variation` | isVariation=true | `variation part def V :> A {}` |
| `variant` | variant membership | `variant part sEngine : StandardEngine;` |
| `readonly` | isReadOnly=true | `readonly attribute ro;` |
| `derived` | isDerived=true | `derived ref attribute y :> init1;` |
| `end` | isEnd=true | `end e1;` |
| `ref` | isReference=true (composite=false) | `ref part aReference : Referred;` |
| `in` / `out` / `inout` | feature direction | `in port powerIn : Real;` |
| `individual` | isIndividual=true | `individual item def Batman :> Hero;` |
| `parallel` | isParallel=true (states) | `state s parallel { ... }` |
| `nonunique` | isUnique=false | `part p1 nonunique : P1;` |
| `ordered` | isOrdered=true | (inferred, after multiplicity) |
| `default` | isDefault=true (values) | `attribute x default = 10;` |
| `not` | isNegated=true (satisfy/assert) | `not satisfy req2 by p;` |

*Source: `convertBooleanTest/boolean.sysml`, `isUniqueFeature/model.sysml`, `LabelConstants.java`*

---

## 18. Concrete Code Examples

### Example 1: Basic Package with Parts and Inheritance

```sysml
package VehicleModel {
    private import ScalarValues::*;

    part def Vehicle {
        attribute actualSpeed :> ISQ::speed;
        part engine;
    }

    part def Car :> Vehicle {
        part wheels : Wheel [4];
    }

    part def Wheel {
        attribute diameter : Real;
    }

    part myCar : Car;
}
```

### Example 2: Interface, Ports, and Flow

```sysml
package PowerSystem {
    item def Power {
        attribute value;
    }

    port def PowerIP {
        out item power : Power;
    }

    interface def PowerInterface {
        end supplierPort : PowerIP;
        end consumerPort : ~PowerIP;
        flow of Power from supplierPort.power to consumerPort.power;
    }

    interface bat2eng : PowerInterface connect battery.powerPort to engine.enginePort;
}
```

### Example 3: Enumeration and Action with Control Flow

```sysml
package BehaviorModel {
    enum def StatusKind {
        enum safe;
        enum alert;
    }

    action def 'Drive Batmobile' {
        first start;
        then action scanEnvironment {
            out status : StatusKind;
        }
        then decide;
            if scanEnvironment.status == StatusKind::safe then 'Switch to standard mode';
            if scanEnvironment.status == StatusKind::alert then 'Switch to alert mode';
        action 'Switch to standard mode';
        then endOfStatusCheck;
        action 'Switch to alert mode';
        then endOfStatusCheck;
        merge endOfStatusCheck;
        then done;
    }
}
```

### Example 4: Requirements with Constraints

```sysml
package Requirements {
    part def Vehicle;
    part def ProductManagement;

    requirement def VehicleMaxSpeed {
        doc
        /*
         * The actual speed of the vehicle shall
         * be less or equal than the maximum speed.
         */
        subject vehicle : Vehicle;
        stakeholder pm : ProductManagement;
        attribute maxSpeed : Real;
        require constraint { vehicle.actualSpeed <= maxSpeed }
    }

    requirement batmobileSpecification {
        requirement <REQ42> batmobileMaxSpeed : VehicleMaxSpeed {
            attribute :>> maxSpeed = 230;
        }
    }

    part batmobileDesign : Vehicle;
    satisfy batmobileSpecification by batmobileDesign;
}
```

### Example 5: State Machine with Transitions

```sysml
package StateMachineExample {
    state def EngineControl {
        entry action;
        exit action;

        state off;
        state idle;
        state running;

        transition t1 first off then idle;
        transition t2 first idle then running;
        transition t3 first running then idle;
        transition t4 first idle [speed == 0] then off;
    }
}
```

### Example 6: Variability and Configuration

```sysml
package Variability {
    part def StandardEngine;
    part def XtremeEngine;

    variation part def EngineChoices {
        variant part sEngine : StandardEngine;
        variant part xEngine : XtremeEngine;
    }

    part def Batmobile {
        part batmobileEngine : EngineChoices;

        assert constraint {
            batmobileEngine == batmobileEngine::xEngine
        }
    }
}
```

### Example 7: Attributes with Values and Assignments

```sysml
package AssignmentExample {
    part def Counter {
        attribute count : ScalarValues::Integer := 0;
        action incr {
            assign count := count + 1;
        }
    }

    action counter {
        attribute count default := 0;
        assign count := 1;
    }
}
```

### Example 8: Imports, Aliases, and Namespace Navigation

```sysml
package ImportExample {
    package Definitions {
        part def Vehicle;
        alias Car for Vehicle;
    }

    package Usages {
        public import Definitions::Car;
        part vehicle : Car;
    }

    // Full wildcard import
    private import ScalarValues::*;

    // Recursive import
    private import SomeLib::**;
}
```

### Example 9: Individual Occurrence with Snapshots

```sysml
package OccurrenceExample {
    part def Batman { }

    part bm1 : Batman {
        timeslice batmanDriving {
            snapshot :>> start {
                attribute :>> battery.capacity = 40000;
            }
            snapshot :>> done {
                attribute :>> battery.capacity = 42;
            }
        }
        then timeslice charging {
            attribute :>> driver = null;
        }
    }
}
```

### Example 10: Complete Real-World Model (Batmobile excerpt)

```sysml
package Batmobile {
    doc
    /*
     * Complete vehicle system model.
     */

    part def Vehicle {
        item driver;
        part engine;
        attribute actualSpeed :> ISQ::speed;
    }

    part def Wheel {
        item boundingBox : ShapeItems::Box [1] :> boundingShapes {
            attribute :>> length :> ISQ::length = 80 [SI::cm];
        }
    }

    part def Batmobile :> Vehicle {
        part seat [2];
        part wheels : Wheel [4];
        part frontLeftWheel :> wheels;
        part frontRightWheel :> wheels;

        part battery {
            port powerPort : PowerIP;
            attribute capacity;
        }

        interface bat2eng : PowerInterface
            connect battery.powerPort to engine.enginePort;
    }

    individual item def Batman :> Hero {
        attribute realName = "Jon Holt";
    }

    part bm1 : Batmobile {
        timeslice driving {
            item :>> driver : Batman;
        }
    }

    satisfy batmobileSpecification by bm1;
}
```

---

## 19. Validation Rules Summary (Key Constraints)

From `SysMLv2ValidationRules.java` (399 rules total). Key rules for code generation:

| Rule | Constraint |
|------|-----------|
| Top-level imports | Must be `private` |
| PartUsage | Must have at least one PartDefinition among its itemDefinitions |
| AttributeUsage | Always referential; all features must be non-composite |
| AttributeDefinition | All features must be non-composite |
| BindingConnector | Must be binary (exactly 2 relatedFeatures) |
| Definition variation | Must be abstract if isVariation |
| EnumerationDefinition | Must be a variation |
| TransitionUsage | Must have a Succession; source must be ActionUsage or StateUsage |
| StateDefinition parallel | No incoming/outgoing transitions on substates if parallel |
| RequirementDefinition | At most one SubjectMembership; subjectParameter is first input |
| CaseDefinition | At most one ObjectiveMembership; at most one SubjectMembership |
| PortDefinition | Must have exactly one ConjugatedPortDefinition (auto-created) |
| MultiplicityRange bounds | Must be non-negative integers; lower ≤ upper |

---

## 20. Key Syntax Pitfalls (from SysmlSyntaxValidator)

These are validated and **rejected** by the AI chat syntax checker:

1. **`value Real;`** — Don't generate this. Use `private import ScalarValues::*;` and type attributes as `Real`.
2. **`enum Name { a, b }`** — Use `enum def Name { enum a; enum b; }`.
3. **`part def Child : Base`** — Use `:>` not `:` for specialization.
4. **`port in power`** — Direction precedes kind: `in port power`.
5. **`requirement Name { "text" }`** — Use `requirement def Name { doc /* text */ }`.
6. **`actor User;`** / **`useCase Login;`** — Don't use shorthand; model actors as `part` within requirements/use cases.
7. **`moduleA satisfy moduleB;`** — Don't use relationship shorthand; use full syntax: `satisfy reqRef by partRef;`.
8. **`view MyDiagram : tree { include X; }`** — Not OMG syntax; use proper `view def` / `view` with `expose`.
9. **`transition t first A accept Signal then B;`** — Accept triggers need properly declared signal types.
10. **Unbalanced delimiters** `{`, `(`, `[` — Always close all brackets.

---

## 21. File Reference Summary

| Purpose | File Path |
|---------|-----------|
| Grammar (direct edit subset) | `services/syson-direct-edit-grammar/src/main/resources/DirectEdit.g4` |
| SysMLv2 → JSON AST (CLI bridge) | `application/syson-sysml-import/.../SysmlToAst.java` |
| JSON AST → EMF orchestrator | `application/syson-sysml-import/.../ASTTransformer.java` |
| AST tree parser | `application/syson-sysml-import/.../parser/AstTreeParser.java` |
| Type mapping ($type → EClass) | `application/syson-sysml-import/.../parser/translation/EClassifierTranslator.java` |
| Attribute mapping | `application/syson-sysml-import/.../parser/translation/EAttributeTranslator.java` |
| Reference resolution | `application/syson-sysml-import/.../parser/translation/EReferenceComputer.java` |
| Non-containment refs | `application/syson-sysml-import/.../parser/NonContainmentReferenceHandler.java` |
| AI syntax validator | `application/syson-application/.../chat/SysmlSyntaxValidator.java` |
| OMG validation rules | `application/syson-sysml-validation/.../SysMLv2ValidationRules.java` |
| EMF metamodel | `metamodel/syson-sysml-metamodel/src/main/resources/model/sysml.ecore` |
| Label constants | `metamodel/syson-sysml-metamodel/.../helper/LabelConstants.java` |
| Label service (textual) | `services/syson-services/.../LabelService.java` |
| Batmobile example | `application/syson-application-configuration/src/main/resources/templates/Batmobile.sysml` |
| Test fixtures (18 .sysml files) | `application/syson-sysml-import/src/test/resources/ASTTransformerTest/` |
| AST transformer tests | `application/syson-sysml-import/src/test/java/.../ASTTransformerTest.java` |
| Syntax validator tests | `application/syson-application/src/test/java/.../chat/SysmlSyntaxValidatorTest.java` |
