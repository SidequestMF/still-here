import Foundation
import HealthKit
import CoreMotion
import WatchKit
import Combine

/// Runs during night mode. Keeps the app alive with a workout session (the only reliable way
/// to get heart rate every few seconds on Apple Watch), watches heart rate and motion,
/// and decides when to ask "Are you OK?".
@MainActor
final class MonitorEngine: NSObject, ObservableObject {

    enum Phase: Equatable { case idle, armed, checking(Trigger), alerted(AlertCause) }

    @Published private(set) var phase: Phase = .idle
    @Published private(set) var heartRate: Double?
    @Published private(set) var baselineHeartRate: Double?
    @Published private(set) var nextCheckIn: Date?
    @Published var baselineTestSeconds: Double?          // set by calibration
    /// Set when a check-in was passed shakily and the student has a campus safe ride.
    @Published var rideOffer: CampusLine?
    /// Set after an all-clear, to show next-morning advice.
    @Published var showAftercare = false

    var settings = NightSettings()
    var onAlert: ((AlertEvent) -> Void)?

    private let health = HKHealthStore()
    private var session: HKWorkoutSession?
    private var builder: HKLiveWorkoutBuilder?
    private let motion = CMMotionManager()

    private var hrWatcher = HeartRateWatcher(start: Date())
    private var motionWatcher = MotionWatcher(start: Date())
    private var checkTimer: Timer?
    private var scheduleTimer: Timer?
    private var contactTimer: Timer?
    private var lastHeartRateAt = Date()
    private(set) var currentTrigger: Trigger = .scheduled

    // MARK: Permissions

    func requestAuthorization() async throws {
        let read: Set<HKObjectType> = [HKQuantityType(.heartRate), HKQuantityType(.heartRateVariabilitySDNN)]
        let share: Set<HKSampleType> = [HKObjectType.workoutType()]
        try await health.requestAuthorization(toShare: share, read: read)
    }

    // MARK: Night mode

