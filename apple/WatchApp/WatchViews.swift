import SwiftUI
import WatchKit

@main
struct StillHereWatchApp: App {
    @StateObject private var engine = MonitorEngine()
    @StateObject private var link = WatchPhoneLink()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(engine)
                .environmentObject(link)
                .task {
                    link.activate()
                    link.onSettings = { engine.settings = $0 }
                    engine.onAlert = { link.sendAlert($0) }
                    try? await engine.requestAuthorization()
                }
        }
    }
}

struct RootView: View {
    @EnvironmentObject var engine: MonitorEngine

    var body: some View {
        switch engine.phase {
        case .idle:               IdleView()
        case .armed:              ArmedView()
        case .checking(let t):    CheckView(trigger: t)
        case .alerted(let c):     AlertedView(cause: c)
        }
    }
}

// MARK: Idle / calibration

struct IdleView: View {
    @EnvironmentObject var engine: MonitorEngine
    @State private var calibrating = false
    @State private var rounds: [Double] = []

    var body: some View {
        if calibrating {
            VStack(spacing: 4) {
                Text("Tap 1–4 in order").font(.headline)
                Text("Round \(rounds.count + 1) of 2").font(.footnote).foregroundStyle(.secondary)
                ResponseTest { seconds, _ in
                    rounds.append(seconds)
                    if rounds.count == 2 {
                        engine.baselineTestSeconds = rounds.reduce(0, +) / 2
                        calibrating = false
                        try? engine.startNight()
                    }
                }
                .id(rounds.count)   // fresh layout each round
            }
        } else {
            ScrollView {
                VStack(spacing: 10) {
                    Text("Still Here").font(.title3.bold())
                    if engine.settings.contacts.isEmpty {
                        Text("Add trusted contacts in the iPhone app first.")
                            .font(.footnote).multilineTextAlignment(.center)
                    } else {
                        Text("\(engine.settings.contacts.count) contacts ready").font(.footnote)
                        Button("Start night mode") { rounds = []; calibrating = true }
                            .tint(.orange)
                    }
                }
            }
        }
    }
}

// MARK: Armed

struct ArmedView: View {
    @EnvironmentObject var engine: MonitorEngine

    var body: some View {
        ScrollView {
            VStack(spacing: 8) {
                Text("Watching").font(.footnote.weight(.semibold)).foregroundStyle(.green)
                if let next = engine.nextCheckIn {
                    Text(next, style: .timer).font(.system(.title, design: .monospaced))
                    Text("until next check").font(.caption2).foregroundStyle(.secondary)
                }
                if let hr = engine.heartRate {
                    Text("\(Int(hr)) bpm").font(.footnote.monospacedDigit())
                }
                HoldForHelpButton { engine.askForHelp() }
                Button("Check me now") { engine.beginCheck(.manual) }
                Button("End night", role: .destructive) { engine.endNight() }
            }
        }
        .sheet(item: $engine.rideOffer) { ride in RideOfferView(ride: ride) }
        .sheet(isPresented: $engine.showAftercare) { AftercareView(support: engine.settings.campus?.support) }
    }
}

/// Shown when a check-in was passed but slower than usual and the student set a campus ride.
struct RideOfferView: View {
    let ride: CampusLine
    @Environment(\.dismiss) var dismiss

    var body: some View {
        ScrollView {
            VStack(spacing: 8) {
                Text("You passed, but you're slower than usual").font(.headline).multilineTextAlignment(.center)
                Text("Time to head home? \(ride.name) is free.").font(.footnote).multilineTextAlignment(.center)
                Button("Call \(Phone.display(ride.phone))") { call(ride.phone) }.tint(.orange)
                if !ride.hours.isEmpty { Text(ride.hours).font(.caption2).foregroundStyle(.secondary) }
                Button("I'm OK") { dismiss() }
            }
        }
    }
}

/// Next-morning advice after an alert, with the campus confidential line if set.
struct AftercareView: View {
    let support: CampusLine?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 6) {
                Text("Glad you're safe").font(.headline)
                Text("If you think you were drugged, go to an ER soon and ask for a drug screen by name. Many drugs leave the body within a day.")
                    .font(.footnote)
                if let s = support {
                    Text("\(s.name), confidential:").font(.footnote.bold())
                    Button(Phone.display(s.phone)) { call(s.phone) }
                }
                Text("RAINN: 800-656-4673, any time.").font(.caption2).foregroundStyle(.secondary)
            }
        }
    }
}

