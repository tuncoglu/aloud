# Aloud privacy policy

_Last updated: 2026-10-27_

Aloud is a free, open-source audiobook player for Wear OS. It does not collect,
transmit, or share any personal data.

## What Aloud stores

Everything stays on your watch, in the app's private storage:

- the audiobook files you upload,
- your playback position, playback speed, and sleep-timer setting for each book.

Deleting a book in the app, or uninstalling the app, removes this data. Aloud
disables Android backup, so none of it is copied to Google or to another device.

## What Aloud does not do

- No account, sign-in, or cloud sync.
- No analytics, crash reporting, advertising, or tracking.
- No third-party SDKs that collect data.
- No contact with any server operated by the developer or anyone else.

## Network use

Aloud has no internet features. When you press **Start** on the Uploader screen,
the watch asks to join a saved Wi-Fi network and runs a small web server on your
local network, so you can copy books from a computer or phone. The server:

- is protected by a random six-digit PIN shown only on the watch,
- is reachable only from your local network,
- switches itself off after two minutes without activity, or when you press Stop.

The uploader uses plain HTTP, so the PIN and the files you send are not
encrypted in transit. Use it only on a network you trust.

## Permissions

| Permission | Why |
| --- | --- |
| Internet, local network, network state, change network state | Run the local uploader and ask the watch to join Wi-Fi |
| Notifications, foreground service | Show playback controls and the uploader's running status |
| Wake lock | Keep playing with the screen off |

## Contact

Questions or concerns: open an issue at https://github.com/tuncoglu/aloud/issues.
