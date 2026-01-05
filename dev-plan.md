# spweebo'ard Development Plan

## Vision

A mobile keyboard application that bridges symbolic conceptual notation (SPW) and natural language through on-device LLM interpretation. Users compose thoughts using a 13-symbol semantic alphabet, which the system expands into coherent natural language against a loaded conceptual "ground."

---

## SPW Symbol Register

```
Symbol  Name          Semantic Role
──────────────────────────────────────────
~       potential     latent possibility, becoming
#       vibration     resonance, frequency, rhythm
.       ground        foundation, context, base truth
?       wonder        inquiry, exploration, openness
!       action        execution, assertion, force
*       value         worth, significance, weight
&       subject       agent, entity, the who
@       perspective   viewpoint, lens, frame
<>      concept       abstraction, idea, category
()      scene         situation, context, narrative
[]      mode          state, manner, operational frame
{}      direction     vector, intent, trajectory
^       integration   synthesis, unification, elevation
```

### Compositional Grammar

**Symbol order implies flow/transformation.** SPW expressions read as cognitive operations chaining left-to-right.

```spw
&@              subject → perspective     "from the subject's view"
@&              perspective → subject     "who holds this view"
?{Worcester#}   wonder{direction[vibration]}  "wondering about Worcester's resonance"
```

**Nesting encodes relational structure** — analogous to prepositions in natural language:

```spw
# "Henry of Worcester" as SPW
<ofness>couple[Henry, Worcester]

# Expanded with pattern
<ofness>couple{
  [Henry, Worcester]
  [<name> "," <city>]
}

# Wondering about Henry's perspective grounded in Worcester's vibration
?{Henry@.(Worcester#)}

# Henry's perspective as potential of the "of Worcester" relation
@Henry ~ "of[Worcester]"
```

**Cognitive primitives**: The 13 symbols approximate the low-level operations that structure thought — the "prepositions of cognition" that prime how concepts relate. A document as a list of SPW relationships encodes not just content but cognitive stance.

### Symbol Lore

Each symbol carries semantic weight that compounds in combination:

| Sequence | Reading | Cognitive Operation |
|----------|---------|---------------------|
| `&.`     | subject-ground | "who is foundational here" |
| `.&`     | ground-subject | "foundation gives rise to agent" |
| `?!`     | wonder-action | "explore then execute" |
| `!?`     | action-wonder | "act then question" |
| `*^`     | value-integration | "worth synthesized" |
| `@[]`    | perspective-mode | "view in a certain manner" |
| `{}~`    | direction-potential | "intent becoming" |

---

## Core Interaction Model

```
┌─────────────────────────────────────────────────────┐
│  SPW Symbol Row                                     │
│  ~ # . ? ! * & @ < > ( ) [ ] { } ^                  │
├─────────────────────────────────────────────────────┤
│  Buffer Display: &@[work]{->} ?*<productivity>     │
│  Ground: "software development"                     │
├─────────────────────────────────────────────────────┤
│  Preview: "From my perspective as a developer,      │
│           what matters most about productivity?"    │
├─────────────────────────────────────────────────────┤
│  q w e r t y u i o p                               │
│   a s d f g h j k l                                │
│    z x c v b n m                                   │
└─────────────────────────────────────────────────────┘
```

**Flow:**
1. User loads a SPW block as "ground" (persistent context)
2. User taps SPW symbols to compose a conceptual expression
3. On-device LLM interprets symbols against ground
4. Natural language preview renders in real-time
5. User commits output to active text field

---

## Architecture

### Layer Diagram

