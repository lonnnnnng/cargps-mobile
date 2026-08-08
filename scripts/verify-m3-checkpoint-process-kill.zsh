#!/bin/zsh

# 作者：long｜M3 破坏性验证只允许操作两个已核对的模拟器，避免 adb 默认设备误指向其它真机。
set -euo pipefail

PROJECT_DIR="${0:A:h}/.."
SERIAL="${1:-emulator-5554}"
APP_PACKAGE="com.cargps.mobile"
SERVICE_COMPONENT="${APP_PACKAGE}/.TripRecordingService"
PROBE_COMPONENT="${APP_PACKAGE}/.M3CheckpointProbeService"
PROBE_ACTION="${APP_PACKAGE}.action.M3_CHECKPOINT_PROBE"
QUERY_ACTION="${APP_PACKAGE}.action.M3_CHECKPOINT_QUERY"
START_TRIP_ACTION="${APP_PACKAGE}.action.START_TRIP"
PROBE_TAG="CarGpsM3Probe"
READY_SENTINEL="CARGPS_M3_PROBE_READY"
SENTINEL="CARGPS_M3_CHECKPOINT_BLOCKED"
QUERY_SENTINEL="CARGPS_M3_QUERY_RESULT"
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

actual_avd="$(adb_target shell getprop ro.boot.qemu.avd_name | tr -d '\r')"
if [[ -z "$actual_avd" ]]; then
  actual_avd="$(adb_target shell getprop ro.kernel.qemu.avd_name | tr -d '\r')"
fi
actual_sdk="$(adb_target shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ "$actual_avd" != "$EXPECTED_AVD" || "$actual_sdk" != "$EXPECTED_SDK" ]]; then
  print -u2 "拒绝执行：$SERIAL 映射为 AVD=$actual_avd SDK=$actual_sdk，预期 AVD=$EXPECTED_AVD SDK=$EXPECTED_SDK"
  exit 2
fi

print "[M3] 设备已锁定：serial=$SERIAL avd=$actual_avd sdk=$actual_sdk"

build_and_install() {
  print "[M3] 构建并安装独立 probe APK"
  (cd "$PROJECT_DIR" && ./gradlew :mobile-app:assembleProbe --console=plain)
  if ! adb_target install -r "$PROJECT_DIR/mobile-app/build/outputs/apk/probe/mobile-app-probe.apk" >/dev/null; then
    adb_target shell pm uninstall "$APP_PACKAGE" >/dev/null 2>&1 || true
    adb_target install "$PROJECT_DIR/mobile-app/build/outputs/apk/probe/mobile-app-probe.apk" >/dev/null
  fi
  # 作者：long｜清理只针对本次 CarGPS 探针的应用数据，确保活动行程和通知不会混入上一次验证。
  adb_target shell pm clear "$APP_PACKAGE" >/dev/null
  adb_target shell pm grant "$APP_PACKAGE" android.permission.ACCESS_FINE_LOCATION
  adb_target shell pm grant "$APP_PACKAGE" android.permission.ACCESS_COARSE_LOCATION
  if (( actual_sdk >= 33 )); then
    adb_target shell pm grant "$APP_PACKAGE" android.permission.POST_NOTIFICATIONS
  fi
}

evidence_complete=0
probe_log="$(mktemp -t cargps-m3-probe.XXXXXX)"
cleanup_probe() {
  if (( evidence_complete == 1 )); then
    if [[ "${KEEP_RECOVERED_STATE:-0}" != "1" ]]; then
      # 证据已经采集完成后才清理恢复服务，避免改变 START_STICKY 判定。
      adb_target shell am force-stop "$APP_PACKAGE" >/dev/null 2>&1 || true
    fi
  else
    adb_target shell am force-stop "$APP_PACKAGE" >/dev/null 2>&1 || true
  fi
  rm -f "$probe_log"
}
trap cleanup_probe EXIT INT TERM

build_and_install
adb_target logcat -c

print "[M3] 启动 probe-only 阻塞探针并等待 $SENTINEL"
adb_target shell am start-foreground-service \
  -n "$PROBE_COMPONENT" \
  -a "$PROBE_ACTION" >|"$probe_log" 2>&1

probe_ready=0
for ((attempt = 1; attempt <= POLL_SECONDS * 2; attempt++)); do
  log_snapshot="$(adb_target logcat -d -s "$PROBE_TAG:I" '*:S' 2>/dev/null || true)"
  if [[ "$log_snapshot" == *"$READY_SENTINEL"* ]]; then
    probe_ready=1
    break
  fi
  sleep 0.5
done
if (( probe_ready == 0 )); then
  print -u2 "失败：${POLL_SECONDS} 秒内没有观察到 $READY_SENTINEL"
  sed -n '1,160p' "$probe_log" >&2 || true
  exit 1
fi

# 作者：long｜probe Manifest 只在专用变体临时导出生产 Service，shell 直接启动可保留 Android 14+ location FGS 的系统豁免。
adb_target shell am start-foreground-service \
  -n "$SERVICE_COMPONENT" \
  -a "$START_TRIP_ACTION" >/dev/null

