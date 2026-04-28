package eagle

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

private val webLog = LoggerFactory.getLogger("WebDashboard")

class AlertHub(private val maxAlerts: Int) {
    private val lock = Mutex()
    private val deque = ArrayDeque<DashboardAlert>()
    private val flow = MutableSharedFlow<DashboardAlert>(extraBufferCapacity = 64)
    private val nextId = AtomicLong(1)

    val alerts = flow.asSharedFlow()

    suspend fun publish(instance: InstanceConfig, meta: AlertMeta, messageHtml: String) {
        val alert = DashboardAlert(
            id = nextId.getAndIncrement(),
            timestampMillis = System.currentTimeMillis(),
            instanceName = instance.name,
            symbol = meta.symbol,
            timeframe = meta.timeframe,
            bullish = meta.bullish,
            emaFast = meta.emaFast,
            emaSlow = meta.emaSlow,
            fastEma = meta.fastEma,
            slowEma = meta.slowEma,
            close = meta.close,
            messageHtml = messageHtml
        )
        lock.withLock {
            deque.addLast(alert)
            while (deque.size > maxAlerts) deque.removeFirst()
        }
        flow.emit(alert)
    }

    suspend fun snapshotOldestFirst(): List<DashboardAlert> =
        lock.withLock { deque.toList() }
}

fun Application.configureDashboard(hub: AlertHub, jsonFmt: Json) {
    install(ContentNegotiation) {
        json(jsonFmt)
    }
    routing {
        get("/") {
            call.respondText(dashboardHtml(), ContentType.Text.Html)
        }
        get("/api/alerts") {
            call.respond(hub.snapshotOldestFirst())
        }
        get("/api/events") {
            // EventSource stays in "connecting" until the response starts. If we only `collect`
            // and no alert fires for a long time, some engines never flush headers — write a
            // comment line immediately so the client gets 200 + event-stream right away.
            call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
            call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                write(": eagle\n\n")
                flush()
                hub.alerts.collect { alert ->
                    val data = jsonFmt.encodeToString(DashboardAlert.serializer(), alert)
                    write("data: $data\n\n")
                    flush()
                }
            }
        }
    }
}

fun startWebDashboard(host: String, port: Int, hub: AlertHub, jsonFmt: Json) {
    val server = embeddedServer(CIO, port = port, host = host, module = { configureDashboard(hub, jsonFmt) })
    server.start(wait = false)
    webLog.info("Web dashboard: http://{}:{}/", host, port)
}

