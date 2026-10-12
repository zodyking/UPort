# UPort developer branding integration

For Android TV, include launcher resources in your source project, not only the Play Console upload:
- `android:icon="@mipmap/ic_launcher"`
- `android:banner="@drawable/tv_banner"` on the application and/or Leanback launcher Activity as appropriate.
- The 320x180 PNG is the xhdpi launcher banner; the 1280x720 file is a separate Google Play TV listing banner.
- Adaptive icon foreground/background PNGs are design starting points. Use Android Studio's Image Asset Studio to define layer scale, safe area, and mask behavior.

Suggested project mapping:
- `02_Google_Play_Android_TV/android_tv_banner_xhdpi_320x180.png` -> `app/src/main/res/drawable-xhdpi/tv_banner.png`
- `02_Google_Play_Android_TV/android_tv_launcher_icon_xhdpi_160x160.png` -> icon resource according to project setup

For Amazon Fire TV, upload 1280x720 app icon and 1920x1080 background as separate console fields. Keep main banner text inside central 882x448 region.
For Apple tvOS, the PNG files are flat visual references only. Layered app icon stacks and Xcode asset catalog are needed before tvOS submission.

Platform screenshot templates are guides, NOT authentic store screenshots and must be replaced with actual app captures.
