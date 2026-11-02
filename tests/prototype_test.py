"""End-to-end tests for the web prototype in a real Chrome engine.

Run:  python3 tests/prototype_test.py
Uses Playwright's fake clock so timeouts (30-second check-ins, 60-second
schedules, 3-second holds) run instantly and the timing of every tap is exact.
"""
import pathlib, re, sys, tempfile
from playwright.sync_api import sync_playwright, expect

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = (ROOT / "web-prototype" / "index.html").read_text()
# The artifact host wraps the page in this skeleton; do the same locally.
PAGE = ('<!doctype html><html><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">'
        '<style>[hidden]{display:none!important}body{margin:0}</style></head><body>' + SRC + '</body></html>')
tmp = pathlib.Path(tempfile.mkdtemp()) / "index.html"
tmp.write_text(PAGE)
URL = tmp.as_uri()
SHOTS = ROOT / "tests" / "screenshots"
SHOTS.mkdir(exist_ok=True)

results = []
def test(name):
    def wrap(fn):
        results.append((name, fn))
        return fn
    return wrap

# ---------- helpers ----------
def fresh(p, browser, width=390, scheme="dark"):
    ctx = browser.new_context(viewport={"width": width, "height": 844}, color_scheme=scheme)
    page = ctx.new_page()
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.on("console", lambda m: errors.append(m.text) if m.type == "error" and "fonts" not in m.text.lower() else None)
    page.clock.install()
    page.goto(URL)
    page.errors = errors
    return page

def tap(page, arena, gaps_ms=(300, 300, 300), wrong=0):
    """Tap 1-4 in `arena` with the given gaps between taps; add `wrong` wrong taps first."""
    sel = f"{arena} .target"
    for _ in range(wrong):
        page.locator(sel, has_text="4").click()
        page.clock.run_for(100)
    page.locator(sel, has_text="1").click()
    for n, gap in zip(("2", "3", "4"), gaps_ms):
        page.clock.run_for(gap)
        page.locator(sel, has_text=n).click()
    page.clock.run_for(600)   # let setTimeout-driven transitions run

def setup(page, name="Jess", contacts="Maya (roommate)\nSam (sister)", demo=True):
    page.fill("#f-name", name)
    page.fill("#f-contacts", contacts)
    if not demo:
        page.uncheck("#f-demo")
    page.click("#setup button[type=submit]")

def calibrate(page):
    tap(page, "#calib-arena")
    tap(page, "#calib-arena")

def armed(page, **kw):
    setup(page, **kw); calibrate(page)
    expect(page.locator("#s-armed")).to_be_visible()

def visible(page, sid):
    expect(page.locator(sid)).to_be_visible()

# ---------- tests ----------
@test("Page loads on the setup screen with no script errors")
def _(page):
    visible(page, "#s-setup")
    for s in ("#s-calib", "#s-armed", "#s-check", "#s-alert"):
        expect(page.locator(s)).to_be_hidden()

@test("Can't continue with no contacts")
def _(page):
    setup(page, contacts="   \n  ")
    visible(page, "#s-setup")

@test("Calibration takes two rounds, then night mode starts with a baseline")
def _(page):
    setup(page)
    visible(page, "#s-calib")
    tap(page, "#calib-arena")
    expect(page.locator("#calib-round")).to_have_text("2")
    tap(page, "#calib-arena")
    visible(page, "#s-armed")
    # 0.9 s of scripted taps; allow for slower test machines
    expect(page.locator("#baseline")).to_have_text(re.compile(r"^(0\.9|1\.[0-9])s$"))
    expect(page.locator("#log")).to_contain_text("2 contacts ready")

@test("Wrong taps during calibration don't break it")
def _(page):
    setup(page)
    tap(page, "#calib-arena", wrong=2)
    tap(page, "#calib-arena")
    visible(page, "#s-armed")

@test("Check-in passed at normal speed returns to night mode")
def _(page):
    armed(page)
    page.click("#check-now")
    visible(page, "#s-check")
    tap(page, "#check-arena", gaps_ms=(400, 400, 400))
    visible(page, "#s-armed")
    expect(page.locator("#log")).to_contain_text(re.compile(r"Passed check in 1\.[2-9]s"))

@test("Slow but within the limit passes (2.4s vs 0.9s baseline; limit 3.4s)")
def _(page):
    armed(page)
    page.click("#check-now")
    tap(page, "#check-arena", gaps_ms=(800, 800, 800))
    visible(page, "#s-armed")