```
┌──────────────────────────────────────────────────┐
│           Platform Shell (Swift / Kotlin)        │
│  ┌─────────────────┐    ┌─────────────────────┐  │
│  │ iOS Keyboard    │    │ Android IME         │  │
│  │ Extension       │    │ Service             │  │
│  │ (SwiftUI)       │    │ (Jetpack Compose)   │  │
│  └────────┬────────┘    └──────────┬──────────┘  │
└───────────┼─────────────────────────┼────────────┘
            │         UniFFI          │
            └────────────┬────────────┘
┌────────────────────────┴─────────────────────────┐
│                   Rust Core                       │
│  ┌─────────────┐ ┌─────────────┐ ┌────────────┐  │
│  │ SPW Parser  │ │ Expression  │ │ Ground     │  │
│  │ & Lexer     │ │ Builder     │ │ Manager    │  │
│  └─────────────┘ └─────────────┘ └────────────┘  │
│  ┌─────────────────────────────────────────────┐ │
│  │           LLM Inference Engine              │ │
│  │  (candle / llama.cpp bindings / MLX)        │ │
│  └─────────────────────────────────────────────┘ │
│  ┌─────────────────────────────────────────────┐ │
│  │           Prompt Compiler                   │ │
│  │  SPW Expression + Ground → LLM Prompt       │ │
│  └─────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────┘
```

### Rust Core Modules

```
spweeboard-core/
├── src/
│   ├── lib.rs              # Public API, UniFFI exports
│   ├── spw/
│   │   ├── mod.rs
│   │   ├── lexer.rs        # Tokenize SPW input
│   │   ├── parser.rs       # Build AST from tokens
│   │   ├── ast.rs          # Expression tree types
│   │   └── semantics.rs    # Symbol meaning mappings
│   ├── ground/
│   │   ├── mod.rs
│   │   ├── loader.rs       # Load/parse ground contexts
│   │   └── store.rs        # Persist grounds locally
│   ├── compiler/
│   │   ├── mod.rs
│   │   └── prompt.rs       # SPW+Ground → LLM prompt
│   ├── inference/
│   │   ├── mod.rs
│   │   ├── engine.rs       # Abstract inference trait
│   │   ├── candle.rs       # Candle backend
│   │   └── mlx.rs          # MLX backend (Apple Silicon)
│   └── buffer/
│       ├── mod.rs
│       └── history.rs      # Undo/redo, expression history
└── Cargo.toml
```

---

## LLM Integration Strategy

### Model Requirements
- **Size**: 0.5-1.5B parameters (keyboard-viable, fast inference)
- **Quantization**: Q4_K_M for memory/quality balance
- **Primary Candidate**: Qwen2.5-0.5B-Instruct or SmolLM-1.7B
- **Fallback**: SmolLM-360M for constrained devices

### Runtime: llama.cpp

Use **llama.cpp** via Rust bindings for unified codebase with platform-native acceleration:

| Platform | Backend | Acceleration |
|----------|---------|--------------|
| iOS | Metal | Apple GPU (A11+) |
| Android | Vulkan | GPU where available |
| Android | OpenCL | Fallback GPU |
| Both | CPU | NEON/ARM optimized fallback |

**Rust integration**: `llama-cpp-2` crate wraps llama.cpp, compiles Metal/Vulkan backends per target.

```toml
[dependencies]
llama-cpp-2 = { version = "0.1", features = ["metal"] }  # iOS
llama-cpp-2 = { version = "0.1", features = ["vulkan"] } # Android
```

### Prompt Architecture

The LLM receives a structured prompt compiled from SPW. The model acts as creative interpreter, not literal translator:

```
<system>
You interpret SPW symbolic expressions as natural language. SPW encodes
cognitive operations — treat symbols as creative constraints that shape
meaning, not words to substitute.

Symbol lore (order matters, left-to-right flow):
~ potential (becoming)    # vibration (resonance)   . ground (foundation)
? wonder (inquiry)        ! action (assertion)      * value (significance)
& subject (agent)         @ perspective (viewpoint) <> concept (abstraction)
() scene (situation)      [] mode (manner)          {} direction (intent)
^ integration (synthesis)

Nesting: brackets contain, order transforms. &@ = "from subject's view"
</system>

<ground>
{loaded ground — may itself be SPW}
</ground>

<expression>
{user's SPW input}
</expression>

<task>
Produce natural language that embodies this expression against the ground.
Creative, concise, match the cognitive tone (? = wondering, ! = assertive).
</task>
```