    func startNight() throws {
        let config = HKWorkoutConfiguration()
        config.activityType = .other
        config.locationType = .indoor
        let session = try HKWorkoutSession(healthStore: health, configuration: config)
        let builder = session.associatedWorkoutBuilder()
        builder.dataSource = HKLiveWorkoutDataSource(healthStore: health, workoutConfiguration: config)
        session.delegate = self
        builder.delegate = self
        self.session = session
        self.builder = builder

        let armedAt = Date()
        hrWatcher = HeartRateWatcher(start: armedAt)
        motionWatcher = MotionWatcher(start: armedAt)
        baselineHeartRate = nil
        session.startActivity(with: armedAt)
        builder.beginCollection(withStart: armedAt) { _, _ in }

        startMotion()
        phase = .armed
        scheduleNextCheckIn()
        lastHeartRateAt = Date()
        contactTimer?.invalidate()
        contactTimer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.phase == .armed else { return }
                if Date().timeIntervalSince(self.lastHeartRateAt) > Tuning.watchOffSeconds {
                    self.lastHeartRateAt = Date()   // one check per gap, not one every 30 s
                    self.beginCheck(.watchOff)
                }
            }
        }
    }

    func endNight() {
        checkTimer?.invalidate(); scheduleTimer?.invalidate(); contactTimer?.invalidate()
        motion.stopDeviceMotionUpdates()
        session?.end()
        // We don't save a workout to Health; this is a safety session, not exercise.
        builder?.discardWorkout()
        session = nil; builder = nil
        phase = .idle
        nextCheckIn = nil
    }

    // MARK: Check-ins

    private func scheduleNextCheckIn() {
        scheduleTimer?.invalidate()
        let next = Date().addingTimeInterval(TimeInterval(settings.checkInMinutes * 60))
        nextCheckIn = next
        scheduleTimer = Timer.scheduledTimer(withTimeInterval: next.timeIntervalSinceNow, repeats: false) { [weak self] _ in
            Task { @MainActor in self?.beginCheck(.scheduled) }
        }
    }

    func beginCheck(_ trigger: Trigger) {
        guard phase == .armed else { return }
        currentTrigger = trigger
        phase = .checking(trigger)
        WKInterfaceDevice.current().play(.notification)
        checkTimer?.invalidate()
        checkTimer = Timer.scheduledTimer(withTimeInterval: TimeInterval(settings.responseWindowSeconds), repeats: false) { [weak self] _ in
            Task { @MainActor in self?.raiseAlert(.noResponse, testSeconds: nil) }
        }
        // Keep buzzing every few seconds so the wearer notices in a loud room.
        Task { @MainActor in
            while case .checking = phase {
                WKInterfaceDevice.current().play(.retry)
                try? await Task.sleep(for: .seconds(4))
            }
        }
    }

    /// Called by the response test view. Returns true if the check passed.
    @discardableResult
    func recordTest(seconds: Double, errors: Int, isFinalAttempt: Bool) -> Bool {
        let attempt = isFinalAttempt ? 2 : 1
        let passed = ResponseRule.passes(seconds: seconds, errors: errors, baseline: baselineTestSeconds)
        if passed {
            checkTimer?.invalidate()
            // Only a heart-rate check means "this heart rate is fine for me".
            if currentTrigger == .heartRateChange { hrWatcher.snooze(from: Date()) }
            phase = .armed
            WKInterfaceDevice.current().play(.success)
            scheduleNextCheckIn()
            if let ride = settings.campus?.ride,
               ResponseRule.isShaky(seconds: seconds, errors: errors, baseline: baselineTestSeconds, attempt: attempt) {
                rideOffer = ride
            }
        } else if isFinalAttempt {
            raiseAlert(.failedTests, testSeconds: seconds)
        }
        return passed
    }

    func askForHelp() {
        if case .checking = phase {} else { currentTrigger = .helpButton }
        raiseAlert(.askedForHelp, testSeconds: nil)
    }

    /// The real app should require the wearer's passcode (or the phone) to send an all-clear,
    /// so someone else can't cancel the alert by tapping the watch.
    func allClear() {
        phase = .armed
        scheduleNextCheckIn()
        showAftercare = true
    }

    private func raiseAlert(_ cause: AlertCause, testSeconds: Double?) {
        checkTimer?.invalidate()
        phase = .alerted(cause)
        WKInterfaceDevice.current().play(.failure)
        let event = AlertEvent(
            wearerName: settings.wearerName,
            trigger: currentTrigger,
            cause: cause,
            createdAt: Date(),
            lastHeartRate: heartRate,
            baselineHeartRate: baselineHeartRate,
            testSeconds: testSeconds,
            baselineTestSeconds: baselineTestSeconds,
            campus: settings.campus.map { AlertCampus(policeName: $0.police.name, policePhone: $0.police.phone, home: $0.home) })
        onAlert?(event)
    }

    // MARK: Heart rate

    fileprivate func handleHeartRate(_ bpm: Double) {
        heartRate = bpm
        let now = Date()
        lastHeartRateAt = now
        let shouldCheck = hrWatcher.add(bpm: bpm, at: now,
                                        secondsSinceMovement: motionWatcher.secondsSinceMovement(at: now),
                                        intensity: motionWatcher.intensity)
        baselineHeartRate = hrWatcher.baseline
        if shouldCheck && phase == .armed { beginCheck(.heartRateChange) }
    }

    // MARK: Motion

    private func startMotion() {
        guard motion.isDeviceMotionAvailable else { return }
        motion.deviceMotionUpdateInterval = 0.1
        motion.startDeviceMotionUpdates(to: .main) { [weak self] data, _ in
            guard let self, let a = data?.userAcceleration else { return }
            let g = sqrt(a.x * a.x + a.y * a.y + a.z * a.z)
            Task { @MainActor in self.handleMotion(g) }
        }
    }

    private func handleMotion(_ g: Double) {
        guard let event = motionWatcher.add(g: g, at: Date()), phase == .armed else { return }
        switch event {
        case .stumble:   beginCheck(.stumble)
        case .stillness: beginCheck(.stillness)
        }
    }
}

// MARK: - HealthKit delegates

extension MonitorEngine: HKWorkoutSessionDelegate, HKLiveWorkoutBuilderDelegate {
    nonisolated func workoutSession(_ s: HKWorkoutSession, didChangeTo to: HKWorkoutSessionState,
                                    from: HKWorkoutSessionState, date: Date) {}
    nonisolated func workoutSession(_ s: HKWorkoutSession, didFailWithError error: Error) {}
    nonisolated func workoutBuilderDidCollectEvent(_ b: HKLiveWorkoutBuilder) {}

    nonisolated func workoutBuilder(_ b: HKLiveWorkoutBuilder, didCollectDataOf types: Set<HKSampleType>) {
        let hrType = HKQuantityType(.heartRate)
        guard types.contains(hrType),
              let q = b.statistics(for: hrType)?.mostRecentQuantity() else { return }
        let bpm = q.doubleValue(for: HKUnit.count().unitDivided(by: .minute()))
        Task { @MainActor in self.handleHeartRate(bpm) }
    }
}
