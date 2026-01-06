import Foundation
import Combine

/// View model for the keyboard, managing buffer state and LLM interaction.
@MainActor
final class KeyboardViewModel: ObservableObject {
    /// Current expression buffer (raw SPW text).
    @Published private(set) var buffer: String = ""

    /// LLM-generated preview of the interpreted expression.
    @Published private(set) var preview: String?

    /// Whether inference is currently running.
    @Published private(set) var isGenerating: Bool = false

    /// History of committed expressions.
    @Published private(set) var history: [String] = []

    /// Currently loaded ground context.
    @Published var activeGround: Ground?

    private var previewTask: Task<Void, Never>?
    private let debounceInterval: TimeInterval = 0.15

    // MARK: - Buffer Operations

    /// Pushes a SPW symbol to the buffer.
    func pushSymbol(_ symbol: String) {
        buffer.append(symbol)
        schedulePreview()
    }

    /// Pushes a character (for QWERTY input).
    func pushCharacter(_ char: String) {
        buffer.append(char)
        schedulePreview()
    }

    /// Removes the last character from the buffer.
    func pop() {
        guard !buffer.isEmpty else { return }
        buffer.removeLast()
        schedulePreview()
    }

    /// Clears the entire buffer.
    func clear() {
        buffer = ""
        preview = nil
        previewTask?.cancel()
    }

    /// Commits the current buffer to history.
    func commit() {
        guard !buffer.isEmpty else { return }
        history.append(buffer)
        buffer = ""
        preview = nil
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
        schedulePreview()
    }

    // MARK: - LLM Preview

    /// Schedules a debounced preview generation.
    private func schedulePreview() {
        previewTask?.cancel()

        guard !buffer.isEmpty else {
            preview = nil
            return
        }

        previewTask = Task {
            try? await Task.sleep(for: .milliseconds(Int(debounceInterval * 1000)))

            guard !Task.isCancelled else { return }
            await generatePreview()
        }
    }

    /// Generates an LLM preview for the current buffer.
    private func generatePreview() async {
        isGenerating = true
        defer { isGenerating = false }

        // TODO: Integrate with Rust core via UniFFI
        // For now, show a placeholder
        preview = interpretPlaceholder(buffer)
    }

    /// Placeholder interpretation until LLM is integrated.
    private func interpretPlaceholder(_ input: String) -> String? {
        guard !input.isEmpty else { return nil }

        // Simple symbol-to-meaning mapping for demo
        var parts: [String] = []

        for char in input {
            switch char {
            case "~": parts.append("becoming")
            case "#": parts.append("resonance")
            case ".": parts.append("grounded in")
            case "?": parts.append("wondering")
            case "!": parts.append("asserting")
            case "*": parts.append("valuing")
            case "&": parts.append("the subject")
            case "@": parts.append("from perspective")
            case "^": parts.append("integrating")
            case "<": parts.append("concept(")
            case ">": parts.append(")")
            case "(": parts.append("scene(")
            case ")": parts.append(")")
            case "[": parts.append("mode(")
            case "]": parts.append(")")
            case "{": parts.append("toward(")
            case "}": parts.append(")")
            default: parts.append(String(char))
            }
        }

        return parts.joined(separator: " ")
    }
}

/// Ground model (duplicated for keyboard extension, shared via App Groups).
struct Ground: Identifiable, Codable, Equatable {
    let id: String
    var name: String
    var content: String
    var isSpw: Bool
}
