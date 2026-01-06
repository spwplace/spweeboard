import SwiftUI

/// Main container app for spweebo'ard.
/// Provides settings, ground management, and keyboard setup instructions.
@main
struct SpweeboardApp: App {
    @StateObject private var groundStore = GroundStore()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(groundStore)
        }
    }
}
