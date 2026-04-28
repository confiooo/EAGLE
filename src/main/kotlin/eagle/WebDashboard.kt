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
    .sub { color: var(--muted); font-size: 0.875rem; margin-bottom: 1.25rem; }
    #status { font-size: 0.8rem; color: var(--muted); margin-bottom: 1rem; }
    #status.live { color: var(--bull); }
    .list { display: flex; flex-direction: column; gap: 0.65rem; max-width: 52rem; }
    article {
      background: var(--panel);
      border: 1px solid var(--border);
      border-radius: 8px;
      padding: 0.85rem 1rem;
    }
    article.bull { border-left: 3px solid var(--bull); }
    article.bear { border-left: 3px solid var(--bear); }
    .meta {
      font-size: 0.75rem;
      color: var(--muted);
      margin-bottom: 0.5rem;
    }
    .body { font-size: 0.9rem; }
    .body b { color: var(--text); }
  </style>
</head>
<body>
  <h1>Eagle alerts</h1>
  <p class="sub">Local dashboard — new crosses appear below as they fire.</p>
  <p id="status">Connecting…</p>
  <div id="alerts" class="list"></div>
  <script>
    const el = document.getElementById('alerts');
    const st = document.getElementById('status');

    function fmtTime(ms) {
      const d = new Date(ms);
      return d.toLocaleString(undefined, {
        dateStyle: 'short', timeStyle: 'medium'
      });
    }

    function card(a) {
      const art = document.createElement('article');
      art.className = a.bullish ? 'bull' : 'bear';
      const side = a.bullish ? 'Bullish' : 'Bearish';
      const meta = document.createElement('div');
      meta.className = 'meta';
      meta.textContent = '#' + a.id + ' · ' + fmtTime(a.timestampMillis) + ' · ' + a.instanceName +
        ' · ' + a.symbol + ' ' + a.timeframe + ' · ' + side + ' · EMA' + a.emaFast + '/EMA' + a.emaSlow;
      const body = document.createElement('div');
      body.className = 'body';
      body.innerHTML = a.messageHtml;
      art.appendChild(meta);
      art.appendChild(body);
      return art;
    }

    function prepend(a) {
      el.insertBefore(card(a), el.firstChild);
    }

    fetch('/api/alerts').then(r => r.json()).then(rows => {
      rows.forEach(prepend);
    }).catch(() => { st.textContent = 'Could not load history'; });

    const es = new EventSource('/api/events');
    es.onopen = () => {
      st.textContent = 'Live (SSE connected)';
      st.className = 'live';
    };
    es.onmessage = (ev) => {
      try {
        prepend(JSON.parse(ev.data));
      } catch (e) {}
    };
    es.onerror = () => {
      st.textContent = 'SSE disconnected — refresh the page';
      st.className = '';
    };
  </script>
</body>
</html>
""".trimIndent()
