# Text-message relay

iPhone apps can't send text messages on their own, so alerts go through this small service, which texts each contact through Twilio.

## Run it

Needs Node 18 or newer and a Twilio account with a phone number.

```sh
RELAY_TOKEN=pick-a-long-random-string \
TWILIO_SID=AC... TWILIO_TOKEN=... TWILIO_FROM=+1... \
node server.js
```

It listens on port 8787 (or `PORT`) at `POST /alert`. Put the URL and the same token in the iPhone app's settings. Host it anywhere that runs Node over HTTPS.

## What it does

- Rejects any request without the right token.
- Builds the message itself from fixed wording, so the token can't be used to send arbitrary text.
- Texts up to 5 contacts; numbers must include the country code (+1...).
- Sends each alert once, even when it arrives from both the watch and the phone.
- If every text fails, returns an error so the app retries.

Tests: `node test.js`.
