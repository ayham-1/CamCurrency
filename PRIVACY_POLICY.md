## firmus: Privacy policy

Welcome to the firmus home launcher app for Android!

This is an open source Android app developed by [ayham](https://ayham.xyz). The source
code is available on Codeberg under the MIT license; the app is also available
on Google Play.

The application uses Ad services from GoogleAdmob, the respective privacy policy
applies here.

The application uses a Google's library for analyzing camera feed. ALL is done
locally. I do not collect any information.

Other than the previously mentioned exceptions, I do NOT collect ANY
information. No personal or anonymous data is collected by me directly and I do
not have any method of doing so through the code.

### Explanation of permissions requested in the app

The list of permissions required by the app can be found in the `AndroidManifest.xml` file:

```xml
<uses-feature android:name="android.hardware.camera.any" />
<uses-permission android:name="android.permission.CAMERA" />

<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

* `android.hardware.camera.any` -  to access camera.
* `android.permission.CAMERA` -  to access camera.
* `android.permission.INTERNET` - to access current exchange rates over the
  internet.
* `android.permission.ACCESS_NETWORK_STATE` - to access current exchange rates.

If you find any security vulnerability that has been inadvertently caused by me,
or have any question regarding how the app protects your privacy, send me an
email or post a discussion on Codeberg.

Yours sincerely,
ayham,
me@ayham.xyz
