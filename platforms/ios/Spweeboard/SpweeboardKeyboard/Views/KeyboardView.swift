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
            // Buffer display with parse status
            BufferDisplayView(viewModel: viewModel)

            // Ground selector row
            GroundSelectorRow(viewModel: viewModel)
                .padding(.top, 6)

            // SPW symbol rows
            SPWSymbolRowView(viewModel: viewModel)

            // QWERTY keyboard (always visible like Android)
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
                onSend: {
                    commitAndInsert()
                },
                canSend: viewModel.parseState == .valid && !viewModel.buffer.isEmpty,
                onNextKeyboard: advanceToNextInputMode
            )
        }
        .background(Color(.systemBackground).opacity(0.95))
        .environment(\.colorScheme, UITraitCollection.current.userInterfaceStyle == .dark ? .dark : .light)
    }

    private func commitAndInsert() {
        guard viewModel.parseState == .valid && !viewModel.buffer.isEmpty else { return }
        // Send the interpretation if available, otherwise raw buffer
        let text = viewModel.interpretation ?? viewModel.buffer
        textDocumentProxy.insertText(text)
        viewModel.commit()
    }
}

/// Displays the current buffer with parse state visual feedback.
struct BufferDisplayView: View {
    @ObservedObject var viewModel: KeyboardViewModel

    private var borderColor: Color {
        switch viewModel.parseState {
        case .empty: return Color(.systemGray4)
        case .valid: return .accentColor
        case .invalid: return .red
        }
    }

    private var backgroundColor: Color {
        switch viewModel.parseState {
        case .empty: return Color(.secondarySystemBackground)
        case .valid: return Color.accentColor.opacity(0.1)
        case .invalid: return Color.red.opacity(0.1)
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            // Current expression buffer
            HStack {
                Text(viewModel.buffer.isEmpty ? "type SPW expression..." : viewModel.buffer)
                    .font(.system(.body, design: .monospaced))
                    .foregroundColor(viewModel.buffer.isEmpty ? Color(.tertiaryLabel) : textColor)
                    .lineLimit(1)

                Spacer()

                if !viewModel.buffer.isEmpty {
                    // Parse status indicator
                    Circle()
                        .fill(parseIndicatorColor)
                        .frame(width: 8, height: 8)

                    Button {
                        viewModel.clear()
                    } label: {
                        Text("×")
                            .font(.system(size: 20))
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(backgroundColor)
            .overlay(
                RoundedRectangle(cornerRadius: 10)
                    .stroke(borderColor, lineWidth: 2)
            )
            .clipShape(RoundedRectangle(cornerRadius: 10))
            .animation(.easeInOut(duration: 0.2), value: viewModel.parseState)

            // Interpretation preview (when valid)
            if let interpretation = viewModel.interpretation, viewModel.parseState == .valid {
                Text("→ \(interpretation)")
                    .font(.caption)
                    .foregroundColor(.accentColor)
                    .lineLimit(2)
                    .padding(.horizontal, 4)
            }
        }
        .padding(.horizontal, 8)
        .padding(.top, 8)
    }

    private var textColor: Color {
        switch viewModel.parseState {
        case .empty, .valid: return .primary
        case .invalid: return .red
        }
    }

    private var parseIndicatorColor: Color {
        switch viewModel.parseState {
        case .empty: return .clear
        case .valid: return .green
        case .invalid: return .red
        }
    }
}

/// Ground selector row for interpretation context.
struct GroundSelectorRow: View {
    @ObservedObject var viewModel: KeyboardViewModel

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(defaultGrounds) { ground in
                    let isSelected = ground.id == viewModel.selectedGround.id
                    Button {
                        viewModel.selectGround(ground)
                    } label: {
                        Text(ground.name)
                            .font(.system(size: 12, weight: isSelected ? .semibold : .regular))
                            .foregroundStyle(isSelected ? Color(.systemBackground) : .secondary)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(isSelected ? Color.accentColor : Color(.tertiarySystemBackground))
                            .clipShape(Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 8)
        }
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

/// QWERTY keyboard with integrated send and globe buttons.
struct QWERTYView: View {
    let onKeyTap: (String) -> Void
    let onBackspace: () -> Void
    let onSpace: () -> Void
    let onSend: () -> Void
    let canSend: Bool
    let onNextKeyboard: () -> Void

    private let rows = [
        ["q", "w", "e", "r", "t", "y", "u", "i", "o", "p"],
        ["a", "s", "d", "f", "g", "h", "j", "k", "l"],
        ["z", "x", "c", "v", "b", "n", "m"],
    ]

    var body: some View {
        VStack(spacing: 4) {
            ForEach(rows.indices, id: \.self) { index in
                HStack(spacing: 3) {
                    // Add padding for centered rows
                    if index == 1 { Spacer().frame(width: 16) }
                    if index == 2 { Spacer().frame(width: 32) }

                    ForEach(rows[index], id: \.self) { key in
                        KeyView(label: key) {
                            onKeyTap(key)
                        }
                    }

                    if index == 1 { Spacer().frame(width: 16) }
                    if index == 2 { Spacer().frame(width: 32) }
                }
            }

            // Bottom row with globe, backspace, space, and send
            HStack(spacing: 4) {
                // Globe (next keyboard)
                Button(action: onNextKeyboard) {
                    Image(systemName: "globe")
                        .font(.system(size: 18))
                        .frame(width: 44, height: 46)
                        .background(Color(.tertiarySystemBackground))
                        .clipShape(RoundedRectangle(cornerRadius: 6))
                }
                .buttonStyle(.plain)

                // Backspace
                Button(action: onBackspace) {
                    Image(systemName: "delete.left")
                        .font(.system(size: 18))
                        .frame(width: 44, height: 46)
                        .background(Color(.tertiarySystemBackground))
                        .clipShape(RoundedRectangle(cornerRadius: 6))
                }
                .buttonStyle(.plain)

                // Space bar
                Button(action: onSpace) {
                    Text("space")
                        .font(.system(size: 12))
                        .foregroundStyle(.tertiary)
                        .frame(maxWidth: .infinity)
                        .frame(height: 46)
                        .background(Color(.secondarySystemBackground))
                        .clipShape(RoundedRectangle(cornerRadius: 6))
                }
                .buttonStyle(.plain)

                // Send button
                Button(action: onSend) {
                    Text("↑")
                        .font(.system(size: 22, weight: .bold))
                        .foregroundStyle(canSend ? Color(.systemBackground) : Color(.tertiaryLabel))
                        .frame(width: 60, height: 46)
                        .background(canSend ? Color.accentColor : Color(.tertiarySystemBackground))
                        .clipShape(RoundedRectangle(cornerRadius: 6))
                        .scaleEffect(canSend ? 1.0 : 0.95)
                        .animation(.easeInOut(duration: 0.15), value: canSend)
                }
                .buttonStyle(.plain)
                .disabled(!canSend)
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
