"""在模拟器上实测"进入角色聊天框"的帧耗时喵。

为什么要脚本化喵：之前我用一串 adb 命令手测，结果有一次 tap 根本没点中
（应用还在聊天页），gfxinfo 报出 "Total frames rendered: 0"，
而我差点把那个 0 当成"优化成功"喵。这个脚本每一步都先**确认自己在哪一屏**，
不确认就报错退出，避免再拿无效样本下结论喵。
"""
import re
import subprocess
import sys
import time

sys.stdout.reconfigure(encoding="utf-8")
ADB = r"C:\Users\xiaowenChen\AppData\Local\Android\Sdk\platform-tools\adb.exe"
PKG = "com.qingyu.app.debug"
XML = r"F:\qingyu\_perf_ui.xml"


def sh(*args):
    return subprocess.run([ADB, "shell", *args], capture_output=True,
                          text=True, encoding="utf-8", errors="replace").stdout


def ui_texts():
    """当前屏幕上的 (文本, 中心坐标) 列表喵。"""
    subprocess.run([ADB, "shell", "uiautomator", "dump", "/sdcard/_p.xml"],
                   capture_output=True)
    subprocess.run([ADB, "pull", "/sdcard/_p.xml", XML], capture_output=True)
    out = []
    try:
        text = open(XML, encoding="utf-8").read()
    except OSError:
        return out
    for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', text):
        label = m.group(1)
        x1, y1, x2, y2 = (int(g) for g in m.groups()[1:])
        if label.strip():
            out.append((label, (x1 + x2) // 2, (y1 + y2) // 2))
    return out


def back_to_list(target="长对话测试"):
    """确保回到主列表页，并返回那个会话行的坐标喵。"""
    for _ in range(4):
        texts = ui_texts()
        hit = next((t for t in texts if t[0] == target), None)
        if hit:
            return hit[1], hit[2]
        sh("input", "keyevent", "KEYCODE_BACK")
        time.sleep(1.2)
    return None


def measure(cycles=5, target="长对话测试"):
    # **必须先确认进程没被冻结**喵：应用在后台待久了会被系统缓存冻结，
    # 这时 gfxinfo 只会回一句 "Skipped: process is frozen"，
    # 拿它当样本会得出完全错误的结论（我踩过：把 0 帧误读成"零卡顿"）喵。
    sh("am", "start", "-n", f"{PKG}/com.qingyu.app.MainActivity")
    time.sleep(4)
    probe = sh("dumpsys", "gfxinfo", PKG)
    if "frozen" in probe:
        print("  !! 进程仍被冻结，本次测量无效")
        return None

    coords = back_to_list(target)
    if coords is None:
        print(f"  !! 回不到列表页，找不到「{target}」")
        return None
    cx, cy = coords
    print(f"  会话行坐标 ({cx},{cy})")

    sh("dumpsys", "gfxinfo", PKG, "reset")
    for i in range(cycles):
        sh("input", "tap", str(cx), str(cy))
        time.sleep(2.0)
        sh("input", "keyevent", "KEYCODE_BACK")
        time.sleep(1.2)

    # **这里绝不能再 reset** 喵：那会把刚刚跑出来的样本全部清掉，
    # 于是读到 "Total frames rendered: 0"，而那个 0 很容易被误当成"零卡顿"喵。
    dump = sh("dumpsys", "gfxinfo", PKG)
    if "frozen" in dump:
        print("  !! 测量途中进程被冻结，本次无效")
        return None
    return dump


def report(dump, label):
    if dump is None:
        print("\n  （本次没有有效样本）")
        return
    print(f"\n  === {label} ===")
    keys = ["Total frames rendered", "Janky frames:", "50th percentile",
            "90th percentile", "95th percentile", "99th percentile",
            "Number Missed Vsync", "Number Slow UI thread",
            "Number Slow issue draw commands"]
    for line in dump.splitlines():
        s = line.strip()
        if any(s.startswith(k) for k in keys):
            print("  " + s)


if __name__ == "__main__":
    cycles = int(sys.argv[1]) if len(sys.argv) > 1 else 5
    print(f"=== 连续进出 {cycles} 次 ===")
    report(measure(cycles), f"进入聊天框 ×{cycles}")
