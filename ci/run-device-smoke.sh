#!/usr/bin/env bash
set -euo pipefail
mkdir -p device-reports
mapfile -t apps < <(find device-apks/app -type f -name '*.apk')
mapfile -t tests < <(find device-apks/tests -type f -name '*.apk')
[[ ${#apps[@]} -eq 1 && ${#tests[@]} -eq 1 ]]
adb install -r "${apps[0]}"
adb install -r "${tests[0]}"
adb shell pm grant io.github.lolososka.zapretmobile android.permission.POST_NOTIFICATIONS
adb logcat -c
trap 'adb logcat -d > device-reports/logcat.txt; adb pull /sdcard/Android/data/io.github.lolososka.zapretmobile/files/smoke device-reports/screenshots || true' EXIT
timeout 180 adb shell am instrument -w io.github.lolososka.zapretmobile.test/androidx.test.runner.AndroidJUnitRunner | tee device-reports/instrumentation.txt
grep -Eq '^OK \([0-9]+ tests?\)' device-reports/instrumentation.txt
! grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' device-reports/instrumentation.txt
