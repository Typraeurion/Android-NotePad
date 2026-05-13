# Preparing for the Next Release

- [ ] Run all tests
    - Open a new sheet in `doc/Test_Results.ods`.  Copy or create a matrix of test classes vs. platforms.
    - Run all tests under `app/src/test/` stand-alone.  Enter the results in the spreadsheet in the “N/A” platform column (“Passed” or “'passed/total” for any failures)
	- For each available emulator in Android Studio's Device Manager for API’s ≥ `minSdk`,
	    1. Start the emulator; wait for it to be available in the “Running Devices” drop-down (select it if necessary).
		- Run “All Data Tests” (all tests in package …`.notes.data`).  Enter the results in the spreadsheet in the column for this platform.
		- Run …`.notes.provider.NoteRepositoryTests`.  Enter the results.
		- Run …`.notes.util.FileUtilsTests`.  Enter the results.
		- Run “All Service Tests” (all tests in package …`.notes.service`).  Enter the results.
		- Run “All UI Tests” (all tests in package …`.notes.ui`).  Enter the results.
		7. Stop the emulator.
	- Address any failing tests as you go.  All stand-alone tests under `app/src/test/` **must** pass.  All instrumented tests under `app/src/androidTest` _should_ pass; any exceptions must be for good reason and documented.
- [ ] Check the project for lint errors
    1. `./gradlew lint`
	2. Check the results in `app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt` for any “Error”s.
	3. Fix the errors as needed.
- [ ] Increment the application version to the next applicable number (minor for added features, patch for bug fixes).
    - `app/src/main/res/values/strings.xml`: `InfoPopupText` line 2 (version string and build date)
	- `app/build.gradle`: `android.defaultConfig`: `versionCode` and `versionName`
	- `app/src/main/AndroidManifest.xml`: `android:versionCode` and `android:versionName` in top-level `<manifest>` tag
    - `doc/NotePad.texinfo`: “Known Bugs” first paragraph.  Also be sure any bugs that were fixed are moved into a new list.
- [ ] When applicable, increment the target SDK level / Android version.
    - `app/build.gradle`: `android.defaultConfig.targetSdk`
	- `app/src/main/AndroidManifest.xml`: `<uses-sdk android:targetSdkVersion`
	- `doc/NotePad.texinfo`: “Device Compatibility” first paragraph (“designed for compatibility with …”)
	- `doc/Building_NotePad.texinfo`: “Testing” → “Using Virtual Devices”: move the new target version (and any others after the old target) from the list “suggested to ensure compatibility with new devices” to the preceding list “_highly recommended_ to test against the following API’s”, and move “which is the current target version” to the new target version.
- [ ] When applicable, increment the minimum SDK level / Android version.
    - `app/build.gradle`: `android.defaultConfig.minSdk`
	- `app/src/main/AndroidManifest.xml`: `<uses-sdk android:minSdkVersion`
	- `doc/NotePad.texinfo`: “Device Compatibility” first paragraph (“also runs on …”)
	- `doc/Building_NotePad.texinfo`: “Testing” → “Using Virtual Devices”: remove all versions earlier than the new minimum from the list “_highly recommended_ to test against the following API’s”
	- `README.md`: last bullet item on the feature list (“Backwards compatible with …”)
    - `fastlane/metadata/android/en-US/full_description.txt`: last bullet item on the feature list (“Backwards compatible with …”)
- [ ] Rebuild the documentation
    1. `cd doc`
	2. `make`
	3. `cd ..`
- [ ] Write up a change log entry for all outstanding changes
- [ ] Write up a brief user-facing change log for F-Droid
    - `fastlane/metadata/android/en-US/changelogs/`_versionCode_`.txt`
- [ ] `git add` all modified files and `git commit`
- [ ] Build and sign the APK
    1. `./gradlew assembleRelease`
    2. Check `build.gradle`s `android.buildToolsVersion` for the tools version; set `BUILD_TOOLS_VERSION_DIRECTORY` to `~/src/Android_SDKs/build-tools/` plus the tools version.
	3. Set `ANDROID_KEYSTORE` to the keystory location (`../.private/*.keystore`)
	4. Set `SIGNING_KEY_ALIAS` to the name of your signing key (e.g. “MyKey”)
	5.
	```
	${BUILD_TOOLS_VERSION_DIRECTORY}/apksigner sign \
      --alignment-preserved \
      --ks ${ANDROID_KEYSTORE} --ks-key-alias ${SIGNING_KEY_ALIAS} \
      --out app/NotePad-${VERSION}.apk \
      app/build/outputs/apk/release/app-release-unsigned.apk
    ```
- [ ] Tag the commit with the version name
    - `git tag v`_versionName_
- [ ] Push the commit tag up to GitHub
    - `git push github master v`_versionName_
- [ ] Create the new release
    1. Open the [Releases](https://github.com/Typraeurion/Android-NotePad/releases) page in GitHub.  Open [Draft a new release](https://github.com/Typraeurion/Android-NotePad/releases/new) in a new window; use the previous release as a template to fill in the next one.
    - Select the tag for this release.
	- Title the release (e.g. “_Android Version_ Update”, “_Bug_ Patch”).
	- Enter release notes; generally the same items listed in the F-Droid change log.
	- Upload the APK from `app/NotePad-`_versionName_`.apk`
	- Upload the user documentation `doc/NotePad.pdf`
	7. “Publish release”
- [ ] Within the next couple of days, check the [F-Droid build monitor](https://monitor.f-droid.org/builds/build) to see whether the build was successful or failed.
