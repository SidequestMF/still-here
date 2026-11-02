# Safety limits

Please read this before using or promoting Still Here.

## What it can't do

- **It can't detect drugs.** It notices changes in your body and your responses. Many drugs, and alcohol, cause those changes; so do tiredness, a quiet conversation, and a dead battery.
- **It will miss some cases.** A person can be drugged while their heart rate and movement stay within what the app treats as normal. Scheduled check-ins are the backstop, so a missed check-in always leads to an alert.
- **It will raise some false check-ins.** Sitting very still for 15 minutes, or a calm heart rate after a busy night, can start a check-in. A check-in is a quick tap test, not an alert; only failing it twice or not answering sends texts.
- **Alerts depend on technology working:** a charged watch and phone, a network connection, the relay service running, and contacts who read their texts.
- **It can't call 911 for you.** Apps aren't allowed to. Contacts are told to call 911 if they can't reach you.
- **It never contacts campus police itself.** In campus mode, friends get the campus police number. Automatic police alerts would need each department's agreement and would risk false alarms.
- **Campus numbers can go out of date.** The starter list shows the date each number was checked, and the test suite fails once a number is a year old.

## Design choices made for safety

- **Compare people to themselves.** Every threshold is relative to the wearer's own baseline that night, never a fixed "normal" number.
- **A check-in before an alert.** Nothing is sent without first giving the wearer a chance to answer, except the hold-for-help button.
- **Silence means alert.** Not answering is treated as a reason to alert, not a reason to wait.
- **Cancelling should need the wearer.** An all-clear should require the wearer's passcode (still to be built), so someone else can't silence an alert by tapping the watch.
- **The relay writes the text.** The relay builds the message from fixed wording, so a leaked token can't be used to send arbitrary texts.

## Privacy

- Location is read only when an alert goes out, and sent only to the wearer's chosen contacts.
- Heart-rate and motion data stay on the watch. Nothing is stored in Apple Health as a workout.
- The relay keeps no records beyond a one-hour list of alert IDs used to avoid duplicate texts.

## Before anyone relies on it

1. Build and run on real Apple Watches across models (see `apple/README.md`).
2. Test battery life over a full night.
3. Collect consented real-world data and tune thresholds (see `RESEARCH.md`).
4. Add passcode-protected all-clear.
5. Have people with lived experience, advocates, and clinicians review the flow.
