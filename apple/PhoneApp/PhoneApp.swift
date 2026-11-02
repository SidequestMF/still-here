import SwiftUI
import WatchConnectivity
import CoreLocation
import UserNotifications

@main
struct StillHerePhoneApp: App {
    @StateObject private var store = SettingsStore()
    @StateObject private var link = PhoneWatchLink()

    var body: some Scene {
        WindowGroup {
            SetupView()
                .environmentObject(store)
                .task {
                    link.store = store
                    store.onChange = { link.pushSettings() }
                    link.activate()
                    await LocationProvider.shared.requestPermission()
                    _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])
                }
        }
    }
}

// MARK: Settings

@MainActor
final class SettingsStore: ObservableObject {
    @Published var settings: NightSettings {
        didSet { save(); onChange?() }
    }
    var onChange: (() -> Void)?
    private let key = "stillhere.settings"

    init() {
        if let data = UserDefaults.standard.data(forKey: key),
           let s = try? JSONDecoder().decode(NightSettings.self, from: data) {
            settings = s
        } else {
            settings = NightSettings()
        }
    }
    private func save() {
        if let data = try? JSONEncoder().encode(settings) { UserDefaults.standard.set(data, forKey: key) }
    }
}

struct SetupView: View {
    @EnvironmentObject var store: SettingsStore
    @State private var newName = ""
    @State private var newPhone = ""
    @State private var relayText = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("You") {
                    TextField("First name", text: $store.settings.wearerName)
                        .textContentType(.givenName)
                }
                Section {
                    ForEach(store.settings.contacts) { c in
                        VStack(alignment: .leading) {
                            Text(c.name).bold()
                            Text(c.phone).font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                    .onDelete { store.settings.contacts.remove(atOffsets: $0) }
                    TextField("Name", text: $newName)
                    TextField("Mobile number, e.g. +16085551234", text: $newPhone)
                        .keyboardType(.phonePad)
                    Button("Add contact") {
                        let phone = newPhone.filter { "+0123456789".contains($0) }
                        guard !newName.isEmpty, phone.count >= 10 else { return }
                        store.settings.contacts.append(TrustedContact(name: newName, phone: phone))
                        newName = ""; newPhone = ""
                    }
                } header: { Text("Who gets alerted") } footer: {
                    Text("Tell these people you added them, so an alert from an unknown number doesn't get ignored.")
                }
                Section("Check-ins") {
                    Stepper("Every \(store.settings.checkInMinutes) minutes",
                            value: $store.settings.checkInMinutes, in: 10...120, step: 5)
                    Stepper("Answer within \(store.settings.responseWindowSeconds) seconds",
                            value: $store.settings.responseWindowSeconds, in: 30...180, step: 15)
                }
                Section {
                    TextField("https://your-relay.example.com/alert", text: $relayText)
                        .keyboardType(.URL).textInputAutocapitalization(.never)
                        .onSubmit { store.settings.relayURL = URL(string: relayText) }
                    SecureField("Relay token", text: $store.settings.relayToken)
                } header: { Text("Text message relay") } footer: {
                    Text("iPhone apps can't send texts on their own. Alerts go through a small relay service you run. See relay/README.md.")
                }
                Section {
                    Text("Still Here is not a medical device and can't detect drugs. It notices when you stop responding normally. Call 911 in an emergency.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Still Here")
            .onAppear { relayText = store.settings.relayURL?.absoluteString ?? "" }
        }
    }
}

// MARK: Watch link (phone side)

@MainActor
final class PhoneWatchLink: NSObject, ObservableObject {
    weak var store: SettingsStore?
    private var handled = Set<UUID>()

    func activate() {
        guard WCSession.isSupported() else { return }
        WCSession.default.delegate = self
        WCSession.default.activate()
    }

