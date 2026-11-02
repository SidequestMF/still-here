import Foundation
import WatchConnectivity

/// Watch side of the watch ↔ iPhone connection.
/// Receives settings from the phone; sends alerts to the phone, and also straight to the
/// relay when the watch has its own internet (Wi-Fi or cellular), in case the phone is gone.
@MainActor
final class WatchPhoneLink: NSObject, ObservableObject {
    @Published var lastDeliveryNote = "Sending…"
    var onSettings: ((NightSettings) -> Void)?

    private var latestSettings = NightSettings()

    func activate() {
        guard WCSession.isSupported() else { return }
        WCSession.default.delegate = self
        WCSession.default.activate()
    }

    func sendAlert(_ event: AlertEvent) {
        guard let data = try? JSONEncoder().encode(event) else { return }
        let session = WCSession.default
        var routes: [String] = []

        if session.activationState == .activated {
            if session.isReachable {
                session.sendMessage(["alert": data], replyHandler: nil) { [weak self] _ in
                    // Fall back to the queued route if live delivery fails.
                    WCSession.default.transferUserInfo(["alert": data])
                    Task { @MainActor in self?.lastDeliveryNote = "Phone didn't answer. Retrying." }
                }
                routes.append("iPhone")
            } else {
                session.transferUserInfo(["alert": data])   // delivered when the phone reconnects
                routes.append("iPhone (when reachable)")
            }
        }
        if latestSettings.relayURL != nil {
            Task { await sendDirect(event) }
            routes.append("watch internet")
        }
        lastDeliveryNote = routes.isEmpty
            ? "Can't reach your iPhone or the internet. Keep the help screen open."
            : "Sent via " + routes.joined(separator: " and ") + "."
    }

    private func sendDirect(_ event: AlertEvent) async {
        guard let url = latestSettings.relayURL else { return }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("Bearer \(latestSettings.relayToken)", forHTTPHeaderField: "Authorization")
        // The relay needs contacts too, since this path skips the phone.
        let wrapped = RelayRequest(event: event, contacts: latestSettings.contacts)
        req.httpBody = try? JSONEncoder.relay.encode(wrapped)
        _ = try? await URLSession.shared.data(for: req)
    }
}

extension WatchPhoneLink: WCSessionDelegate {
    nonisolated func session(_ session: WCSession, activationDidCompleteWith state: WCSessionActivationState, error: Error?) {
        let ctx = session.receivedApplicationContext
        Task { @MainActor in self.apply(ctx) }
    }
    nonisolated func session(_ session: WCSession, didReceiveApplicationContext ctx: [String: Any]) {
        Task { @MainActor in self.apply(ctx) }
    }
    private func apply(_ ctx: [String: Any]) {
        guard let data = ctx["settings"] as? Data,
              let s = try? JSONDecoder().decode(NightSettings.self, from: data) else { return }
        latestSettings = s
        onSettings?(s)
    }
}
