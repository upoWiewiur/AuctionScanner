import os
import sqlite3
from datetime import datetime
from typing import Dict, List, Optional, Any

from fastapi import FastAPI, Depends, HTTPException, Security, Request
from fastapi.security.api_key import APIKeyHeader
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel

PORT = int(os.environ.get('PORT', 8080))
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DB_FILE = os.path.join(BASE_DIR, "market_data.db")
WEB_DIR = os.path.join(BASE_DIR, "web")

API_SECRET_KEY = os.environ.get("API_SECRET_KEY", "")
api_key_header = APIKeyHeader(name="X-API-Key", auto_error=False)

app = FastAPI(title="Market Analyzer API")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

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
    c.execute('CREATE UNIQUE INDEX IF NOT EXISTS idx_item_unique ON price_points(item_name, timestamp, price)')
    c.execute('CREATE INDEX IF NOT EXISTS idx_item_time ON price_points(item_name, timestamp)')
    c.execute("UPDATE price_points SET item_name = 'minecraft:' || item_name WHERE item_name NOT LIKE '%:%'")
    conn.commit()
    conn.close()

init_db()

def get_api_key(api_key: str = Security(api_key_header)):
    if not API_SECRET_KEY:
        return True
    if api_key == API_SECRET_KEY:
        return True
    raise HTTPException(status_code=401, detail="Unauthorized")

def get_db():
    conn = sqlite3.connect(DB_FILE, timeout=10)
    try:
        yield conn
    finally:
        conn.close()

# Pydantic Models
class PricePointSchema(BaseModel):
    timestamp: int
    price: float
    volume: int

class ItemDataSchema(BaseModel):
    itemName: str
    history: List[PricePointSchema]

class MarketDataPayload(BaseModel):
    server: str
    data: Dict[str, ItemDataSchema]

class SnipePayload(BaseModel):
    item_name: str
    price: float
    avg_price: float

class VerifyDealPayload(BaseModel):
    item: str
    price: float

@app.get("/api/market-data")
def get_market_data(conn: sqlite3.Connection = Depends(get_db)):
    min_ts = int((datetime.now().timestamp() - (7 * 24 * 3600)) * 1000)
    c = conn.cursor()
    c.execute(
        'SELECT item_name, timestamp, price, volume FROM price_points '
        'WHERE timestamp > ? ORDER BY item_name, timestamp ASC',
        (min_ts,)
    )
    rows = c.fetchall()
    result = {}
    for item_name, ts, price, vol in rows:
        if item_name not in result:
            result[item_name] = {"itemName": item_name, "history": []}
        result[item_name]["history"].append({"timestamp": ts, "price": price, "volume": vol})
    return result

@app.post("/api/market-data")
def post_market_data(payload: MarketDataPayload, conn: sqlite3.Connection = Depends(get_db), _: bool = Depends(get_api_key)):
    if payload.server != 'donutsmp.net':
        raise HTTPException(status_code=403, detail="Forbidden: Only DonutSMP.net is allowed")
    
    c = conn.cursor()
    new_points = 0
    for item_name, item_data in payload.data.items():
        norm = item_name if ':' in item_name else f'minecraft:{item_name}'
        for pt in item_data.history:
            c.execute(
                'INSERT OR IGNORE INTO price_points (item_name, timestamp, price, volume) VALUES (?, ?, ?, ?)',
                (norm, pt.timestamp, pt.price, pt.volume)
            )
            if c.rowcount > 0:
                new_points += 1
    conn.commit()
    return {"status": "success", "inserted": new_points}

@app.get("/api/snipes")
def get_snipes(conn: sqlite3.Connection = Depends(get_db)):
    c = conn.cursor()
    c.execute('SELECT item_name, timestamp, price, avg_price FROM snipes ORDER BY timestamp DESC LIMIT 50')
    rows = c.fetchall()
    return [{"item_name": r[0], "timestamp": r[1], "price": r[2], "avg_price": r[3]} for r in rows]

@app.post("/api/snipes")
def post_snipes(payload: SnipePayload, conn: sqlite3.Connection = Depends(get_db), _: bool = Depends(get_api_key)):
    c = conn.cursor()
    c.execute(
        'INSERT INTO snipes (item_name, timestamp, price, avg_price) VALUES (?, ?, ?, ?)',
        (payload.item_name, int(datetime.now().timestamp() * 1000), payload.price, payload.avg_price)
    )
    conn.commit()
    return {"status": "success"}

@app.post("/verify-deal")
def verify_deal(payload: VerifyDealPayload, conn: sqlite3.Connection = Depends(get_db), _: bool = Depends(get_api_key)):
    c = conn.cursor()
    min_7d = int((datetime.now().timestamp() - 7 * 24 * 3600) * 1000)
    c.execute('SELECT price FROM price_points WHERE item_name=? AND timestamp>?', (payload.item, min_7d))
    prices = [r[0] for r in c.fetchall()]
    
    if len(prices) < 3:
        return {"isDeal": False, "reason": "insufficient_data", "dataPoints": len(prices)}
    
    prices.sort()
    trim = max(1, int(len(prices) * 0.10))
    trimmed = prices[trim:-trim] if len(prices) > 2 else prices
    avg = sum(trimmed) / len(trimmed)
    
    discount = ((avg - payload.price) / avg * 100) if avg > 0 else 0
    is_deal = discount >= 10.0
    
    return {
        "isDeal": is_deal,
        "discount": round(discount, 2),
        "avgPrice7d": round(avg, 2),
        "queriedPrice": payload.price,
        "recommendation": "BUY" if discount >= 10 else ("HOLD" if discount >= 0 else "OVERPRICED"),
        "dataPoints": len(prices)
    }