    func pushSettings() {
        guard let s = store?.settings, let data = try? JSONEncoder().encode(s),
              WCSession.default.activationState == .activated else { return }
        try? WCSession.default.updateApplicationContext(["settings": data])
    }

    fileprivate func handleAlert(_ data: Data) async {
        guard var event = try? JSONDecoder().decode(AlertEvent.self, from: data),
              !handled.contains(event.id), let settings = store?.settings else { return }
        handled.insert(event.id)

        if let loc = await LocationProvider.shared.currentLocation() {
            event.latitude = loc.coordinate.latitude
            event.longitude = loc.coordinate.longitude
        }
        let sent = await AlertRelay.send(event, settings: settings)
        if !sent { await notifyLocally(event) }
    }

    /// Last resort if the relay is unreachable: a loud notification on the phone,
    /// in case someone nearby picks it up.
    private func notifyLocally(_ event: AlertEvent) async {
        let content = UNMutableNotificationContent()
        content.title = "Still Here: \(event.wearerName) may need help"
        content.body = event.message()
        content.sound = .defaultCritical
        let req = UNNotificationRequest(identifier: event.id.uuidString, content: content, trigger: nil)
        try? await UNUserNotificationCenter.current().add(req)
    }
}

extension PhoneWatchLink: WCSessionDelegate {
    nonisolated func session(_ s: WCSession, activationDidCompleteWith state: WCSessionActivationState, error: Error?) {
        Task { @MainActor in self.pushSettings() }
    }
    nonisolated func sessionDidBecomeInactive(_ s: WCSession) {}
    nonisolated func sessionDidDeactivate(_ s: WCSession) { WCSession.default.activate() }

    nonisolated func session(_ s: WCSession, didReceiveMessage message: [String: Any]) {
        guard let data = message["alert"] as? Data else { return }
        Task { @MainActor in await self.handleAlert(data) }
    }
    nonisolated func session(_ s: WCSession, didReceiveUserInfo info: [String: Any] = [:]) {
        guard let data = info["alert"] as? Data else { return }
        Task { @MainActor in await self.handleAlert(data) }
    }
}

// MARK: Relay

enum AlertRelay {
    static func send(_ event: AlertEvent, settings: NightSettings) async -> Bool {
        guard let url = settings.relayURL, !settings.contacts.isEmpty else { return false }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.timeoutInterval = 15
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("Bearer \(settings.relayToken)", forHTTPHeaderField: "Authorization")
        req.httpBody = try? JSONEncoder.relay.encode(RelayRequest(event: event, contacts: settings.contacts))
        for attempt in 0..<3 {
            if let (_, resp) = try? await URLSession.shared.data(for: req),
               let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode) {
                return true
            }
            try? await Task.sleep(for: .seconds(Double(2 << attempt)))
        }
        return false
    }
}

// MARK: Location

@MainActor
final class LocationProvider: NSObject, CLLocationManagerDelegate {
    static let shared = LocationProvider()
    private let manager = CLLocationManager()
    private var waiting: CheckedContinuation<CLLocation?, Never>?

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
    }

    func requestPermission() async {
        // "Always" is needed so the phone can attach a location when woken by the watch.
        manager.requestAlwaysAuthorization()
    }

    func currentLocation() async -> CLLocation? {
        if let recent = manager.location, recent.timestamp.timeIntervalSinceNow > -120 { return recent }
        return await withCheckedContinuation { c in
            waiting = c
            manager.requestLocation()
            // Don't hold the alert hostage to GPS: give up after 10 seconds.
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(10))
                self.finish(self.manager.location)
            }
        }
    }

    private func finish(_ loc: CLLocation?) {
        waiting?.resume(returning: loc)
        waiting = nil
    }

    nonisolated func locationManager(_ m: CLLocationManager, didUpdateLocations locs: [CLLocation]) {
        Task { @MainActor in self.finish(locs.last) }
    }
    nonisolated func locationManager(_ m: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in self.finish(nil) }
    }
}
