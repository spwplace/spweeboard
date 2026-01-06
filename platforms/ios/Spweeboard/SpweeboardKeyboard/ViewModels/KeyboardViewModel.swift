import Foundation
import Combine

// MARK: - Rust FFI Configuration

/// Set to true when Rust library is linked to the project.
/// The build script (build-rust.sh) generates the bindings.
/// Until integration is complete, we use Swift fallback.
private let useRustParser = false

/// Parse state for SPW expression.
enum ParseState {
    case empty
    case valid
    case invalid
}

/// Available grounds for interpretation context.
struct GroundOption: Identifiable, Equatable {
    let id: String
    let name: String
    let spw: String
}

let defaultGrounds: [GroundOption] = [
    GroundOption(id: "none", name: "None", spw: ""),
    GroundOption(id: "software", name: "Software", spw: ".{software}"),
    GroundOption(id: "craft", name: "Craft", spw: "@[craft].{utility}"),
    GroundOption(id: "poetry", name: "Poetry", spw: "@[poetry]~"),
    GroundOption(id: "inquiry", name: "Inquiry", spw: "?{&@.}"),
]

/// View model for the keyboard, managing buffer state and LLM interaction.
@MainActor
final class KeyboardViewModel: ObservableObject {
    /// Current expression buffer (raw SPW text).
    @Published private(set) var buffer: String = ""

    /// Parse state of the current expression.
    @Published private(set) var parseState: ParseState = .empty

    /// LLM-generated preview of the interpreted expression.
    @Published private(set) var interpretation: String?

    /// Whether inference is currently running.
    @Published private(set) var isGenerating: Bool = false

    /// History of committed expressions.
    @Published private(set) var history: [String] = []

    /// Currently selected ground context.
    @Published var selectedGround: GroundOption = defaultGrounds[0]

    private var previewTask: Task<Void, Never>?
    private let debounceInterval: TimeInterval = 0.15

    // MARK: - Buffer Operations

    /// Pushes a SPW symbol to the buffer.
    func pushSymbol(_ symbol: String) {
        buffer.append(symbol)
        updateParseState()
    }

    /// Pushes a character (for QWERTY input).
    func pushCharacter(_ char: String) {
        buffer.append(char)
        updateParseState()
    }

    /// Removes the last character from the buffer.
    func pop() {
        guard !buffer.isEmpty else { return }
        buffer.removeLast()
        updateParseState()
    }

    /// Clears the entire buffer.
    func clear() {
        buffer = ""
        parseState = .empty
        interpretation = nil
        previewTask?.cancel()
    }

    /// Commits the current buffer to history.
    func commit() {
        guard !buffer.isEmpty else { return }
        history.append(buffer)
        buffer = ""
        parseState = .empty
        interpretation = nil
        previewTask?.cancel()

        // Trim history to last 100 entries
        if history.count > 100 {
            history.removeFirst(history.count - 100)
        }
    }

    /// Recalls an expression from history.
    func recall(at index: Int) {
        guard history.indices.contains(index) else { return }
        buffer = history[index]
        updateParseState()
    }

    /// Selects a ground context and re-interprets.
    func selectGround(_ ground: GroundOption) {
        selectedGround = ground
        if parseState == .valid {
            interpretation = interpretSwift(buffer, ground: ground)
        }
    }

    // MARK: - Parse State

    /// Updates parse state based on current buffer.
    private func updateParseState() {
        guard !buffer.isEmpty else {
            parseState = .empty
            interpretation = nil
            return
        }

        // Validate SPW expression
        // TODO: Enable Rust FFI when library is integrated
        let isValid = validateSpwSwift(buffer)

        parseState = isValid ? .valid : .invalid

        if isValid {
            interpretation = interpretSwift(buffer, ground: selectedGround)
        } else {
            interpretation = nil
        }
    }

    /// Swift fallback for SPW validation - checks bracket matching.
    private func validateSpwSwift(_ input: String) -> Bool {
        var bracketStack: [Character] = []

        let openBrackets: [Character: Character] = [
            "<": ">",
            "(": ")",
            "[": "]",
            "{": "}"
        ]
        let closeBrackets: Set<Character> = [">", ")", "]", "}"]

        for char in input {
            if openBrackets.keys.contains(char) {
                bracketStack.append(char)
            } else if closeBrackets.contains(char) {
                guard let last = bracketStack.last,
                      openBrackets[last] == char else {
                    return false
                }
                bracketStack.removeLast()
            }
        }

        // All brackets must be closed for valid expression
        return bracketStack.isEmpty
    }

    /// Interpret SPW expression against ground context.
    private func interpretSwift(_ input: String, ground: GroundOption) -> String {
        var parts: [String] = []

        // Add ground context prefix if selected
        if ground.id != "none" && !ground.spw.isEmpty {
            parts.append("[\(ground.name)]")
        }

        // Simple token-by-token interpretation
        parts.append(contentsOf: tokenizeSpw(input))

        return parts.joined(separator: " ")
    }

    /// Tokenize SPW expression into readable parts (Swift fallback).
    private func tokenizeSpw(_ input: String) -> [String] {
        var result: [String] = []
        var inBracket = false
        var bracketContent = ""
        var bracketType: Character = " "

        for char in input {
            switch char {
            case "<", "(", "[", "{":
                inBracket = true
                bracketType = char
                bracketContent = ""
            case ">", ")", "]", "}":
                if inBracket {
                    let wrapper: String
                    switch bracketType {
                    case "<": wrapper = "⟨\(bracketContent)⟩"
                    case "(": wrapper = "(\(bracketContent))"
                    case "[": wrapper = "[\(bracketContent)]"
                    case "{": wrapper = "→\(bracketContent)"
                    default: wrapper = bracketContent
                    }
                    result.append(wrapper)
                    inBracket = false
                }
            default:
                if inBracket {
                    bracketContent.append(char)
                } else {
                    let word: String
                    switch char {
                    case "~": word = "becoming"
                    case "#": word = "vibrating"
                    case ".": word = "grounded"
                    case "?": word = "wondering"
                    case "!": word = "asserting"
                    case "*": word = "valued"
                    case "&": word = "self"
                    case "@": word = "seeing"
                    case "^": word = "integrating"
                    case " ": word = ""
                    default: word = String(char)
                    }
                    if !word.isEmpty {
                        result.append(word)
                    }
                }
            }
        }

        return result
    }
}

/// Ground model (duplicated for keyboard extension, shared via App Groups).
struct Ground: Identifiable, Codable, Equatable {
    let id: String
    var name: String
    var content: String
    var isSpw: Bool
}
