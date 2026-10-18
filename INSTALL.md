# Installing MyBots

You need two things: a running [Hermes Agent](https://github.com/NousResearch/hermes-agent)
gateway on your network, and an Android 8.0+ device on that same network.
The phone talks to nothing else.

**Fastest to done:** if you want your own agent to set this up for you, hand
it [docs/INSTALL-agent.md](docs/INSTALL-agent.md) and come back at step 2.

## 1. Install the gadget plugin on your gateway

MyBots' hub side is our own plugin, and it ships inside this repo at
`plugin/gadget/`. (The gadget plugin bundled with hermes-agent works for
pairing and audio, but it lacks the fixes that make the experience good:
long answers finish without being cut off, and the head narrates while the
agent works. Those fixes live in ours.)

Copy it where your gateway finds plugins - for one profile:

```
cp -r plugin/gadget ~/.hermes/profiles/<profile>/plugins/
```

...or globally in `~/.hermes/plugins/` if one install drives everything.
Then enable it for the profile the head should drive (use `-p <profile>`
throughout if you have more than one):

```yaml
platforms:
  gadget:
    enabled: true
```

Restart the gateway. If several profiles should reach the head, each needs
its own port: `extra.port: 8766` for the second one (the first keeps the
default `8765`).

## 2. Install the app on the device

**From the release APK (recommended):** download the APK from the
[Releases page](../../releases), copy it to the device, and install it.
Android will ask you to allow installing from unknown sources, since the app
is distributed directly.

If you have USB debugging and `adb`:

```
adb install mybots-v0.x-debug.apk
```

**From source:** see [README](README.md#building-from-source).

## 3. Pair

1. Open MyBots. It asks for the hub URL. That is your gateway, WebSocket style:
   `ws://<your-gateway>:8765/gadget` (add `s` for TLS, use the right port per
   profile).
2. A pairing code appears on screen (eight characters, uppercase letters and digits).
3. Approve it on the gateway:
   ```
   hermes -p <profile> pairing approve gadget K7M2XQ9T
   ```
4. The head should animate in and say hello. Approving is one-time; the device
   stores its key and reconnects by itself after that.

## 4. Try things

- Say **hey jarvis** and ask something.
- Or touch-and-hold the head to talk without wake word.
- Swipe up on the strip to change profile; the head morphs while switching.
- Tap the head to poke it. It does not know why you did that.
- The gear icon opens settings: wake word, phone name, per-profile pages.

## Privacy

The app opens exactly one network connection, to the hub URL you typed.
Microphone audio travels over it and nowhere else. No analytics, no ad
SDKs, no third parties. Everything smart happens on your own machine.

## Troubleshooting

- **Stuck on connecting:** check the gateway is up, the plugin is enabled,
  and nothing blocks the port (firewall, VLAN). Same-subnet is the easy path.
- **Pairs but stays silent:** the gateway side does speech; check the profile
  has TTS configured.
- **Wake word never catches:** enable it in settings; the model runs on-device
  and needs one clear "hey jarvis" to have registered your voice in practice.
- **Wrong name shows on your hub:** set the phone name on the settings card,
  and a distinct name per profile page, so multi-device fleets read sanely.
