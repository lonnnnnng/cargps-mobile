#!/bin/zsh

# 作者：long｜M6 同进程回收验证只允许操作两台已核对模拟器，拒绝 adb 默认设备和任何真机 serial。
set -euo pipefail

PROJECT_DIR="${0:A:h}/.."
SERIAL="${1:-emulator-5554}"
APP_PACKAGE="com.cargps.mobile"
MAIN_COMPONENT="${APP_PACKAGE}/.MainActivity"
SERVICE_COMPONENT="${APP_PACKAGE}/.TripRecordingService"
START_TRIP_ACTION="${APP_PACKAGE}.action.START_TRIP"
END_TRIP_ACTION="${APP_PACKAGE}.action.END_TRIP"
UI_DUMP_PATH="/sdcard/cargps-m6-process-rebind.xml"
POLL_SECONDS=60

case "$SERIAL" in
  emulator-5554)
    EXPECTED_AVD="Pixel_9"
    EXPECTED_SDK="35"
    ;;
  emulator-5556)
    EXPECTED_AVD="CASKA_1024x600"
    EXPECTED_SDK="27"
    ;;
  *)
    print -u2 "拒绝执行：serial 只能是 emulator-5554 或 emulator-5556，收到 $SERIAL"
    exit 2
    ;;
esac

adb_target() {
  adb -s "$SERIAL" "$@"
}

read_app_pid() {
  adb_target shell pidof "$APP_PACKAGE" 2>/dev/null | tr -d '\r' | awk '{print $1}'
}

read_app_pid_count() {
  adb_target shell pidof "$APP_PACKAGE" 2>/dev/null | tr -d '\r' | awk '{print NF}'
}

read_location_thread_count() {
  local pid="$1"
  local thread_names
  thread_names="$(
    adb_target shell \
      "for task in /proc/$pid/task/*; do cat \"\$task/comm\" 2>/dev/null; done" \
      2>/dev/null | tr -d '\r' || true
  )"
  print -r -- "$thread_names" | grep -c '^cargps-location$' || true
}

read_ui_dump() {
  adb_target shell uiautomator dump "$UI_DUMP_PATH" >/dev/null 2>&1 || return 1
  adb_target exec-out cat "$UI_DUMP_PATH" 2>/dev/null | tr -d '\r'
}

read_focused_window_dump() {
  adb_target shell dumpsys window 2>/dev/null | tr -d '\r' |
    grep -E 'mCurrentFocus=|mFocusedApp=|topResumedActivity=' || true
}

read_service_dump() {
  adb_target shell dumpsys activity services "$SERVICE_COMPONENT" 2>/dev/null | tr -d '\r'
}

is_main_activity_focused() {
  local focused_window_dump="$1"
  print -r -- "$focused_window_dump" |
    grep -Eq '(mCurrentFocus|mFocusedApp|topResumedActivity)=.*com\.cargps\.mobile.*MainActivity'
}

actual_avd="$(adb_target shell getprop ro.boot.qemu.avd_name | tr -d '\r')"
if [[ -z "$actual_avd" ]]; then
  actual_avd="$(adb_target shell getprop ro.kernel.qemu.avd_name | tr -d '\r')"
fi
actual_sdk="$(adb_target shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ "$actual_avd" != "$EXPECTED_AVD" || "$actual_sdk" != "$EXPECTED_SDK" ]]; then
  print -u2 "拒绝执行：$SERIAL 映射为 AVD=$actual_avd SDK=$actual_sdk，预期 AVD=$EXPECTED_AVD SDK=$EXPECTED_SDK"
  exit 2
fi

print "[M6] 设备已锁定：serial=$SERIAL avd=$actual_avd sdk=$actual_sdk"

cleanup_probe() {
  if [[ "${KEEP_VALIDATION_STATE:-0}" != "1" ]]; then
    # 作者：long｜退出时的 force-stop 只清理 CarGPS 测试现场；恢复断言全部在它之前完成，不能把清理记录混入被测路径。
    adb_target shell am force-stop "$APP_PACKAGE" >/dev/null 2>&1 || true
  fi
  adb_target shell rm -f "$UI_DUMP_PATH" >/dev/null 2>&1 || true
}
trap cleanup_probe EXIT INT TERM

print "[M6] 构建并安装独立 probe APK"
(cd "$PROJECT_DIR" && ./gradlew :mobile-app:assembleProbe --console=plain)
if ! adb_target install -r "$PROJECT_DIR/mobile-app/build/outputs/apk/probe/mobile-app-probe.apk" >/dev/null; then
  adb_target shell pm uninstall "$APP_PACKAGE" >/dev/null 2>&1 || true
  adb_target install "$PROJECT_DIR/mobile-app/build/outputs/apk/probe/mobile-app-probe.apk" >/dev/null
fi

