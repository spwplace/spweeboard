import SwiftUI
import UIKit

/// Main SwiftUI keyboard view.
struct KeyboardView: View {
    let textDocumentProxy: UITextDocumentProxy
    let advanceToNextInputMode: () -> Void

    @StateObject private var viewModel = KeyboardViewModel()
    @State private var showQwerty = false

    var body: some View {
        VStack(spacing: 0) {
            // Buffer display and preview
            BufferDisplayView(viewModel: viewModel)

            // SPW symbol row
            SPWSymbolRowView(viewModel: viewModel)

            // QWERTY or action row
            if showQwerty {
                QWERTYView(
                    onKeyTap: { key in
                        viewModel.pushCharacter(key)
                    },
                    onBackspace: {
                        viewModel.pop()
                    },
                    onSpace: {
                        viewModel.pushCharacter(" ")
                    },
                    onReturn: {
                        commitAndInsert()
                    }
                )
            } else {
                ActionRowView(
                    viewModel: viewModel,
                    onCommit: commitAndInsert,
                    onToggleQwerty: { showQwerty.toggle() },
                    onNextKeyboard: advanceToNextInputMode
                )
            }
        }
        .background(Color(.systemBackground).opacity(0.95))
        .environment(\.colorScheme, UITraitCollection.current.userInterfaceStyle == .dark ? .dark : .light)
    }

    private func commitAndInsert() {
        // TODO: When LLM integration is ready, generate interpreted text
        // For now, insert the raw SPW expression
        let text = viewModel.buffer.isEmpty ? "" : viewModel.buffer
        textDocumentProxy.insertText(text)
        viewModel.commit()
    }
}

/// Displays the current buffer and LLM preview.
struct BufferDisplayView: View {
    @ObservedObject var viewModel: KeyboardViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            // Current expression buffer
            HStack {
                Text(viewModel.buffer.isEmpty ? "~" : viewModel.buffer)
                    .font(.system(.body, design: .monospaced))
                    .foregroundStyle(viewModel.buffer.isEmpty ? .tertiary : .primary)

                Spacer()

                if !viewModel.buffer.isEmpty {
                    Button {
                        viewModel.clear()
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(.ultraThinMaterial)
            .clipShape(RoundedRectangle(cornerRadius: 8))

            // LLM preview (when available)
            if let preview = viewModel.preview {
                Text(preview)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                    .padding(.horizontal, 4)
            }
        }
        .padding(.horizontal, 8)
        .padding(.top, 8)
    }
}

/// Row of SPW symbols — the cognitive primitives.
struct SPWSymbolRowView: View {
    @ObservedObject var viewModel: KeyboardViewModel

    private let symbols: [(String, String)] = [
        ("~", "potential"),
        ("#", "vibration"),
        (".", "ground"),
        ("?", "wonder"),
        ("!", "action"),
        ("*", "value"),
        ("&", "subject"),
        ("@", "perspective"),
        ("^", "integration"),
    ]

    private let brackets: [(String, String, String)] = [
        ("<", ">", "concept"),
        ("(", ")", "scene"),
        ("[", "]", "mode"),
        ("{", "}", "direction"),
    ]

