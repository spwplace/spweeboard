import SwiftUI

/// Main view for the container app.
struct ContentView: View {
    @EnvironmentObject var groundStore: GroundStore
    @State private var selectedTab = 0

    var body: some View {
        TabView(selection: $selectedTab) {
            SetupView()
                .tabItem {
                    Label("Setup", systemImage: "keyboard")
                }
                .tag(0)

            GroundLibraryView()
                .tabItem {
                    Label("Grounds", systemImage: "square.stack.3d.up")
                }
                .tag(1)

            SettingsView()
                .tabItem {
                    Label("Settings", systemImage: "gear")
                }
                .tag(2)
        }
        .tint(.primary)
    }
}

/// Setup instructions for enabling the keyboard.
struct SetupView: View {
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    headerSection
                    setupSteps
                    Spacer()
                }
                .padding()
            }
            .navigationTitle("spweebo'ard")
        }
    }

    private var headerSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Symbolic Keyboard")
                .font(.headline)
                .foregroundStyle(.secondary)

            Text("Compose thoughts using SPW cognitive primitives, interpreted by on-device AI.")
                .font(.subheadline)
                .foregroundStyle(.tertiary)
        }
    }

    private var setupSteps: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Enable Keyboard")
                .font(.title3)
                .fontWeight(.semibold)

            SetupStepView(
                number: 1,
                title: "Open Settings",
                description: "Go to Settings → General → Keyboard → Keyboards"
            )

            SetupStepView(
                number: 2,
                title: "Add New Keyboard",
                description: "Tap 'Add New Keyboard...' and select spweebo'ard"
            )

            SetupStepView(
                number: 3,
                title: "Allow Full Access",
                description: "Enable full access for on-device LLM inference"
            )

            Button {
                openSettings()
            } label: {
                Label("Open Keyboard Settings", systemImage: "gear")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
        }
    }

    private func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }
}

struct SetupStepView: View {
    let number: Int
    let title: String
    let description: String

    var body: some View {
        HStack(alignment: .top, spacing: 16) {
            Text("\(number)")
                .font(.headline)
                .foregroundStyle(.white)
                .frame(width: 28, height: 28)
                .background(Circle().fill(.tint))

            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.headline)
                Text(description)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

/// Ground library management view.
struct GroundLibraryView: View {
    @EnvironmentObject var groundStore: GroundStore
    @State private var showingNewGround = false

    var body: some View {
        NavigationStack {
            List {
                if groundStore.grounds.isEmpty {
                    ContentUnavailableView(
                        "No Grounds",
                        systemImage: "square.stack.3d.up.slash",
                        description: Text("Add a ground to provide context for your expressions")
                    )
                } else {
                    ForEach(groundStore.grounds) { ground in
                        GroundRowView(ground: ground)
                    }
                    .onDelete { indexSet in
                        groundStore.delete(at: indexSet)
                    }
                }
            }
            .navigationTitle("Grounds")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        showingNewGround = true
                    } label: {
                        Image(systemName: "plus")
                    }
                }
            }
            .sheet(isPresented: $showingNewGround) {
                NewGroundView()
            }
        }
    }
}

struct GroundRowView: View {
    let ground: Ground

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(ground.name)
                .font(.headline)
            Text(ground.content)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(2)
        }
        .padding(.vertical, 4)
    }
}

struct NewGroundView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject var groundStore: GroundStore

    @State private var name = ""
    @State private var content = ""
    @State private var isSpw = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Name", text: $name)
                    Toggle("SPW Expression", isOn: $isSpw)
                }

                Section(isSpw ? "SPW Content" : "Natural Language") {
                    TextEditor(text: $content)
                        .frame(minHeight: 100)
                        .font(isSpw ? .system(.body, design: .monospaced) : .body)
                }

                if isSpw {
                    Section {
                        Text("SPW symbols: ~ # . ? ! * & @ ^ < > ( ) [ ] { }")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("New Ground")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        let ground = Ground(
                            id: UUID().uuidString,
                            name: name,
                            content: content,
                            isSpw: isSpw
                        )
                        groundStore.add(ground)
                        dismiss()
                    }
                    .disabled(name.isEmpty || content.isEmpty)
                }
            }
        }
    }
}

/// Settings view.
struct SettingsView: View {
    @AppStorage("hapticFeedback") private var hapticFeedback = true
    @AppStorage("streamingPreview") private var streamingPreview = true

    var body: some View {
        NavigationStack {
            Form {
                Section("Keyboard") {
                    Toggle("Haptic Feedback", isOn: $hapticFeedback)
                    Toggle("Streaming Preview", isOn: $streamingPreview)
                }

                Section("Model") {
                    HStack {
                        Text("Active Model")
                        Spacer()
                        Text("Qwen2.5-0.5B")
                            .foregroundStyle(.secondary)
                    }
                }

                Section("About") {
                    HStack {
                        Text("Version")
                        Spacer()
                        Text("0.1.0")
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("Settings")
        }
    }
}

#Preview {
    ContentView()
        .environmentObject(GroundStore())
}