private fun dashboardHtml(): String = """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1"/>
  <title>Eagle — alerts</title>
  <style>
    :root {
      --bg: #0f1419;
      --panel: #1a2332;
      --text: #e7ecf3;
      --muted: #8b9cb3;
      --bull: #3fb950;
      --bear: #f85149;
      --border: #304057;
    }
    * { box-sizing: border-box; }
    body {
      font-family: ui-sans-serif, system-ui, "Segoe UI", Roboto, sans-serif;
      background: var(--bg);
      color: var(--text);
      margin: 0;
      padding: 1rem 1.25rem 2rem;
      line-height: 1.45;
    }
    h1 { font-size: 1.25rem; font-weight: 600; margin: 0 0 0.25rem; }
    .sub { color: var(--muted); font-size: 0.875rem; margin-bottom: 1rem; }
    .toolbar {
      display: flex; flex-wrap: wrap; gap: 0.5rem 1rem; align-items: center;
      margin-bottom: 0.75rem; max-width: 52rem;
    }
    .toolbar button {
      font: inherit; cursor: pointer; padding: 0.35rem 0.75rem; border-radius: 6px;
      border: 1px solid var(--border); background: var(--panel); color: var(--text);
    }
    .toolbar button.active { border-color: var(--bull); color: var(--bull); }
    .toolbar label { font-size: 0.8rem; color: var(--muted); display: inline-flex; align-items: center; gap: 0.35rem; }
    .toolbar input[type="number"] {
      width: 4.2rem; font: inherit; padding: 0.25rem 0.4rem; border-radius: 4px;
      border: 1px solid var(--border); background: var(--bg); color: var(--text);
    }
    #status { font-size: 0.8rem; color: var(--muted); margin-bottom: 0.75rem; }
    #status.live { color: var(--bull); }
    .list-wrap {
      max-width: 52rem; max-height: calc(100vh - 11rem); overflow-y: auto;
      border: 1px solid var(--border); border-radius: 8px; background: var(--bg);
    }
    .list { display: flex; flex-direction: column; gap: 0.35rem; padding: 0.5rem; }
    details.row {
      background: var(--panel);
      border: 1px solid var(--border);
      border-radius: 6px;
      font-size: 0.78rem;
    }
    details.row.bull { border-left: 3px solid var(--bull); }
    details.row.bear { border-left: 3px solid var(--bear); }
    details.row summary {
      cursor: pointer; padding: 0.45rem 0.55rem; list-style: none;
      font-variant-numeric: tabular-nums; user-select: none;
    }
    details.row summary::-webkit-details-marker { display: none; }
    details.row summary::before { content: '▸ '; color: var(--muted); }
    details.row[open] summary::before { content: '▾ '; }
    .row-body {
      padding: 0 0.55rem 0.55rem 1.4rem; font-size: 0.88rem; border-top: 1px solid var(--border);
    }
    .row-body b { color: var(--text); }
  </style>
</head>
<body>
  <h1>Eagle alerts</h1>
  <p class="sub">Compact rows — expand for full message. Live shows only the newest slice; Historical loads the full buffer.</p>
  <div class="toolbar">
    <button type="button" id="btnLive" class="active">Live</button>
    <button type="button" id="btnHist">Historical</button>
    <label>Max rows (live) <input type="number" id="liveCap" min="15" max="300" value="80" step="5"/></label>
  </div>
  <p id="status">Connecting…</p>
  <div class="list-wrap"><div id="alerts" class="list"></div></div>
  <script>
    const el = document.getElementById('alerts');
    const st = document.getElementById('status');
    const btnLive = document.getElementById('btnLive');
    const btnHist = document.getElementById('btnHist');
    const inpCap = document.getElementById('liveCap');

    let mode = 'live';

    function liveCapVal() {
      const n = parseInt(inpCap.value, 10);
      return Number.isFinite(n) ? Math.min(300, Math.max(15, n)) : 80;
    }

    function fmtTime(ms) {
      const d = new Date(ms);
      return d.toLocaleString(undefined, { dateStyle: 'short', timeStyle: 'medium' });
    }

    function compactLine(a) {
      const mark = a.bullish ? '▲' : '▼';
      const side = a.bullish ? 'bull' : 'bear';
      return mark + ' ' + fmtTime(a.timestampMillis) + '  ' + a.symbol + '  ' + a.timeframe + '  ' +
        a.instanceName + '  EMA' + a.emaFast + '/' + a.emaSlow + '  ' + side;
    }

    function rowNode(a) {
      const det = document.createElement('details');
      det.className = 'row ' + (a.bullish ? 'bull' : 'bear');
      const sum = document.createElement('summary');
      sum.textContent = '#' + a.id + '  ' + compactLine(a);
      const body = document.createElement('div');
      body.className = 'row-body';
      body.innerHTML = a.messageHtml;
      det.appendChild(sum);
      det.appendChild(body);
      return det;
    }

    function trimLiveOverCap() {
      if (mode !== 'live') return;
      const cap = liveCapVal();
      while (el.children.length > cap) {
        el.removeChild(el.lastChild);
      }
    }

    function prependLive(a) {
      if (mode !== 'live') return;
      el.insertBefore(rowNode(a), el.firstChild);
      trimLiveOverCap();
    }

    function renderLiveFromRows(rows) {
      el.innerHTML = '';
      const cap = liveCapVal();
      const newestFirst = rows.slice().reverse();
      const slice = newestFirst.slice(0, cap);
      slice.forEach(function (a) { el.appendChild(rowNode(a)); });
    }

    function loadHistorical() {
      st.textContent = 'Loading history…';
      fetch('/api/alerts').then(function (r) { return r.json(); }).then(function (rows) {
        el.innerHTML = '';
        const newestFirst = rows.slice().reverse();
        newestFirst.forEach(function (a) { el.appendChild(rowNode(a)); });
        st.textContent = 'Historical · ' + newestFirst.length + ' alerts (newest at top) · live stream paused';
        st.className = '';
      }).catch(function () { st.textContent = 'Could not load history'; });
    }

    function loadLiveBootstrap() {
      fetch('/api/alerts').then(function (r) { return r.json(); }).then(function (rows) {
        renderLiveFromRows(rows);
        updateLiveStatus();
      }).catch(function () { st.textContent = 'Could not load recent alerts'; });
    }

    function updateLiveStatus() {
      if (mode !== 'live') return;
      const cap = liveCapVal();
      const n = el.children.length;
      const stream = es.readyState === EventSource.OPEN ? 'stream on' : 'stream connecting…';
      st.textContent = 'Live · ' + stream + ' · showing ' + n + ' / max ' + cap;
      st.className = 'live';
    }

    function setMode(next) {
      mode = next;
      btnLive.classList.toggle('active', next === 'live');
      btnHist.classList.toggle('active', next === 'hist');
      inpCap.disabled = next === 'hist';
      if (next === 'hist') { loadHistorical(); return; }
      loadLiveBootstrap();
    }

    btnLive.addEventListener('click', function () { setMode('live'); });
    btnHist.addEventListener('click', function () { setMode('hist'); });
    inpCap.addEventListener('change', function () {
      if (mode !== 'live') return;
      fetch('/api/alerts').then(function (r) { return r.json(); }).then(function (rows) {
        renderLiveFromRows(rows);
        updateLiveStatus();
      });
    });

    const es = new EventSource('/api/events');
    es.onopen = function () { if (mode === 'live') updateLiveStatus(); };
    es.onmessage = function (ev) {
      try {
        const a = JSON.parse(ev.data);
        prependLive(a);
        if (mode === 'live') updateLiveStatus();
      } catch (e) {}
    };
    es.onerror = function () {
      st.textContent = 'SSE disconnected — refresh the page';
      st.className = '';
    };

    loadLiveBootstrap();
  </script>
</body>
</html>
""".trimIndent()