    var body: some View {
        VStack(spacing: 4) {
            // Main symbols
            HStack(spacing: 4) {
                ForEach(symbols, id: \.0) { symbol, name in
                    SymbolKeyView(symbol: symbol, name: name) {
                        viewModel.pushSymbol(symbol)
                        hapticFeedback()
                    }
                }
            }

            // Bracket pairs
            HStack(spacing: 4) {
                ForEach(brackets, id: \.0) { open, close, name in
                    BracketKeyView(open: open, close: close, name: name) { char in
                        viewModel.pushSymbol(char)
                        hapticFeedback()
                    }
                }
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
    }

    private func hapticFeedback() {
        let generator = UIImpactFeedbackGenerator(style: .light)
        generator.impactOccurred()
    }
}

/// A single SPW symbol key.
struct SymbolKeyView: View {
    let symbol: String
    let name: String
    let action: () -> Void

    @State private var isPressed = false

    var body: some View {
        Button(action: action) {
            Text(symbol)
                .font(.system(size: 22, weight: .medium, design: .monospaced))
                .frame(maxWidth: .infinity)
                .frame(height: 44)
                .background(isPressed ? Color.accentColor.opacity(0.3) : Color(.secondarySystemBackground))
                .clipShape(RoundedRectangle(cornerRadius: 6))
        }
        .buttonStyle(.plain)
        .simultaneousGesture(
            DragGesture(minimumDistance: 0)
                .onChanged { _ in isPressed = true }
                .onEnded { _ in isPressed = false }
        )
        .accessibilityLabel(name)
    }
}

/// A bracket pair key (tap for open, long press for close).
struct BracketKeyView: View {
    let open: String
    let close: String
    let name: String
    let action: (String) -> Void

    @State private var isPressed = false

    var body: some View {
        Button {
            action(open)
        } label: {
            HStack(spacing: 2) {
                Text(open)
                Text(close)
                    .foregroundStyle(.secondary)
            }
            .font(.system(size: 18, weight: .medium, design: .monospaced))
            .frame(maxWidth: .infinity)
            .frame(height: 44)
            .background(isPressed ? Color.accentColor.opacity(0.3) : Color(.secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 6))
        }
        .buttonStyle(.plain)
        .simultaneousGesture(
            LongPressGesture(minimumDuration: 0.3)
                .onEnded { _ in action(close) }
        )
        .simultaneousGesture(
            DragGesture(minimumDistance: 0)
                .onChanged { _ in isPressed = true }
                .onEnded { _ in isPressed = false }
        )
        .accessibilityLabel(name)
    }
}

/// Action row with commit, QWERTY toggle, and keyboard switch.
struct ActionRowView: View {
    @ObservedObject var viewModel: KeyboardViewModel
    let onCommit: () -> Void
    let onToggleQwerty: () -> Void
    let onNextKeyboard: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            // Next keyboard
            Button(action: onNextKeyboard) {
                Image(systemName: "globe")
                    .font(.system(size: 20))
                    .frame(width: 44, height: 44)
                    .background(Color(.secondarySystemBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 6))
            }
            .buttonStyle(.plain)

            // Toggle QWERTY
            Button(action: onToggleQwerty) {
                Text("ABC")
                    .font(.system(size: 14, weight: .medium))
                    .frame(width: 60, height: 44)
                    .background(Color(.secondarySystemBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 6))
            }
            .buttonStyle(.plain)

            Spacer()

            // Commit / Send
            Button(action: onCommit) {
                Image(systemName: "arrow.up.circle.fill")
                    .font(.system(size: 32))
                    .foregroundStyle(.tint)
            }
            .buttonStyle(.plain)
            .disabled(viewModel.buffer.isEmpty)
            .opacity(viewModel.buffer.isEmpty ? 0.5 : 1)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 8)
    }
}

/// Simple QWERTY keyboard for typing identifiers and literals.
struct QWERTYView: View {
    let onKeyTap: (String) -> Void
    let onBackspace: () -> Void
    let onSpace: () -> Void
    let onReturn: () -> Void

    private let rows = [
        ["q", "w", "e", "r", "t", "y", "u", "i", "o", "p"],
        ["a", "s", "d", "f", "g", "h", "j", "k", "l"],
        ["z", "x", "c", "v", "b", "n", "m"],
    ]

    var body: some View {
        VStack(spacing: 6) {
            ForEach(rows, id: \.self) { row in
                HStack(spacing: 4) {
                    ForEach(row, id: \.self) { key in
                        KeyView(label: key) {
                            onKeyTap(key)
                        }
                    }
                }
            }

            // Bottom row
            HStack(spacing: 4) {
                Button(action: onBackspace) {
                    Image(systemName: "delete.left")
                        .font(.system(size: 18))
                        .frame(width: 44, height: 42)
                        .background(Color(.tertiarySystemBackground))
                        .clipShape(RoundedRectangle(cornerRadius: 5))
                }
                .buttonStyle(.plain)

                Button(action: onSpace) {
                    Text("")
                        .frame(maxWidth: .infinity)
                        .frame(height: 42)
                        .background(Color(.secondarySystemBackground))
                        .clipShape(RoundedRectangle(cornerRadius: 5))
                }
                .buttonStyle(.plain)

                Button(action: onReturn) {
                    Image(systemName: "return")
                        .font(.system(size: 18))
                        .frame(width: 60, height: 42)
                        .background(Color(.tertiarySystemBackground))
                        .clipShape(RoundedRectangle(cornerRadius: 5))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 4)
        .padding(.vertical, 6)
    }
}

struct KeyView: View {
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: {
            action()
            let generator = UIImpactFeedbackGenerator(style: .light)
            generator.impactOccurred()
        }) {
            Text(label)
                .font(.system(size: 22))
                .frame(maxWidth: .infinity)
                .frame(height: 42)
                .background(Color(.secondarySystemBackground))
                .clipShape(RoundedRectangle(cornerRadius: 5))
        }
        .buttonStyle(.plain)
    }
}
