# Phone apps

Android apps for Alejo's phone and his Boox Go7 e-reader, one folder each. Follow
the `AGENTS.md` inside each app.

- `morning-reader/`: a finite morning reading session that replaces feeds.

## Android

Connect with `~/best/stash/dotfiles/bin/adb-phone`. It prints the serial to pass to
`adb -s`, or runs `adb` with any arguments you give it. If it can't find the phone,
ask Alejo to turn on Wireless debugging. The phone stays awake while charging, so
plug it in for tests that run in the background. Set `PHONE_SERIAL=ec27fd9` to
reach the Boox instead; if it isn't found, ask Alejo to plug it into the Mac by USB.

Build with `JAVA_HOME` set to
`/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home` and `ANDROID_HOME`
to `~/Library/Android/sdk`.
