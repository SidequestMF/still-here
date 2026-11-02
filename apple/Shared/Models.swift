import Foundation

/// A person who gets alerted. Stored on the iPhone and synced to the watch.
struct TrustedContact: Codable, Identifiable, Hashable {
    var id = UUID()
    var name: String
    var phone: String   // E.164 format, e.g. +16085551234
}

/// What made the watch start a check-in or raise an alert.
enum Trigger: String, Codable {
    case scheduled          // regular timed check-in
    case heartRateChange    // heart rate moved far from the wearer's own baseline
    case stumble            // hard impact followed by little movement
    case stillness          // unusually long stillness during night mode
    case manual             // wearer asked for a check
    case helpButton         // wearer held the help control
    case watchOff           // heart rate stopped arriving (watch removed or lost contact)

    var plainEnglish: String {
        switch self {
        case .scheduled:       return "a scheduled check-in"
        case .heartRateChange: return "an unusual change in heart rate"
        case .stumble:         return "a stumble or fall"
        case .stillness:       return "being unusually still"
        case .manual:          return "a check the wearer asked for"
        case .helpButton:      return "the wearer pressing the help button"
        case .watchOff:        return "the watch losing contact with the wearer's wrist"
        }
    }
}

/// Why an alert went out.
enum AlertCause: String, Codable {
    case noResponse         // check-in timed out
    case failedTests        // response tests much slower than baseline
    case askedForHelp       // wearer tapped "I need help" or held the help button
}

/// The payload passed from watch → phone → relay.
struct AlertEvent: Codable {
    /// The same alert can arrive by two routes (phone and watch); the relay uses this to send it once.
    var id = UUID()
    var timeZone = TimeZone.current.identifier
    var wearerName: String
    var trigger: Trigger
    var cause: AlertCause
    var createdAt: Date
    var lastHeartRate: Double?
    var baselineHeartRate: Double?
    var testSeconds: Double?
    var baselineTestSeconds: Double?
    var latitude: Double?
    var longitude: Double?
    var campus: AlertCampus? = nil

    /// The text message contacts receive. Kept short so it fits in one or two SMS segments.
    func message() -> String {
        let time = DateFormatter.localizedString(from: createdAt, dateStyle: .none, timeStyle: .short)
        var lines = ["Still Here alert for \(wearerName) at \(time)."]
        switch cause {
        case .noResponse:   lines.append("No response to a check-in after \(trigger.plainEnglish).")
        case .failedTests:  lines.append("Response tests were much slower than normal after \(trigger.plainEnglish).")
        case .askedForHelp: lines.append("\(wearerName) asked for help.")
        }
        if let lat = latitude, let lon = longitude {
            lines.append("Location: https://maps.apple.com/?ll=\(lat),\(lon)")
        }
        if let c = campus {
            if !c.home.isEmpty { lines.append("\(wearerName) lives at: \(c.home)") }
            lines.append("Campus police (\(c.policeName)): \(Phone.display(c.policePhone)). They can usually reach \(wearerName) fastest on campus.")
        }
        lines.append("Please call \(wearerName) or go to them now. If you can't reach them and think they're in danger, call 911.")
        return lines.joined(separator: "\n")
    }
}

/// Settings chosen on the iPhone.
/// A campus phone line: police, safe ride, or confidential support. Phone in +1... format.
struct CampusLine: Codable, Hashable {
    var name: String
    var phone: String
    var hours: String = ""
}

/// Campus mode, set up by the student. Police is required; the rest is optional.
struct CampusInfo: Codable, Hashable {
    var name: String
    var police: CampusLine
    var ride: CampusLine?
    var support: CampusLine?
    var home: String = ""        // where they live on campus, to help friends find them
}

/// The small slice of campus info that goes into an alert (and to the relay).
struct AlertCampus: Codable {
    var policeName: String
    var policePhone: String
    var home: String
}

enum Phone {
    /// Any common US format → +1XXXXXXXXXX, or nil.
    static func normalize(_ raw: String) -> String? {
        let d = raw.filter(\.isNumber)
        if d.count == 10 { return "+1" + d }
        if d.count == 11, d.first == "1" { return "+" + d }
        if raw.trimmingCharacters(in: .whitespaces).hasPrefix("+"), (11...15).contains(d.count) { return "+" + d }
        return nil
    }
    /// +16174951212 → 617-495-1212
    static func display(_ e164: String) -> String {
        let d = Array(e164.filter(\.isNumber))
        guard e164.hasPrefix("+1"), d.count == 11 else { return e164 }
        return "\(String(d[1...3]))-\(String(d[4...6]))-\(String(d[7...10]))"
    }
}

struct NightSettings: Codable {
    var wearerName: String = ""
    var contacts: [TrustedContact] = []
    var checkInMinutes: Int = 30
    var responseWindowSeconds: Int = 60
    /// Your own HTTPS relay that sends SMS (see /relay). iOS does not let apps send texts silently.
    var relayURL: URL? = nil
    var relayToken: String = ""
    var campus: CampusInfo? = nil
}

/// All detection thresholds live here.
/// IMPORTANT: these are starting guesses, NOT validated values. They need testing with
/// real people (with ethics review) before anyone relies on them. See docs/RESEARCH.md.
enum Tuning {
    static let heartRateDropFraction: Double = 0.30      // 30% below baseline
    static let heartRateRiseFraction: Double = 0.45      // 45% above baseline while not moving much
    static let heartRateSustainSeconds: Double = 120
    static let heartRateOutShare: Double = 0.8           // 80% of readings in the last 2 min out of range
    static let heartRateSnoozeMinutes: Double = 20       // after a passed check-in
    static let acceptedMinutes: Double = 60              // how long a confirmed-OK level counts as normal
    static let calmIntensityG: Double = 0.1              // below this, a reading counts as "calm"
    static let calmWindowMinutes: Double = 60
    static let calmLagMinutes: Double = 20
    static let calmMinReadings = 60                      // ~5 minutes of calm readings
    static let impactG: Double = 2.5                     // spike in total acceleration
    static let postImpactStillSeconds: Double = 10
    static let stillnessMinutes: Double = 15
    static let watchOffSeconds: Double = 180             // no heart rate this long → check-in
    static let movingMeanG: Double = 0.04                // average user acceleration (g) that counts as moving
    static let failMultiplier: Double = 2.2              // test slower than 2.2× baseline fails
    static let failMinExtraSeconds: Double = 2.5
    static let maxTestErrors = 2
    static let shakyMultiplier: Double = 1.5             // passed, but this much slower than usual → offer a ride
}

/// Body the relay expects. Shared with the iPhone app.
struct RelayRequest: Codable {
    var event: AlertEvent
    var contacts: [TrustedContact]
}

extension JSONEncoder {
    /// Encoder for anything sent to the relay: ISO-8601 dates, which the relay parses.
    static var relay: JSONEncoder {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .iso8601
        return e
    }
}
