# Security

UPort stores console credentials in the Android Keystore on the TV. Do not open a public issue for a leaked key, a bypass of that store, or a problem that would let someone reach a site they should not reach.

## Report

Email [zodykinginbox@gmail.com](mailto:zodykinginbox@gmail.com) with:

- UPort version and TV model
- What an attacker can do, and what they already need
- Whether the issue is in the app, the bundled VPN library, or the way a permission is granted

Please give a short window to confirm before any public write-up. There is no bug-bounty program.

## Out of scope

- A UniFi console vulnerability with no UPort involvement
- Missing optional permissions after a sideload (see the README ADB section)
- Debug builds run only on your own emulator