@test("Too slow twice → alert")
def _(page):
    armed(page)
    page.click("#check-now")
    tap(page, "#check-arena", gaps_ms=(1500, 1500, 1500))
    visible(page, "#s-check")
    expect(page.locator("#check-left")).to_contain_text("Try once more")
    tap(page, "#check-arena", gaps_ms=(1500, 1500, 1500))
    visible(page, "#s-alert")
    expect(page.locator("#alert-reason")).to_contain_text("much slower than normal")

@test("Too slow once, then normal on the retry → passes")
def _(page):
    armed(page)
    page.click("#check-now")
    tap(page, "#check-arena", gaps_ms=(1500, 1500, 1500))
    tap(page, "#check-arena", gaps_ms=(300, 300, 300))
    visible(page, "#s-armed")

@test("Three wrong taps fail an attempt even when fast")
def _(page):
    armed(page)
    page.click("#check-now")
    tap(page, "#check-arena", wrong=3)
    expect(page.locator("#check-left")).to_contain_text("Try once more")
    tap(page, "#check-arena", wrong=3)
    visible(page, "#s-alert")

@test("Ignoring a check-in for 30 seconds → alert")
def _(page):
    armed(page)
    page.click("#check-now")
    page.clock.run_for(29_000)
    visible(page, "#s-check")
    page.clock.run_for(1_500)
    visible(page, "#s-alert")
    expect(page.locator("#alert-reason")).to_contain_text("no response")

@test("Starting the test but stalling still alerts at the deadline")
def _(page):
    armed(page)
    page.click("#check-now")
    page.locator("#check-arena .target", has_text="1").click()
    page.clock.run_for(31_000)
    visible(page, "#s-alert")

@test("'I need help' alerts immediately")
def _(page):
    armed(page)
    page.click("#check-now")
    page.click("#need-help")
    visible(page, "#s-alert")
    expect(page.locator("#alert-reason")).to_have_text('You tapped "I need help."')

@test("Alert lists every contact and a message with the wearer's name and 911 advice")
def _(page):
    armed(page, name="Jess", contacts="Maya (roommate)\nSam (sister)\nDee")
    page.click("#check-now"); page.click("#need-help")
    expect(page.locator("#alert-contacts li")).to_have_count(3)
    msg = page.locator("#alert-msg")
    expect(msg).to_contain_text("Still Here alert for Jess")
    expect(msg).to_contain_text("call 911")
    expect(page.locator("#log")).to_contain_text("Alert sent to 3 contacts")

@test("Holding the help button for 1 second does nothing")
def _(page):
    armed(page)
    box = page.locator("#hold").bounding_box()
    page.mouse.move(box["x"] + 20, box["y"] + 20)
    page.mouse.down(); page.clock.run_for(1000); page.mouse.up()
    page.clock.run_for(3000)
    visible(page, "#s-armed")

@test("Holding the help button for 3 seconds alerts")
def _(page):
    armed(page)
    box = page.locator("#hold").bounding_box()
    page.mouse.move(box["x"] + 20, box["y"] + 20)
    page.mouse.down(); page.clock.run_for(3200); page.mouse.up()
    visible(page, "#s-alert")
    expect(page.locator("#alert-reason")).to_contain_text("held the help button")

@test("Sliding a finger off the help button cancels it")
def _(page):
    armed(page)
    box = page.locator("#hold").bounding_box()
    page.mouse.move(box["x"] + 20, box["y"] + 20)
    page.mouse.down(); page.clock.run_for(1500)
    page.mouse.move(box["x"] + 20, box["y"] - 200)
    page.clock.run_for(2500); page.mouse.up()
    visible(page, "#s-armed")

@test("Keyboard: holding Space on the help button for 3 seconds alerts")
def _(page):
    armed(page)
    page.focus("#hold")
    page.keyboard.down(" "); page.clock.run_for(3200); page.keyboard.up(" ")
    visible(page, "#s-alert")

@test("Simulated heart-rate drop leads to a check-in after a few seconds")
def _(page):
    armed(page)
    page.click("[data-sim=hr]")
    visible(page, "#s-armed")
    page.clock.run_for(4000)
    visible(page, "#s-check")
    expect(page.locator("#check-reason")).to_contain_text("heart rate dropped")

@test("Simulated fall and stillness each start a check-in")
def _(page):
    armed(page)
    page.click("[data-sim=fall]")
    expect(page.locator("#check-reason")).to_contain_text("stumble or a fall")
    tap(page, "#check-arena")
    page.click("[data-sim=still]")
    expect(page.locator("#check-reason")).to_contain_text("haven't moved")