sentinel_seen=0
old_pid=""
for ((attempt = 1; attempt <= POLL_SECONDS * 2; attempt++)); do
  log_snapshot="$(adb_target logcat -d -s "$PROBE_TAG:I" '*:S' 2>/dev/null || true)"
  if [[ "$log_snapshot" == *"$SENTINEL"* ]]; then
    sentinel_seen=1
    old_pid="$(adb_target shell pidof "$APP_PACKAGE" 2>/dev/null | tr -d '\r' | awk '{print $1}')"
    break
  fi
  sleep 0.5
done
if (( sentinel_seen == 0 )); then
  print -u2 "失败：${POLL_SECONDS} 秒内没有观察到 $SENTINEL"
  print -u2 -- "--- probe 启动输出 ---"
  sed -n '1,160p' "$probe_log" >&2 || true
  exit 1
fi
if [[ -z "$old_pid" ]]; then
  print -u2 "失败：观察到 sentinel，但没有找到 $APP_PACKAGE PID"
  exit 1
fi

print "[M3] 已进入提交前阻塞：oldPid=$old_pid；使用应用 UID 发送 SIGKILL"
set +e
adb_target shell run-as "$APP_PACKAGE" kill -9 "$old_pid" >/dev/null 2>&1
kill_command_status=$?
set -e
remaining_old_pid="$(adb_target shell pidof "$APP_PACKAGE" 2>/dev/null | tr -d '\r' | awk '{print $1}' || true)"
if [[ "$remaining_old_pid" == "$old_pid" ]]; then
  print -u2 "失败：应用 UID 的 SIGKILL 未终止 oldPid=$old_pid，命令返回码=$kill_command_status"
  exit 1
fi

new_pid=""
for ((attempt = 1; attempt <= POLL_SECONDS * 2; attempt++)); do
  candidate_pid="$(adb_target shell pidof "$APP_PACKAGE" 2>/dev/null | tr -d '\r' | awk '{print $1}' || true)"
  if [[ -n "$candidate_pid" && "$candidate_pid" != "$old_pid" ]]; then
    new_pid="$candidate_pid"
    break
  fi
  sleep 0.5
done
if [[ -z "$new_pid" ]]; then
  print -u2 "失败：SIGKILL 后 ${POLL_SECONDS} 秒内没有观察到新的 $APP_PACKAGE PID"
  exit 1
fi

service_dump=""
notification_dump=""
service_ready=0
notification_ready=0
for ((attempt = 1; attempt <= POLL_SECONDS * 2; attempt++)); do
  service_dump="$(adb_target shell dumpsys activity services "$SERVICE_COMPONENT" 2>/dev/null || true)"
  notification_dump="$(adb_target shell dumpsys notification --noredact 2>/dev/null || true)"
  if [[ "$service_dump" =~ 'isForeground=true|foreground=true|foregroundId=[1-9]' ]]; then
    service_ready=1
  fi
  if [[ "$notification_dump" == *"CarGPS"* ]]; then
    notification_ready=1
  fi
  if (( service_ready == 1 && notification_ready == 1 )); then break; fi
  sleep 0.5
done
if (( service_ready == 0 )); then
  print -u2 "失败：新 PID=$new_pid 已出现，但 START_STICKY Service 未显示前台状态"
  print -u2 "$service_dump"
  exit 1
fi
if (( notification_ready == 0 )); then
  print -u2 "失败：新 PID=$new_pid 的 CarGPS 前台通知未出现"
  print -u2 "$notification_dump"
  exit 1
fi

print "[M3] 新 Service 已恢复：newPid=$new_pid；通过 probe-only Room 查询 action 核对确认边界"
adb_target shell am start-foreground-service \
  -n "$PROBE_COMPONENT" \
  -a "$QUERY_ACTION" >/dev/null

query_line=""
for ((attempt = 1; attempt <= 30; attempt++)); do
  log_snapshot="$(adb_target logcat -d -s "$PROBE_TAG:I" '*:S' 2>/dev/null || true)"
  query_line="$(print -r -- "$log_snapshot" | grep "$QUERY_SENTINEL" | tail -1 || true)"
  if [[ -n "$query_line" ]]; then break; fi
  sleep 0.5
done
if [[ "$query_line" != *"active=true"* || "$query_line" != *"mode=RECORDING"* ||
  "$query_line" != *"points=0"* || "$query_line" != *"confirmed=0"* ||
  "$query_line" != *"last=0"* ]]; then
  print -u2 "失败：恢复后的 Room 边界不符合预期"
  print -u2 "query_line=$query_line"
  exit 1
fi

evidence_complete=1
print "M3_CHECKPOINT_PROCESS_KILL_RESULT=PASS"
print "serial=$SERIAL"
print "avd=$actual_avd"
print "sdk=$actual_sdk"
print "old_pid=$old_pid"
print "new_pid=$new_pid"
print "confirmed_point_count=0"
print "unconfirmed_loss_window=16"
print "active_trip_mode=RECORDING"
print "service_foreground=1"
print "notification_contains=CarGPS"
print "boundary=进程在第16点批次真正提交前被SIGKILL；恢复到最近确认检查点，未确认尾批最多16点"