# 作者：long｜仅清理目标模拟器内的 CarGPS 测试数据，确保恢复来源是本轮真实 Room 活动行程。
adb_target shell pm clear "$APP_PACKAGE" >/dev/null
adb_target shell pm grant "$APP_PACKAGE" android.permission.ACCESS_FINE_LOCATION
adb_target shell pm grant "$APP_PACKAGE" android.permission.ACCESS_COARSE_LOCATION
if (( actual_sdk >= 33 )); then
  adb_target shell pm grant "$APP_PACKAGE" android.permission.POST_NOTIFICATIONS
fi

location_mode="$(adb_target shell settings get secure location_mode 2>/dev/null | tr -d '\r')"
if [[ "$location_mode" == "0" ]]; then
  print -u2 "拒绝执行：$SERIAL 的系统定位已关闭，请先在该模拟器中开启定位"
  exit 2
fi

adb_target logcat -c
adb_target shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb_target shell wm dismiss-keyguard >/dev/null 2>&1 || true
adb_target shell am start -W -n "$MAIN_COMPONENT" >/dev/null

activity_ready=0
deadline=$((SECONDS + POLL_SECONDS))
while (( SECONDS < deadline )); do
  focused_window_dump="$(read_focused_window_dump)"
  if is_main_activity_focused "$focused_window_dump"; then
    activity_ready=1
    break
  fi
  sleep 0.5
done
if (( activity_ready == 0 )); then
  print -u2 "失败：${POLL_SECONDS} 秒内 MainActivity 未进入前台"
  print -u2 "$focused_window_dump"
  exit 1
fi

print "[M6] Activity 已在前台；通过 probe-only 临时导出的真实 Service 开始行程"
adb_target shell am start-foreground-service \
  -n "$SERVICE_COMPONENT" \
  -a "$START_TRIP_ACTION" >/dev/null

old_pid=""
before_thread_count="0"
before_ready=0
deadline=$((SECONDS + POLL_SECONDS))
while (( SECONDS < deadline )); do
  candidate_pid="$(read_app_pid || true)"
  service_dump="$(read_service_dump || true)"
  ui_dump="$(read_ui_dump || true)"
  if [[ -n "$candidate_pid" ]]; then
    before_thread_count="$(read_location_thread_count "$candidate_pid")"
  fi
  if [[ -n "$candidate_pid" && "$service_dump" =~ 'isForeground=true|foreground=true|foregroundId=[1-9]' &&
    "$ui_dump" == *"记录中"* && "$before_thread_count" == "1" ]]; then
    old_pid="$candidate_pid"
    before_ready=1
    break
  fi
  sleep 0.5
done
if (( before_ready == 0 )); then
  print -u2 "失败：${POLL_SECONDS} 秒内没有建立前台 Activity、活动行程、前台 Service 和单一定位线程"
  print -u2 "pid=${old_pid:-NONE} locationThreads=$before_thread_count"
  print -u2 "$service_dump"
  exit 1
fi

print "[M6] 恢复前边界成立：oldPid=$old_pid；Activity 保持前台并由应用 UID 发送 SIGKILL"
set +e
adb_target shell run-as "$APP_PACKAGE" kill -9 "$old_pid" >/dev/null 2>&1
kill_command_status=$?
set -e

new_pid=""
deadline=$((SECONDS + POLL_SECONDS))
while (( SECONDS < deadline )); do
  candidate_pid="$(read_app_pid || true)"
  if [[ -n "$candidate_pid" && "$candidate_pid" != "$old_pid" ]]; then
    new_pid="$candidate_pid"
    break
  fi
  sleep 0.5
done
if [[ -z "$new_pid" ]]; then
  print -u2 "失败：SIGKILL 后 ${POLL_SECONDS} 秒内没有新进程，kill 返回码=$kill_command_status"
  exit 1
fi

service_recovery_ready=0
after_thread_count="0"
process_count="0"
service_record_count="0"
deadline=$((SECONDS + POLL_SECONDS))
while (( SECONDS < deadline )); do
  service_dump="$(read_service_dump || true)"
  notification_dump="$(adb_target shell dumpsys notification --noredact 2>/dev/null | tr -d '\r' || true)"
  after_thread_count="$(read_location_thread_count "$new_pid")"
  process_count="$(read_app_pid_count || true)"
  service_record_count="$(
    print -r -- "$service_dump" |
      grep -c 'ServiceRecord{.*com.cargps.mobile/.TripRecordingService' || true
  )"
  if [[ "$service_dump" =~ 'isForeground=true|foreground=true|foregroundId=[1-9]' ]] &&
    [[ "$service_dump" == *"restartCount=1"* ]] &&
    [[ "$notification_dump" == *"CarGPS"* ]] &&
    [[ "$after_thread_count" == "1" && "$process_count" == "1" && "$service_record_count" == "1" ]]; then
    service_recovery_ready=1
    break
  fi
  sleep 0.5
done
if (( service_recovery_ready == 0 )); then
  print -u2 "失败：新进程没有独立恢复 START_STICKY Service、通知、单一 ServiceRecord 和单定位线程"
  print -u2 "oldPid=$old_pid newPid=$new_pid processCount=$process_count serviceRecords=$service_record_count locationThreads=$after_thread_count"
  print -u2 -- "--- Service ---"
  print -u2 "$service_dump"
  exit 1