### Inference Modes
1. **Streaming**: Real-time preview as user composes (debounced 150ms)
2. **Commit**: Full generation on send gesture
3. **Cached**: Memoize ground→prompt prefix, common expression patterns

### In-Context Learning Strategy

A 0.5B model won't "understand" SPW natively — it learns from examples in the prompt. The example bank must be:
- **Compact**: Keyboard context budget is tight
- **High-signal**: Each example teaches multiple concepts
- **Compositional**: Examples build on each other

#### Example Categories

**1. Order Semantics** — Show that sequence matters:
```
&@     → "from my perspective as the subject"
@&     → "the subject I'm viewing"
?!     → "wondering, then acting"
!?     → "acting, then questioning what happened"
```

**2. Bracket Nesting** — Show containment/scoping:
```
&[work]        → "the subject in work mode"
[work]&        → "work mode gives rise to the subject"
?{&.}          → "wondering about who is foundational, directed inquiry"
{?}.&          → "directed wondering grounds the subject"
```

**3. Ground Composition** — Show how grounds layer by operator metaphysics:
```
ground_a: @[craft]           "perspective in craft mode"
ground_b: .{utility}         "grounded toward utility"
composed: @[craft].{utility} "craftsperson's view grounded in usefulness"

ground_a: &~                 "subject becoming"
ground_b: *^                 "value integrating"
composed: &~*^               "subject becoming through value integration"
```

**4. Expression Against Ground** — Show ground→expression interaction:
```
ground: .{software}
expr:   ?&*
output: "In software: wondering who finds this valuable"

ground: @[poetry]~
expr:   !<rhythm>#
output: "From poetry's becoming perspective: asserting the concept of rhythm's resonance"
```

**5. Recursive Grounds** — Ground as SPW expression:
```
ground: ?{&@.}              "wondering about subject-perspective-ground flow"
expr:   !*
output: "Within that wondering: asserting value"
```

#### Prompt Budget

Target: ~800 tokens for system + examples, leaving headroom for ground + expression + generation.

```
System instruction:     ~150 tokens
Symbol reference:       ~100 tokens
Order examples (4):     ~120 tokens
Nesting examples (4):   ~140 tokens
Ground composition (3): ~150 tokens
Expression examples (3):~140 tokens
─────────────────────────────────────
Total:                  ~800 tokens
```

#### Example Curation Process

1. Start with manually crafted seed examples
2. Generate candidate interpretations, human-filter for quality
3. Test against held-out expressions for consistency
4. Iterate: add examples that fix failure modes, remove redundant ones
5. A/B test example sets for output quality on target model

---

## Platform Implementation

### iOS (Swift + SwiftUI)

```
spweeboard-ios/
├── SpweeboardKeyboard/          # Keyboard Extension target
│   ├── KeyboardViewController.swift
│   ├── Views/
│   │   ├── SPWRow.swift         # 13-symbol top row
│   │   ├── QWERTYGrid.swift     # Standard layout
│   │   ├── BufferDisplay.swift  # Current expression
│   │   └── PreviewPane.swift    # LLM output preview
│   ├── RustBridge/
│   │   └── SpweeboardCore.swift # UniFFI generated
│   └── Info.plist
├── SpweeboardApp/               # Container app target
│   ├── ContentView.swift        # Settings, ground management
│   ├── GroundLibrary.swift      # Browse/create grounds
│   └── OnboardingFlow.swift
└── Package.swift
```

**Key iOS Considerations:**
- Keyboard extensions have 50MB memory limit
- Use App Groups for shared data between app and extension
- MLX available on Apple Silicon (A14+)

