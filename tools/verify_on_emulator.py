"""把"用模拟器端到端验证"固化成一条命令喵。

为什么要脚本化喵：这一版之前我发布了五个版本，每次都在报告里写"没有设备可跑"，
但实际上这台机器上一直有模拟器（Medium_Phone）喵。结果是五次"编译通过就发布"，
而用户拿到手发现功能根本没生效喵。这个脚本存在的意义就是让那次失误无法重演喵。

用法喵：
    python verify_on_emulator.py            # 装包 + 启动 + 截图 + 查崩
    python verify_on_emulator.py --db       # 额外把数据库拉出来查 schema 与数据
"""
import argparse
import os
import re
import subprocess
import sys
import time

sys.stdout.reconfigure(encoding="utf-8")

SDK = r"C:\Users\xiaowenChen\AppData\Local\Android\Sdk"
ADB = os.path.join(SDK, "platform-tools", "adb.exe")
EMULATOR = os.path.join(SDK, "emulator", "emulator.exe")
AVD = "Medium_Phone"
APK = r"F:\qingyu\app\build\outputs\apk\debug\app-debug.apk"
PKG = "com.qingyu.app.debug"
SHOT = r"F:\qingyu\_verify_shot.png"
DB = r"F:\qingyu\_verify_db.sqlite"


def run(args, **kw):
    return subprocess.run(args, capture_output=True, text=True,
                          encoding="utf-8", errors="replace", **kw)


def adb(*args, timeout=180):
    return run([ADB, *args], timeout=timeout)


def devices():
    out = adb("devices").stdout
    return [l.split()[0] for l in out.splitlines()[1:] if l.strip().endswith("device")]


def ensure_emulator():
    if devices():
        print("  模拟器已在运行")
        return True
    if not os.path.exists(EMULATOR):
        print(f"  !! 找不到模拟器: {EMULATOR}")
        return False
    print("  启动模拟器…")
    subprocess.Popen([EMULATOR, "-avd", AVD, "-no-snapshot-load",
                      "-no-boot-anim", "-gpu", "swiftshader_indirect"],
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    adb("wait-for-device")
    for i in range(90):
        if adb("shell", "getprop", "sys.boot_completed").stdout.strip() == "1":
            print(f"  开机完成（第 {i + 1} 次探测）")
            return True
        time.sleep(5)
    print("  !! 等开机超时")
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", action="store_true", help="额外检查数据库 schema 与数据")
    args = ap.parse_args()

    if not ensure_emulator():
        return 1

    print("\n=== 1. 安装 ===")
    out = adb("install", "-r", APK, timeout=600).stdout
    print("  " + out.strip().splitlines()[-1])
    if "Success" not in out:
        return 1

    print("=== 2. 已装版本（必须是刚构建的那个） ===")
    v = adb("shell", "dumpsys", "package", PKG).stdout
    for line in v.splitlines():
        if "versionName" in line or "versionCode" in line:
            print("  " + line.strip())
            break

    print("=== 3. 启动并观察是否崩溃 ===")
    adb("logcat", "-c")
    adb("shell", "am", "start", "-n", f"{PKG}/com.qingyu.app.MainActivity")
    time.sleep(8)
    pid = adb("shell", "pidof", PKG).stdout.strip()
    print(f"  进程: {'活着 pid=' + pid if pid else '!! 没起来'}")

    crash = adb("logcat", "-d").stdout
    ours = [l for l in crash.splitlines()
            if ("FATAL" in l or "AndroidRuntime" in l) and PKG in l]
    if ours:
        print("  !! 有崩溃:")
        for l in ours[-15:]:
            print("     " + l)
    else:
        print("  没有本应用的崩溃记录")

    print("=== 4. 截图 ===")
    adb("shell", "screencap", "-p", "/sdcard/_verify.png")
    adb("pull", "/sdcard/_verify.png", SHOT)
    print(f"  {SHOT}")

    if args.db:
        print("=== 5. 数据库 ===")
        adb("shell", "am", "force-stop", PKG)
        time.sleep(2)
        # exec-out 是二进制安全的；run-as 的 shell 重定向会写出 0 字节喵。
        with open(DB, "wb") as f:
            p = subprocess.run([ADB, "exec-out", "run-as", PKG, "cat", "databases/qingyu.db"],
                               stdout=f, stderr=subprocess.DEVNULL)
        size = os.path.getsize(DB) if os.path.exists(DB) else 0
        print(f"  拉出 {size} 字节")
        if size > 0:
            import sqlite3
            c = sqlite3.connect(DB)
            print("  user_version =", c.execute("PRAGMA user_version").fetchone()[0])
            tabs = [r[0] for r in c.execute("SELECT name FROM sqlite_master WHERE type='table'")]
            print("  表:", tabs)
            if "conversations" in tabs:
                cols = [r[1] for r in c.execute("PRAGMA table_info(conversations)")]
                print("  conversations 列:", cols)
                print("  未读:", list(c.execute("SELECT id,title,unread_count FROM conversations")))
    return 0


if __name__ == "__main__":
    sys.exit(main())
