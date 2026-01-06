import Foundation

/// A ground context for SPW expression interpretation.
struct Ground: Identifiable, Codable, Equatable {
    let id: String
    var name: String
    var content: String
    var isSpw: Bool

    init(id: String = UUID().uuidString, name: String, content: String, isSpw: Bool = false) {
        self.id = id
        self.name = name
        self.content = content
        self.isSpw = isSpw
    }
}

/// Observable store for ground contexts, shared via App Groups.
@MainActor
final class GroundStore: ObservableObject {
    @Published private(set) var grounds: [Ground] = []

    private let appGroupId = "group.com.spwashi.spweeboard"
    private let fileName = "grounds.json"

    init() {
        load()
    }

    private var fileURL: URL? {
        FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: appGroupId)?
            .appendingPathComponent(fileName)
    }

    func load() {
        guard let url = fileURL,
              FileManager.default.fileExists(atPath: url.path) else {
            grounds = Self.defaultGrounds
            return
        }

        do {
            let data = try Data(contentsOf: url)
            grounds = try JSONDecoder().decode([Ground].self, from: data)
        } catch {
            print("Failed to load grounds: \(error)")
            grounds = Self.defaultGrounds
        }
    }

    func save() {
        guard let url = fileURL else { return }

        do {
            let data = try JSONEncoder().encode(grounds)
            try data.write(to: url, options: .atomic)
        } catch {
            print("Failed to save grounds: \(error)")
        }
    }

    func add(_ ground: Ground) {
        grounds.append(ground)
        save()
    }

    func delete(at indexSet: IndexSet) {
        grounds.remove(atOffsets: indexSet)
        save()
    }

    func update(_ ground: Ground) {
        if let index = grounds.firstIndex(where: { $0.id == ground.id }) {
            grounds[index] = ground
            save()
        }
    }

    /// Default grounds to seed the library.
    static let defaultGrounds: [Ground] = [
        Ground(
            name: "Software Development",
            content: ".{software}",
            isSpw: true
        ),
        Ground(
            name: "Creative Writing",
            content: "@[poetry]~",
            isSpw: true
        ),
        Ground(
            name: "Work Mode",
            content: "@[work].{utility}",
            isSpw: true
        ),
    ]
}
