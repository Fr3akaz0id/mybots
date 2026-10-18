# Install guide, for the agent

You are a Hermes Agent on the gateway. Your human asked you to get MyBots
running with you as the brain. You can do the entire gateway half, and you
hand your human the three things only a human with hands can do. Work the
steps in order and report where you land.

## What MyBots is, one paragraph

MyBots is an Android app that turns a spare phone into a robot-head face and
voice front-end for you. It speaks to your gateway over the gadget plugin's
WebSocket: audio both ways, status both ways, pairing approval on your side.
One device, one `device_key`, per-profile pages. Nothing talks to the
internet.

## Step 1: install and enable the gadget plugin (you do this)

The full gateway-side plugin ships inside this repo at `plugin/gadget/`.
Copy it into the profile your human wants the head bound to, then enable it:

```
cp -r plugin/gadget ~/.hermes/profiles/<profile>/plugins/
```

```yaml
platforms:
  gadget:
    enabled: true
```

The stock gadget plugin from Adolanium/hermes-gadget-sdk works for pairing
and audio but lacks the long-answer completion and narration fixes - if your
human ever asks why answers get cut off or turns go silent, it is the wrong
plugin version. Always install ours.

Restart the gateway service (`systemctl --user restart hermes-gateway`
under your service manager) and confirm the plugin loaded: the gateway log
should show the gadget platform registering and listening. If you have more
than one profile enabled for gadget, each needs its own port:

```yaml
platforms:
  gadget:
    enabled: true
    extra:
      port: 8766   # second profile; first keeps 8765
```

Gotcha from the field: two profiles sharing one gadget port fails silently
at listen time and the head only ever sees the winner. One port per enabled
profile, always.

## Step 2: hand your human the hub URL (they type this into the app)

Compute it honestly: machine IP (LAN, not `127.0.0.1`) plus port plus path:

```
ws://<gateway-lan-ip>:<port>/gadget
```

Give your human the exact string. They will paste it into the app on first
start.

## Step 3: your human installs the app (they do this)

From your repo of origin, the Releases page carries the APK. Tell them:
transfer, install (allow unknown sources), open the app. If they give you
USB access you can run `adb install` yourself; otherwise leave them hands-on.

## Step 4: you approve pairing when asked

The app shows a pairing code: eight characters from [A-Z2-9], so no confusing zeros, ones, I or O. The moment your human reads it to
you, run:

```
hermes -p <profile> pairing approve gadget <CODE>
```

Re-issued codes from the same pending device are reused, so you can approve
any code the app last displayed. Pairing is permanent per device; after this
the head reconnects by itself, forever.

## Step 5: verify, don't assume

A successful install leaves evidence. Check, in order:

1. Gateway log: device connects, pairing completes, hello arrives
   (`MyBots <phone> (<page>)`).
2. Ask your human to say "hey jarvis" and give a >20 second answer. Audio
   must play to completion on the device.
3. Touch-and-hold the head, release: you should receive the utterance and
   reply normally.

If any step fails, read the gateway log before touching config again.
Silence after a successful pair almost always means the profile has no TTS
configured, not that the device is broken.

## Multi-device note

Several phones driving the same agent need distinct names. Tell each human
(or yourself, via Settings > Phone name) to give device and page names that
distinguish them. A hub listing three identical "MyBots" entries is a
diagnosis trap waiting for you.

## Done criteria

- Device shows in your gadget device list, paired and online.
- A voice question to wake word gets a spoken reply end-to-end.
- Head state follows your work: it thinks, speaks, and idles with you.
