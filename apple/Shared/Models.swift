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
        lines.append("Please call \(wearerName) or go to them now. If you can't reach them and think they're in danger, call 911.")
        return lines.joined(separator: "\n")
    }
}

/// Settings chosen on the iPhone.
struct NightSettings: Codable {
    var wearerName: String = ""
    var contacts: [TrustedContact] = []
    var checkInMinutes: Int = 30
    var responseWindowSeconds: Int = 60
    /// Your own HTTPS relay that sends SMS (see /relay). iOS does not let apps send texts silently.
    var relayURL: URL? = nil
    var relayToken: String = ""
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
