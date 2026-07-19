import Foundation

/// A stable per-install device id, persisted locally, used to identify this device
/// to the backend's device-session/remote-control endpoints.
enum DeviceIdentity {
    private static let key = "yaytsa.deviceId"

    static var id: String {
        if let existing = UserDefaults.standard.string(forKey: key) {
            return existing
        }
        let new = UUID().uuidString
        UserDefaults.standard.set(new, forKey: key)
        return new
    }
}
