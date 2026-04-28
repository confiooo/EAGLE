# Eagle

Kotlin bot that streams top Binance **USDT spot pairs** over **WebSocket**, detects **EMA crosses on closed candles**, and sends **Telegram alerts**.

A single process opens **one socket per timeframe** and distributes data across multiple **instances** (different chats, EMA strategies, languages, etc).

---

## Example alert

![telegram alert](docs/example.png)

---

## Why Eagle?

- Uses **closed candles only** → no repainting / false signals
- **Lightweight architecture** → shared WebSockets, minimal overhead
- **Multi-instance support** → run multiple strategies in one process
- No external services required → just **JDK + Telegram bot**
- Built for reliability → **auto reconnect with backoff**

---

## Features

- Ranked watchlist by **24h volume** (configurable cap)
- Filters leveraged tokens (e.g. `BTCUP`, `BTCDOWN`)
- Supports multiple instances:
    - Different Telegram chats
    - Different EMA pairs
    - Different timeframes
    - Different languages (`en`, `es`, `zh`)
- Automatic WebSocket reconnect
- Telegram messages formatted with **HTML**

---

## Requirements

- **JDK 17+**

---

## Quick start

```bash
git clone <https://github.com/confiooo/EAGLE>
cd eagle

cp .env.example .env
cp config.example.json config.json

./gradlew run
```