# Dynamic prices.json export for AutoBuy
@app.get("/api/prices.json")
def get_prices_json(conn: sqlite3.Connection = Depends(get_db)):
    c = conn.cursor()
    min_7d  = int((datetime.now().timestamp() - 7  * 24 * 3600) * 1000)
    min_24h = int((datetime.now().timestamp() - 24 *      3600) * 1000)

    c.execute('SELECT DISTINCT item_name FROM price_points WHERE timestamp > ?', (min_7d,))
    items = [r[0] for r in c.fetchall()]

    result = {}
    AH_TAX = 0.05

    for item in items:
        c.execute('SELECT price FROM price_points WHERE item_name=? AND timestamp>?', (item, min_7d))
        prices_7d = [r[0] for r in c.fetchall()]
        if not prices_7d: continue

        c.execute('SELECT MIN(price) FROM price_points WHERE item_name=? AND timestamp>?', (item, min_24h))
        row = c.fetchone()
        lowest_24h = row[0] if row and row[0] else min(prices_7d)

        avg_7d = sum(prices_7d) / len(prices_7d)
        latest = prices_7d[-1]
        ratio  = latest / avg_7d if avg_7d > 0 else 1.0

        if ratio <= 0.90:   rec = "BUY"
        elif ratio >= 1.05: rec = "SELL"
        else:               rec = "HOLD"

        suggest_sell  = avg_7d * 0.97
        net_profit    = (suggest_sell * (1 - AH_TAX)) - lowest_24h
        roi           = (net_profit / lowest_24h * 100) if lowest_24h > 0 else 0

        result[item] = {
            "avgPrice":    round(avg_7d,    2),
            "lowestPrice": round(lowest_24h, 2),
            "latestPrice": round(latest,    2),
            "suggestSell": round(suggest_sell, 2),
            "netProfit":   round(net_profit, 2),
            "roi":         round(roi,        2),
            "recommendation": rec,
            "dataPoints":  len(prices_7d),
            "lastUpdated": int(datetime.now().timestamp() * 1000)
        }
    return result

@app.get("/api/heatmap/{item_name:path}")
def get_heatmap(item_name: str, conn: sqlite3.Connection = Depends(get_db)):
    c = conn.cursor()
    min_ts = int((datetime.now().timestamp() - 14 * 24 * 3600) * 1000)
    c.execute(
        'SELECT timestamp, price FROM price_points '
        'WHERE item_name=? AND timestamp>? ORDER BY timestamp ASC',
        (item_name, min_ts)
    )
    rows = c.fetchall()

    sums   = [0.0] * 24
    counts = [0]   * 24
    for ts, price in rows:
        h = datetime.fromtimestamp(ts / 1000).hour
        sums[h]   += price
        counts[h] += 1

    heatmap = []
    for h in range(24):
        avg = (sums[h] / counts[h]) if counts[h] > 0 else None
        heatmap.append({"hour": h, "avgPrice": round(avg, 2) if avg else None, "count": counts[h]})

    return {"item": item_name, "heatmap": heatmap}

@app.get("/api/flip/{item_name:path}")
def get_flip(item_name: str, conn: sqlite3.Connection = Depends(get_db)):
    c = conn.cursor()
    min_7d  = int((datetime.now().timestamp() - 7  * 24 * 3600) * 1000)
    min_24h = int((datetime.now().timestamp() - 24 *      3600) * 1000)

    c.execute('SELECT price FROM price_points WHERE item_name=? AND timestamp>? ORDER BY timestamp', (item_name, min_7d))
    prices = [r[0] for r in c.fetchall()]

    c.execute('SELECT MIN(price) FROM price_points WHERE item_name=? AND timestamp>?', (item_name, min_24h))
    row = c.fetchone()

    if not prices:
        raise HTTPException(status_code=404, detail="No data for this item")

    AH_TAX  = 0.05
    avg     = sum(prices) / len(prices)
    buy_at  = row[0] if row and row[0] else min(prices)
    sell_at = avg * 0.97
    tax     = sell_at * AH_TAX
    net     = sell_at - tax - buy_at
    roi     = (net / buy_at * 100) if buy_at > 0 else 0

    return {
        "item":       item_name,
        "buyAt":      round(buy_at,  2),
        "sellAt":     round(sell_at, 2),
        "taxAmount":  round(tax,     2),
        "netProfit":  round(net,     2),
        "roi":        round(roi,     2),
        "avgPrice7d": round(avg,     2)
    }

# Static Fallbacks
app.mount("/web", StaticFiles(directory=WEB_DIR), name="web")
app.mount("/icons", StaticFiles(directory=os.path.join(WEB_DIR, "icons")), name="icons")

@app.get("/")
@app.get("/index.html")
def serve_index():
    return FileResponse(os.path.join(WEB_DIR, "index.html"))

@app.get("/mobile")
@app.get("/mobile.html")
def serve_mobile():
    return FileResponse(os.path.join(WEB_DIR, "mobile.html"))

@app.get("/manifest.json")
def serve_manifest():
    return FileResponse(os.path.join(WEB_DIR, "manifest.json"))

@app.get("/sw.js")
def serve_sw():
    return FileResponse(os.path.join(WEB_DIR, "sw.js"), media_type="application/javascript")

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("app:app", host="0.0.0.0", port=PORT, reload=False)