@test("Demo mode: scheduled check-in arrives after 60 seconds")
def _(page):
    armed(page)
    page.clock.run_for(58_000)
    visible(page, "#s-armed")
    page.clock.run_for(3_000)
    visible(page, "#s-check")
    expect(page.locator("#check-reason")).to_have_text("Scheduled check-in.")

@test("Normal mode: no check-in after 60 seconds; one at 30 minutes")
def _(page):
    armed(page, demo=False)
    page.clock.run_for(61_000)
    visible(page, "#s-armed")
    page.clock.run_for(28 * 60_000 + 50_000)   # 29:51 since arming
    visible(page, "#s-armed")
    page.clock.run_for(15_000)                  # 30:06
    visible(page, "#s-check")

@test("After passing, the next check-in is scheduled again")
def _(page):
    armed(page)
    page.clock.run_for(61_000); tap(page, "#check-arena")
    visible(page, "#s-armed")
    page.clock.run_for(61_000)
    visible(page, "#s-check")

@test("A pending heart-rate check doesn't fire after the wearer ends night mode")
def _(page):
    armed(page)
    page.click("[data-sim=hr]")
    page.click("#end-night")
    page.clock.run_for(5000)
    visible(page, "#s-setup")

@test("Ending and quickly restarting night mode doesn't carry over an old heart-rate check")
def _(page):
    armed(page)
    page.click("[data-sim=hr]")
    page.click("#end-night")
    page.click("#setup button[type=submit]"); calibrate(page)
    page.clock.run_for(5000)
    visible(page, "#s-armed")

@test("A fall during a pending heart-rate check gives one check-in, not two")
def _(page):
    armed(page)
    page.click("[data-sim=hr]")
    page.click("[data-sim=fall]")
    tap(page, "#check-arena")
    page.clock.run_for(5000)
    visible(page, "#s-armed")

@test("All-clear shows next-morning advice, then back to night mode; Start over goes to setup")
def _(page):
    armed(page)
    page.click("#check-now"); page.click("#need-help")
    page.click("#all-clear")
    visible(page, "#s-after")
    expect(page.locator("#s-after")).to_contain_text("Ask for a drug screen by name")
    expect(page.locator("#support-box")).to_be_hidden()
    page.click("#after-back")
    visible(page, "#s-armed")
    expect(page.locator("#log")).to_contain_text("All-clear sent")
    page.click("#check-now"); page.click("#need-help")
    page.click("#restart")
    visible(page, "#s-setup")

@test("No timers keep running after an alert (no surprise check-ins)")
def _(page):
    armed(page)
    page.click("#check-now"); page.click("#need-help")
    page.clock.run_for(5 * 60_000)
    visible(page, "#s-alert")

@test("Names with HTML are shown as plain text, not run")
def _(page):
    armed(page, name='<img src=x onerror="window.pwned=1">', contacts='<b>Bold</b>')
    page.click("#check-now"); page.click("#need-help")
    page.clock.run_for(500)
    assert page.evaluate("window.pwned") is None
    expect(page.locator("#alert-contacts li").first).to_contain_text("<b>Bold</b>")

@test("Settings are remembered after reload")
def _(page):
    setup(page, name="Ana", contacts="Lee")
    page.reload()
    assert page.input_value("#f-name") == "Ana"
    assert page.input_value("#f-contacts") == "Lee"

@test("Every screen fits a 320px-wide phone with no sideways scrolling")
def _(page):
    page.set_viewport_size({"width": 320, "height": 640})
    def no_overflow(label):
        w = page.evaluate("[document.documentElement.scrollWidth, window.innerWidth]")
        assert w[0] <= w[1], f"{label}: page is {w[0]}px wide in a {w[1]}px screen"
    no_overflow("setup"); setup(page)
    no_overflow("calibration"); calibrate(page)
    no_overflow("night mode"); page.click("#check-now")
    no_overflow("check-in"); page.click("#need-help")
    no_overflow("alert")

@test("Targets in the tap test never overlap and stay inside the box (50 layouts)")
def _(page):
    def check_layout():
        boxes = page.evaluate("""() => {
          const a = document.querySelector('#calib-arena').getBoundingClientRect();
          return [...document.querySelectorAll('#calib-arena .target')].map(t => {
            const r = t.getBoundingClientRect();
            return {l: r.left - a.left, t: r.top - a.top, r: a.right - r.right, b: a.bottom - r.bottom,
                    cx: r.left + r.width / 2, cy: r.top + r.height / 2};
          });
        }""")
        assert len(boxes) == 4
        for b in boxes:
            assert min(b["l"], b["t"], b["r"], b["b"]) >= -1, f"target outside box: {b}"
        for i in range(4):
            for j in range(i + 1, 4):
                d = ((boxes[i]["cx"] - boxes[j]["cx"]) ** 2 + (boxes[i]["cy"] - boxes[j]["cy"]) ** 2) ** .5
                assert d >= 64, f"targets overlap ({d:.0f}px apart)"
    setup(page)
    for _ in range(25):
        check_layout(); tap(page, "#calib-arena")
        check_layout(); tap(page, "#calib-arena")
        page.click("#end-night")
        page.click("#setup button[type=submit]")


