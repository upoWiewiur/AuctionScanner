import http.server
import json
import sqlite3
import urllib.parse
from datetime import datetime

PORT = 8080
import os

# Pobranie ścieżki do folderu, w którym znajduje się app.py
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DB_FILE = os.path.join(BASE_DIR, "market_data.db")

def init_db():
    conn = sqlite3.connect(DB_FILE)
    c = conn.cursor()
    c.execute('''
        CREATE TABLE IF NOT EXISTS price_points (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            item_name TEXT NOT NULL,
            timestamp INTEGER NOT NULL,
            price REAL NOT NULL,
            volume INTEGER NOT NULL
        )
    ''')
    c.execute('''
        CREATE TABLE IF NOT EXISTS snipes (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            item_name TEXT NOT NULL,
            timestamp INTEGER NOT NULL,
            price REAL NOT NULL,
            avg_price REAL NOT NULL
        )
    ''')
    # Unique index to prevent duplicate entries efficiently
    c.execute('CREATE UNIQUE INDEX IF NOT EXISTS idx_item_unique ON price_points(item_name, timestamp, price)')
    c.execute('CREATE INDEX IF NOT EXISTS idx_item_time ON price_points(item_name, timestamp)')
    
    # One-time migration: merge items without namespace into minecraft: namespace
    c.execute("UPDATE price_points SET item_name = 'minecraft:' || item_name WHERE item_name NOT LIKE '%:%'")
    
    conn.commit()
    conn.close()

