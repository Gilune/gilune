"""Run one command with a deadline; capture owned JVM stacks before terminating it."""

import argparse
import json
import os
from pathlib import Path
import platform
import shutil
import signal
import subprocess
import sys
import threading


def descendants(root):
    if os.name == "nt":
        result = subprocess.run([
            "powershell", "-NoProfile", "-Command",
            "Get-CimInstance Win32_Process | Select-Object ProcessId,ParentProcessId,Name | ConvertTo-Json -Compress",
        ], capture_output=True, text=True, check=True, timeout=15)
        rows = [(p["ProcessId"], p["ParentProcessId"], p["Name"])
                for p in json.loads(result.stdout)]
    else:
        result = subprocess.run(["ps", "-eo", "pid=,ppid=,comm="],
                                capture_output=True, text=True, check=True, timeout=15)
        rows = [(int(pid), int(parent), name)
                for pid, parent, name in (line.split(None, 2) for line in result.stdout.splitlines())]
    selected = {root}
    while True:
        added = {pid for pid, parent, _ in rows if parent in selected} - selected
        if not added:
            return [(pid, name) for pid, _, name in rows if pid in selected]
        selected.update(added)


def diagnose(pid, directory):
    try:
        owned = descendants(pid)
        (directory / "processes.json").write_text(json.dumps(owned), encoding="utf-8")
        jcmd = shutil.which("jcmd")
        if jcmd:
            for child, name in owned:
                if name.lower() in ("java", "java.exe"):
                    with (directory / f"threads-{child}.txt").open("w", encoding="utf-8") as output:
                        try:
                            subprocess.run([jcmd, str(child), "Thread.print", "-l"],
                                           stdout=output, stderr=subprocess.STDOUT, timeout=10)
                        except subprocess.TimeoutExpired:
                            output.write("\nThread dump did not finish within 10 seconds.\n")
    except Exception as error:
        (directory / "diagnostic-error.txt").write_text(str(error), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--timeout", type=float, default=1200, help="Wall-clock seconds")
    parser.add_argument("--output", default="build/ci-diagnostics")
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command
    if command and command[0] == "--":
        command = command[1:]
    if not command:
        parser.error("a command is required")
    directory = Path(args.output)
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "environment.json").write_text(json.dumps({
        "platform": platform.platform(), "python": sys.version,
        "cpu_count": os.cpu_count(), "disk_free": shutil.disk_usage(directory).free,
    }, indent=2), encoding="utf-8")
    if os.name == "nt" and command[0].lower().endswith((".bat", ".cmd")):
        command[0] = str(Path(command[0]).resolve())
        command = [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c", *command]
    with (directory / "command.log").open("w", encoding="utf-8") as log:
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   text=True, encoding="utf-8", errors="replace",
                                   start_new_session=os.name != "nt")
        def copy_output():
            for line in process.stdout:
                log.write(line)
                log.flush()
                print(line, end="", flush=True)
        pump = threading.Thread(target=copy_output, daemon=True)
        pump.start()
        try:
            code = process.wait(timeout=args.timeout)
        except subprocess.TimeoutExpired:
            print("Deadline exceeded; collecting JVM stacks before stopping the command.", flush=True)
            diagnose(process.pid, directory)
            if os.name == "nt":
                subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], check=False)
            else:
                os.killpg(process.pid, signal.SIGKILL)
            process.wait()
            code = 124
        pump.join(timeout=10)
    return code


if __name__ == "__main__":
    sys.exit(main())
