"""Floating always-on-top BPM widget for Windows/Linux desktops.

Runs a small HTTP listener and shows whatever BPM it receives in a frameless,
transparent, draggable window. Point Fit3Free's "WiFi fallback" toggle at this
machine (http://<pc-ip>:8765/) and the number tracks your watch live.

    python desktop_widget.py                 # listen on 0.0.0.0:8765
    python desktop_widget.py --port 9000
    python desktop_widget.py --host 127.0.0.1

Drag with left mouse, right-click to close.

SECURITY: the listener has no authentication or encryption. Binding to 0.0.0.0
means anything on your network can POST a number to it, and BPM values travel
in plaintext HTTP. That is fine on a home LAN and wrong on public WiFi. Use
--host 127.0.0.1 with an SSH tunnel if you need it locked down.
"""
import argparse
import http.server
import json
import threading
import tkinter as tk

current_bpm = "--"


class HRHandler(http.server.BaseHTTPRequestHandler):
    """Accepts {"bpm": 85}, a bare 85, or a raw HR Measurement hex payload."""

    def do_POST(self):
        global current_bpm
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length).decode("utf-8", "replace")

        bpm = self._extract(body.strip())
        if bpm is not None and 20 <= bpm <= 250:
            current_bpm = str(bpm)

        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.end_headers()
        self.wfile.write(b"OK")

    def do_GET(self):
        """Latest reading as JSON, handy for other widgets or overlays."""
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps({"bpm": current_bpm}).encode())

    @staticmethod
    def _extract(text):
        if not text:
            return None
        if text.isdigit():
            return int(text)
        try:
            data = json.loads(text)
        except json.JSONDecodeError:
            return None
        if isinstance(data, dict):
            for key in ("bpm", "hr", "heartRate", "value"):
                if data.get(key) is not None:
                    return int(data[key])
            return None
        if isinstance(data, (int, float)):
            return int(data)
        if isinstance(data, list) and len(data) >= 2:
            # Raw [flags, bpm, ...] straight off the BLE characteristic.
            flags = int(data[0])
            if flags & 0x01 and len(data) >= 3:
                return int(data[1]) | (int(data[2]) << 8)
            return int(data[1])
        return None

    def log_message(self, fmt, *args):
        pass  # Keep the console quiet.


def start_server(host, port):
    try:
        http.server.HTTPServer((host, port), HRHandler).serve_forever()
    except OSError as e:
        print(f"Could not bind {host}:{port} - already in use? ({e})")


class FloatingWidget:
    def __init__(self, root):
        self.root = root
        self.x = self.y = 0

        root.overrideredirect(True)        # no title bar or borders
        root.attributes("-topmost", True)  # float above other windows
        root.config(bg="black")
        try:
            # Chroma-key black to fake transparency. Windows only.
            root.wm_attributes("-transparentcolor", "black")
        except tk.TclError:
            pass

        w, h = root.winfo_screenwidth(), root.winfo_screenheight()
        root.geometry(f"200x100+{w - 250}+{h - 150}")

        root.bind("<ButtonPress-1>", self.start_move)
        root.bind("<B1-Motion>", self.do_move)
        root.bind("<Button-3>", lambda e: root.destroy())

        self.bpm_label = tk.Label(root, text="--", fg="#ff2a5f", bg="black",
                                  font=("Segoe UI Black", 48, "bold"))
        self.bpm_label.pack(side="left")

        tk.Label(root, text="BPM", fg="#cccccc", bg="black",
                 font=("Segoe UI", 14, "bold")).pack(side="left", anchor="s", pady=15)

        self.update_ui()

    def start_move(self, event):
        self.x, self.y = event.x, event.y

    def do_move(self, event):
        x = self.root.winfo_x() + event.x - self.x
        y = self.root.winfo_y() + event.y - self.y
        self.root.geometry(f"+{x}+{y}")

    def update_ui(self):
        self.bpm_label.config(text=current_bpm)
        if current_bpm.isdigit():
            val = int(current_bpm)
            colour = "#ff1e1e" if val > 140 else "#ff8c00" if val > 100 else "#ff2a5f"
            self.bpm_label.config(fg=colour)
        self.root.after(1000, self.update_ui)


def main():
    ap = argparse.ArgumentParser(description="Floating desktop BPM widget.")
    ap.add_argument("--host", default="0.0.0.0",
                    help="bind address (default 0.0.0.0, use 127.0.0.1 to stay local)")
    ap.add_argument("--port", type=int, default=8765, help="listen port (default 8765)")
    args = ap.parse_args()

    if args.host == "0.0.0.0":
        print(f"Listening on 0.0.0.0:{args.port} - unauthenticated, LAN-reachable.")
    threading.Thread(target=start_server, args=(args.host, args.port), daemon=True).start()

    root = tk.Tk()
    FloatingWidget(root)
    root.mainloop()


if __name__ == "__main__":
    main()