### Android (Kotlin + Jetpack Compose)

```
spweeboard-android/
├── app/
│   └── src/main/
│       ├── kotlin/.../
│       │   ├── MainActivity.kt
│       │   ├── GroundManagerActivity.kt
│       │   └── SettingsScreen.kt
│       └── res/
├── keyboard/
│   └── src/main/
│       ├── kotlin/.../
│       │   ├── SpweeboardService.kt    # InputMethodService
│       │   ├── ui/
│       │   │   ├── KeyboardLayout.kt   # Compose UI
│       │   │   ├── SPWSymbolRow.kt
│       │   │   └── PreviewPane.kt
│       │   └── rust/
│       │       └── NativeBridge.kt     # JNI/UniFFI
│       └── jniLibs/                    # Compiled .so files
└── build.gradle.kts
```

**Key Android Considerations:**
- InputMethodService lifecycle management
- NNAPI for hardware acceleration where available
- Target API 26+ for modern Compose support

---

## Development Phases

### Phase 1: Foundation
- [ ] Rust core scaffold with UniFFI setup
- [ ] SPW lexer and parser
- [ ] Basic expression AST
- [ ] iOS keyboard extension shell (static UI)
- [ ] Android IME shell (static UI)

### Phase 2: Symbol System
- [ ] Full SPW semantic mapping
- [ ] Ground loading and persistence
- [ ] Expression → prompt compiler
- [ ] Buffer management with history

### Phase 3: LLM Integration
- [ ] Candle inference engine integration
- [ ] Model loading and quantization
- [ ] Streaming token generation
- [ ] Prompt template refinement

### Phase 4: Platform Polish
- [ ] iOS: App Groups, MLX backend option
- [ ] Android: NNAPI acceleration
- [ ] Gesture support (swipe to clear, etc.)
- [ ] Haptic feedback on symbol selection

### Phase 5: Experience
- [ ] Ground library (presets + user-created)
- [ ] Expression history and favorites
- [ ] Theming and accessibility
- [ ] Onboarding flow

---

## Technical Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| FFI Layer | UniFFI | Type-safe, generates Swift/Kotlin bindings |
| LLM Runtime | llama.cpp | Metal (iOS) + Vulkan (Android) acceleration |
| Rust Bindings | llama-cpp-2 | Maintained, feature-flagged backends |
| iOS UI | SwiftUI | Modern, declarative, keyboard extension support |
| Android UI | Jetpack Compose | Modern, declarative, Kotlin-native |
| Model Format | GGUF | Industry standard, quantization support |
| Model | Qwen2.5-0.5B | Small, fast, good instruction following |
| Storage | SQLite via rusqlite | Reliable, cross-platform, embedded |

---

## Design Decisions (Resolved)

1. **Symbol Order Matters**: `&@` (subject → perspective) is semantically distinct from `@&` (perspective → subject). Order implies flow/transformation.

2. **Recursive Grounds**: Grounds can be SPW expressions themselves, enabling compositional context layering.

3. **Interpretation Mode**: Creative constraint + contextual inference. The LLM treats symbols as creative boundaries and interprets against ground context—not literal symbol-to-word expansion.

## Design Decisions (Continued)

4. **Ground Composition**: Multiple grounds compose according to operator metaphysics — same rules that govern symbol composition. `@[craft]` + `.{utility}` = `@[craft].{utility}`, interpreted compositionally.

---

## Resources

- [UniFFI Book](https://mozilla.github.io/uniffi-rs/)
- [llama.cpp](https://github.com/ggerganov/llama.cpp)
- [llama-cpp-2 Rust crate](https://crates.io/crates/llama-cpp-2)
- [Qwen2.5-0.5B-Instruct GGUF](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF)
- [iOS Keyboard Extensions](https://developer.apple.com/documentation/uikit/keyboards_and_input/creating_a_custom_keyboard)
- [Android InputMethodService](https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method)
