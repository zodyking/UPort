# Contributing to UPort

UPort is an Android TV app. Changes should stay usable from across the room with a D-pad, on Fire TV and Google TV as well as Android TV.

## Issues

Use the forms. They are the fastest way to get a report that can be reproduced.

- [Bug report](https://github.com/zodyking/UPort/issues/new?template=bug_report.yml)
- [Feature request](https://github.com/zodyking/UPort/issues/new?template=feature_request.yml)

Do not paste API keys, Tailscale auth keys, console passwords, or site-package files into an issue.

## Pull requests

1. Fork the repository and create a branch from `main`.
2. Keep the change focused. A bug fix and a redesign are two pull requests.
3. Match the Kotlin style already in `app/src/main`.
4. Run `./gradlew :app:testDebugUnitTest` when you touch logic that has tests.
5. Fill in the pull request template, including the TV or emulator you used.

Do not commit `local.properties`, `keystore.properties`, `*.jks`, or a keystore. Release signing stays on the machine that builds the sideload APK.

## Brand

Use the masters in `UPort_Brand_Kit/`. Signal blue is `#0860FA`. The wordmark is the traced SVG, not a redraw in Inter or another font. Screenshot templates under `UPort_Brand_Kit/07_Screenshot_Guides/` are guides, not store captures.

## Security

Report vulnerabilities through [SECURITY.md](SECURITY.md).