fi

print "[M6] Service 已在无 Activity 时独立恢复：newPid=$new_pid；模拟用户重新打开应用并重绑"
adb_target shell am start -W -n "$MAIN_COMPONENT" >/dev/null

rebind_ready=0
deadline=$((SECONDS + POLL_SECONDS))
while (( SECONDS < deadline )); do
  current_pid="$(read_app_pid || true)"
  focused_window_dump="$(read_focused_window_dump)"
  service_dump="$(read_service_dump || true)"
  ui_dump="$(read_ui_dump || true)"
  after_thread_count="$(read_location_thread_count "$new_pid")"
  process_count="$(read_app_pid_count || true)"
  service_record_count="$(
    print -r -- "$service_dump" |
      grep -c 'ServiceRecord{.*com.cargps.mobile/.TripRecordingService' || true
  )"
  if [[ "$current_pid" == "$new_pid" ]] &&
    is_main_activity_focused "$focused_window_dump" &&
    [[ "$service_dump" =~ 'isForeground=true|foreground=true|foregroundId=[1-9]' ]] &&
    [[ "$service_dump" == *"restartCount=1"* ]] &&
    [[ "$ui_dump" == *"记录中"* && "$ui_dump" == *"已恢复"* ]] &&
    [[ "$after_thread_count" == "1" && "$process_count" == "1" && "$service_record_count" == "1" ]]; then
    rebind_ready=1
    break
  fi
  sleep 0.5
done
if (( rebind_ready == 0 )); then
  print -u2 "失败：用户返回后 Activity 未重绑同一恢复进程，或产生重复 Service/定位线程"
  print -u2 "oldPid=$old_pid newPid=$new_pid currentPid=${current_pid:-NONE} processCount=$process_count serviceRecords=$service_record_count locationThreads=$after_thread_count"
  print -u2 -- "--- Service ---"
  print -u2 "$service_dump"
  print -u2 -- "--- Window ---"
  print -u2 "$focused_window_dump"
  exit 1
fi

exit_info_verified=0
if (( actual_sdk >= 30 )); then
  exit_info_dump="$(adb_target shell dumpsys activity exit-info "$APP_PACKAGE" 2>/dev/null | tr -d '\r' || true)"
  exit_info_record="$(
    print -r -- "$exit_info_dump" |
      grep -A2 "pid=$old_pid " |
      head -3 || true
  )"
  if [[ "$exit_info_record" == *"pid=$old_pid"* &&
    ( "$exit_info_record" == *"reason=SIGNALED"* || "$exit_info_record" == *"(SIGNALED)"* ) &&
    "$exit_info_record" == *"status=9"* ]]; then
    exit_info_verified=1
  else
    print -u2 "失败：ApplicationExitInfo 未记录 oldPid=$old_pid 的 SIGNALED/status=9"
    print -u2 "$exit_info_record"
    exit 1
  fi
fi

crash_dump="$(adb_target logcat -b crash -d 2>/dev/null | tr -d '\r' || true)"
if [[ "$crash_dump" == *"$APP_PACKAGE"* || "$crash_dump" == *"FATAL EXCEPTION"* ]]; then
  print -u2 "失败：恢复窗口出现崩溃日志"
  print -u2 "$crash_dump"
  exit 1
fi

print "[M6] Activity 与 Service 已在同一新进程完成恢复；正常结束活动行程"
adb_target shell am start-foreground-service \
  -n "$SERVICE_COMPONENT" \
  -a "$END_TRIP_ACTION" >/dev/null

end_confirmed=0
deadline=$((SECONDS + 15))
while (( SECONDS < deadline )); do
  ui_dump="$(read_ui_dump || true)"
  if [[ "$ui_dump" == *"等待开始"* ]]; then
    end_confirmed=1
    break
  fi
  sleep 0.5
done
if (( end_confirmed == 0 )); then
  print -u2 "失败：恢复后的活动行程未在 15 秒内正常结束"
  exit 1
fi

print "M6_PROCESS_RECREATION_REBIND_RESULT=PASS"
print "serial=$SERIAL"
print "avd=$actual_avd"
print "sdk=$actual_sdk"
print "old_pid=$old_pid"
print "new_pid=$new_pid"
print "process_count=$process_count"
print "service_record_count=$service_record_count"
print "location_threads_before=$before_thread_count"
print "location_threads_after=$after_thread_count"
print "service_restart_count=1"
print "service_recovered_before_activity_return=1"
print "activity_return_rebound_existing_service=1"
print "ui_recording_and_restored=1"
print "exit_info_signaled_status_9=$exit_info_verified"
print "end_confirmed=1"
print "boundary=Activity 前台与 location Service 同进程被 SIGKILL 后，START_STICKY 先独立恢复 Service；用户重新打开应用时 Activity 重绑同一新进程，仍只有一个 ServiceRecord 和一条定位线程"