class MarketAPIHandler(http.server.BaseHTTPRequestHandler):
    def set_cors_headers(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type')

    def do_OPTIONS(self):
        self.send_response(204)
        self.set_cors_headers()
        self.end_headers()

    def do_POST(self):
        path = self.path.rstrip('/')
        if path == '/api/market-data':
            content_length = int(self.headers.get('Content-Length', 0))
            post_data = self.rfile.read(content_length)
            try:
                payload = json.loads(post_data.decode('utf-8'))
                if payload.get('server') != 'donutsmp.net':
                    self.send_response(403)
                    self.set_cors_headers()
                    self.end_headers()
                    self.wfile.write(json.dumps({"error": "Forbidden: Only DonutSMP.net is allowed"}).encode('utf-8'))
                    return
                
                data = payload.get('data', {})
                conn = sqlite3.connect(DB_FILE)
                c = conn.cursor()
                new_points = 0
                for item_name, item_data in data.items():
                    norm_name = item_name if ":" in item_name else f"minecraft:{item_name}"
                    history = item_data.get('history', [])
                    for pt in history:
                        ts = pt.get('timestamp', 0)
                        price = pt.get('price', 0.0)
                        volume = pt.get('volume', 0)
                        c.execute('INSERT OR IGNORE INTO price_points (item_name, timestamp, price, volume) VALUES (?, ?, ?, ?)',
                                  (norm_name, ts, price, volume))
                        if c.rowcount > 0:
                            new_points += 1
                conn.commit()
                conn.close()
                self.send_response(200)
                self.set_cors_headers()
                self.send_header('Content-Type', 'application/json')
                self.end_headers()
                self.wfile.write(json.dumps({"status": "success", "inserted": new_points}).encode('utf-8'))
            except Exception as e:
                self.send_response(400)
                self.set_cors_headers()
                self.end_headers()
                self.wfile.write(json.dumps({"error": str(e)}).encode('utf-8'))
        elif path == '/api/snipes':
            content_length = int(self.headers.get('Content-Length', 0))
            post_data = self.rfile.read(content_length)
            try:
                data = json.loads(post_data.decode('utf-8'))
                conn = sqlite3.connect(DB_FILE)
                c = conn.cursor()
                c.execute('INSERT INTO snipes (item_name, timestamp, price, avg_price) VALUES (?, ?, ?, ?)',
                          (data['item_name'], int(datetime.now().timestamp() * 1000), data['price'], data['avg_price']))
                conn.commit()
                conn.close()
                self.send_response(200)
                self.set_cors_headers()
                self.end_headers()
                self.wfile.write(json.dumps({"status": "success"}).encode('utf-8'))
            except Exception as e:
                self.send_response(400)
                self.set_cors_headers()
                self.end_headers()
                self.wfile.write(json.dumps({"error": str(e)}).encode('utf-8'))
        else:
            self.send_response(404)
            self.end_headers()

    def do_GET(self):
        path = self.path.rstrip('/')
        if path == '/api/market-data':
            try:
                conn = sqlite3.connect(DB_FILE)
                c = conn.cursor()
                # Limit to last 48 hours to keep charts clean and response small
                min_ts = int((datetime.now().timestamp() - (48 * 3600)) * 1000)
                c.execute('SELECT item_name, timestamp, price, volume FROM price_points WHERE timestamp > ? ORDER BY item_name, timestamp ASC', (min_ts,))
                rows = c.fetchall()
                conn.close()
                result = {}
                for row in rows:
                    item_name, ts, price, vol = row
                    if item_name not in result:
                        result[item_name] = {"itemName": item_name, "history": []}
                    result[item_name]["history"].append({"timestamp": ts, "price": price, "volume": vol})
                self.send_response(200)
                self.set_cors_headers()
                self.send_header('Content-Type', 'application/json')
                self.end_headers()
                self.wfile.write(json.dumps(result).encode('utf-8'))
            except Exception as e:
                self.send_response(500)
                self.set_cors_headers()
                self.end_headers()
                self.wfile.write(json.dumps({"error": str(e)}).encode('utf-8'))
        elif path == '/api/snipes':
            try:
                conn = sqlite3.connect(DB_FILE)
                c = conn.cursor()
                c.execute('SELECT item_name, timestamp, price, avg_price FROM snipes ORDER BY timestamp DESC LIMIT 50')
                rows = c.fetchall()
                conn.close()
                result = [{"item_name": r[0], "timestamp": r[1], "price": r[2], "avg_price": r[3]} for r in rows]
                self.send_response(200)
                self.set_cors_headers()
                self.send_header('Content-Type', 'application/json')
                self.end_headers()
                self.wfile.write(json.dumps(result).encode('utf-8'))
            except Exception as e:
                self.send_response(500)
                self.set_cors_headers()
                self.end_headers()
                self.wfile.write(json.dumps({"error": str(e)}).encode('utf-8'))
        else:
            # Serve static files for the dashboard
            if self.path == '/' or self.path == '/index.html':
                filepath = os.path.join(BASE_DIR, 'web', 'index.html')
                content_type = 'text/html'
            elif self.path.startswith('/web/'):
                filepath = os.path.join(BASE_DIR, 'web', self.path[5:])
                content_type = 'text/css' if filepath.endswith('.css') else 'application/javascript'
            else:
                print(f"404 Not Found: {self.path}")
                self.send_response(404)
                self.end_headers()
                return

            try:
                if not os.path.exists(filepath):
                    print(f"File not found on disk: {filepath}")
                    self.send_response(404)
                    self.end_headers()
                    return
                
                with open(filepath, 'rb') as f:
                    content = f.read()
                print(f"Serving {content_type}: {filepath} ({len(content)} bytes)")
                self.send_response(200)
                self.send_header('Content-Type', content_type)
                self.end_headers()
                self.wfile.write(content)
            except Exception as e:
                print(f"File serving error: {e}")
                self.send_response(500)
                self.end_headers()

if __name__ == '__main__':
    init_db()
    # Cloud providers like Render specify the port via the PORT environment variable
    env_port = int(os.environ.get("PORT", 8080))
    server_address = ('', env_port)
    httpd = http.server.HTTPServer(server_address, MarketAPIHandler)
    print(f"Starting Market API server on port {env_port}...")
    print(f"Base Directory: {BASE_DIR}")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    httpd.server_close()
    print("Server stopped.")