# ---------- campus mode ----------
def campus_on(page, school=None):
    page.check("#f-campus-on")
    if school:
        page.select_option("#f-campus-pick", school)

@test("Campus fields stay hidden until the student turns campus mode on")
def _(page):
    expect(page.locator("#campus-fields")).to_be_hidden()
    page.check("#f-campus-on")
    expect(page.locator("#campus-fields")).to_be_visible()

@test("Picking a listed school fills in its numbers and says where they came from")
def _(page):
    campus_on(page, "mit")
    assert page.input_value("#f-police-phone") == "617-253-1212"
    assert page.input_value("#f-ride-phone") == "617-253-2997"
    assert page.input_value("#f-support-phone") == "617-253-2300"
    expect(page.locator("#campus-source")).to_contain_text("checked 2026-11-02")

@test("Campus mode without a police number can't be saved")
def _(page):
    campus_on(page)
    setup(page)
    visible(page, "#s-setup")
    expect(page.locator("#setup-err")).to_contain_text("campus police emergency number")

@test("A mistyped safe-ride number is caught; an empty one is fine")
def _(page):
    campus_on(page)
    page.fill("#f-police-phone", "(555) 555-0100")
    page.fill("#f-ride-phone", "12345")
    setup(page)
    expect(page.locator("#setup-err")).to_contain_text("safe ride")
    page.fill("#f-ride-phone", "")
    page.click("#setup button[type=submit]")
    visible(page, "#s-calib")

@test("Any US number format works: (555) 555-0100, 555.555.0100, +1 555 555 0100")
def _(page):
    for raw in ["(555) 555-0100", "555.555.0100", "+1 555 555 0100", "1-555-555-0100"]:
        page.goto(URL)
        campus_on(page)
        page.fill("#f-police-phone", raw)
        setup(page); calibrate(page)
        page.click("#check-now"); page.click("#need-help")
        expect(page.locator("#alert-msg")).to_contain_text("555-555-0100")
        page.evaluate("localStorage.clear()")

@test("Alert text includes campus police and where the student lives")
def _(page):
    campus_on(page, "harvard")
    page.fill("#f-home", "Quincy House, entry B")
    armed(page)
    page.click("#check-now"); page.click("#need-help")
    msg = page.locator("#alert-msg")
    expect(msg).to_contain_text("Campus police (Harvard University Police): 617-495-1212")
    expect(msg).to_contain_text("Jess lives at: Quincy House, entry B")
    expect(msg).to_contain_text("call 911")

@test("Without campus mode, the alert has no campus lines")
def _(page):
    armed(page)
    page.click("#check-now"); page.click("#need-help")
    expect(page.locator("#alert-msg")).not_to_contain_text("Campus police")

@test("Shaky pass (slower than usual) offers the campus safe ride")
def _(page):
    campus_on(page, "yale")
    armed(page)
    page.click("#check-now")
    tap(page, "#check-arena", gaps_ms=(800, 800, 800))   # 2.4 s vs 0.9 s normal: passes, but slow
    visible(page, "#s-ride")
    expect(page.locator("#ride-phone")).to_have_text("203-785-5555")
    page.click("#ride-ok")
    visible(page, "#s-armed")
    expect(page.locator("#log")).to_contain_text("slower than usual")

@test("Shaky pass with a wrong tap also offers the ride")
def _(page):
    campus_on(page, "mit")
    armed(page)
    page.click("#check-now")
    tap(page, "#check-arena", wrong=1)
    visible(page, "#s-ride")

@test("A normal-speed pass goes straight back to night mode, no ride offer")
def _(page):
    campus_on(page, "mit")
    armed(page)
    page.click("#check-now")
    tap(page, "#check-arena")
    visible(page, "#s-armed")

@test("Shaky pass without a ride number set goes back to night mode")
def _(page):
    campus_on(page)
    page.fill("#f-police-phone", "555-555-0100")
    armed(page)
    page.click("#check-now")
    tap(page, "#check-arena", gaps_ms=(800, 800, 800))
    visible(page, "#s-armed")