/// Places a call through the paired iPhone (or the watch's own cellular connection).
func call(_ e164: String) {
    if let url = URL(string: "tel:\(e164)") { WKExtension.shared().openSystemURL(url) }
}

extension CampusLine: Identifiable { var id: String { phone } }

/// Must be held for 3 seconds so it can't fire from a bump.
struct HoldForHelpButton: View {
    var action: () -> Void
    @State private var progress: CGFloat = 0
    @State private var holding = false

    var body: some View {
        ZStack(alignment: .leading) {
            Capsule().stroke(.red, lineWidth: 2)
            Capsule().fill(.red).frame(maxWidth: .infinity).scaleEffect(x: progress, anchor: .leading)
            Text("Hold for help").font(.footnote.bold()).frame(maxWidth: .infinity)
        }
        .frame(height: 40)
        .onLongPressGesture(minimumDuration: 3, pressing: { pressing in
            holding = pressing
            if pressing {
                WKInterfaceDevice.current().play(.start)
                withAnimation(.linear(duration: 3)) { progress = 1 }
            } else {
                withAnimation(.easeOut(duration: 0.2)) { progress = 0 }
            }
        }, perform: action)
        .accessibilityLabel("Hold for three seconds to alert your contacts")
    }
}

// MARK: Check

struct CheckView: View {
    @EnvironmentObject var engine: MonitorEngine
    let trigger: Trigger
    @State private var attempt = 1

    var body: some View {
        VStack(spacing: 4) {
            Text("Are you OK?").font(.headline)
            Text(attempt == 1 ? "Tap 1–4 in order" : "Once more").font(.caption2).foregroundStyle(.secondary)
            ResponseTest { seconds, errors in
                let passed = engine.recordTest(seconds: seconds, errors: errors, isFinalAttempt: attempt >= 2)
                if !passed { attempt += 1 }
            }
            .id(attempt)
            Button("I need help", role: .destructive) { engine.askForHelp() }
                .font(.footnote)
        }
    }
}

// MARK: Alerted

struct AlertedView: View {
    @EnvironmentObject var engine: MonitorEngine
    @EnvironmentObject var link: WatchPhoneLink
    let cause: AlertCause

    var body: some View {
        ScrollView {
            VStack(spacing: 8) {
                Text("Alerting your people").font(.headline).foregroundStyle(.red)
                Text(link.lastDeliveryNote).font(.footnote).multilineTextAlignment(.center)
                // TODO: require the wearer's passcode or iPhone confirmation before all-clear.
                Button("I'm safe now") { engine.allClear() }.tint(.orange)
            }
        }
    }
}

// MARK: Response test

/// Four numbered circles at random spots; tap them in order.
/// Reports total time from the first tap to the last, and the number of wrong taps.
struct ResponseTest: View {
    var onDone: (_ seconds: Double, _ errors: Int) -> Void

    @State private var points: [CGPoint] = []
    @State private var next = 1
    @State private var errors = 0
    @State private var start: Date?

    var body: some View {
        GeometryReader { geo in
            ZStack {
                ForEach(Array(points.enumerated()), id: \.offset) { i, p in
                    let n = i + 1
                    Button {
                        tap(n)
                    } label: {
                        Text("\(n)").font(.title3.bold())
                            .frame(width: 40, height: 40)
                            .background(Circle().fill(n < next ? Color.orange : Color.gray.opacity(0.3)))
                            .overlay(Circle().stroke(n == next ? Color.orange : .clear, lineWidth: 2))
                    }
                    .buttonStyle(.plain)
                    .position(x: p.x * geo.size.width, y: p.y * geo.size.height)
                    .accessibilityLabel("Target \(n)")
                }
            }
            .onAppear { points = Self.layout() }
        }
    }

    private func tap(_ n: Int) {
        if start == nil { start = Date() }
        if n == next {
            next += 1
            WKInterfaceDevice.current().play(.click)
            if next > 4 { onDone(Date().timeIntervalSince(start!), errors) }
        } else {
            errors += 1
            WKInterfaceDevice.current().play(.directionDown)
        }
    }

    static func layout() -> [CGPoint] {
        var pts: [CGPoint] = []
        var tries = 0
        while pts.count < 4 && tries < 500 {
            tries += 1
            let p = CGPoint(x: .random(in: 0.18...0.82), y: .random(in: 0.18...0.82))
            if pts.allSatisfy({ hypot($0.x - p.x, $0.y - p.y) > 0.3 }) { pts.append(p) }
        }
        return pts
    }
}