@test("No check-ins fire while the ride offer is on screen")
def _(page):
    campus_on(page, "yale")
    armed(page)
    page.click("#check-now")
    tap(page, "#check-arena", gaps_ms=(800, 800, 800))
    page.clock.run_for(5 * 60_000)
    visible(page, "#s-ride")

@test("Next-morning screen shows the campus confidential support line")
def _(page):
    campus_on(page, "harvard")
    armed(page)
    page.click("#check-now"); page.click("#need-help"); page.click("#all-clear")
    expect(page.locator("#support-box")).to_be_visible()
    expect(page.locator("#support-phone")).to_have_text("617-495-9100")

@test("Campus settings are remembered after reload")
def _(page):
    campus_on(page, "yale")
    page.fill("#f-home", "Saybrook")
    setup(page)
    page.reload()
    expect(page.locator("#campus-fields")).to_be_visible()
    assert page.input_value("#f-police-phone") == "203-432-4400"
    assert page.input_value("#f-home") == "Saybrook"

@test("Campus text fields are shown as plain text, not run")
def _(page):
    campus_on(page)
    page.fill("#f-police-name", '<img src=x onerror="window.pwned=1">')
    page.fill("#f-police-phone", "555-555-0100")
    page.fill("#f-home", "<b>x</b>")
    armed(page)
    page.click("#check-now"); page.click("#need-help")
    page.clock.run_for(500)
    assert page.evaluate("window.pwned") is None
    expect(page.locator("#alert-msg")).to_contain_text("<b>x</b>")

@test("Campus screens fit a 320px-wide phone")
def _(page):
    page.set_viewport_size({"width": 320, "height": 640})
    campus_on(page, "harvard")
    def no_overflow(label):
        w = page.evaluate("[document.documentElement.scrollWidth, window.innerWidth]")
        assert w[0] <= w[1], f"{label}: page is {w[0]}px wide in a {w[1]}px screen"
    no_overflow("campus setup")
    armed(page)
    page.click("#check-now")
    tap(page, "#check-arena", gaps_ms=(800, 800, 800))
    no_overflow("ride offer")
    page.click("#ride-ok"); page.click("#check-now"); page.click("#need-help"); page.click("#all-clear")
    no_overflow("next morning")

def screenshots(browser):
    for scheme in ("dark", "light"):
        page = fresh(None, browser, width=390, scheme=scheme)
        page.screenshot(path=str(SHOTS / f"1-setup-{scheme}.png"), full_page=True)
        armed(page)
        page.screenshot(path=str(SHOTS / f"2-night-mode-{scheme}.png"), full_page=True)
        page.click("#check-now")
        page.screenshot(path=str(SHOTS / f"3-check-in-{scheme}.png"), full_page=True)
        page.click("#need-help")
        page.screenshot(path=str(SHOTS / f"4-alert-{scheme}.png"), full_page=True)
        page.context.close()
        page = fresh(None, browser, width=390, scheme=scheme)
        page.check("#f-campus-on"); page.select_option("#f-campus-pick", "mit")
        page.screenshot(path=str(SHOTS / f"5-campus-setup-{scheme}.png"), full_page=True)
        armed(page); page.click("#check-now")
        tap(page, "#check-arena", gaps_ms=(800, 800, 800))
        page.screenshot(path=str(SHOTS / f"6-safe-ride-{scheme}.png"), full_page=True)
        page.click("#ride-ok"); page.click("#check-now"); page.click("#need-help")
        page.screenshot(path=str(SHOTS / f"7-campus-alert-{scheme}.png"), full_page=True)
        page.click("#all-clear")
        page.screenshot(path=str(SHOTS / f"8-next-morning-{scheme}.png"), full_page=True)
        page.context.close()

with sync_playwright() as p:
    browser = p.chromium.launch(executable_path="/usr/bin/google-chrome", args=["--no-sandbox"])
    failed = 0
    for scheme in ("dark", "light"):
        for name, fn in results:
            page = fresh(p, browser, scheme=scheme)
            try:
                fn(page)
                errs = [e for e in page.errors if "net::" not in e and "Failed to load resource" not in e]
                assert not errs, f"script errors: {errs}"
                print(f"PASS  [{scheme}] {name}")
            except Exception as e:
                failed += 1
                print(f"FAIL  [{scheme}] {name}\n      {str(e).splitlines()[0][:300]}")
            page.context.close()
    screenshots(browser)
    browser.close()
    total = len(results) * 2
    print(f"\n{total - failed}/{total} browser tests passed.")
    sys.exit(1 if failed else 0)
