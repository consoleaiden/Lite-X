/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.thunderdog.challegram.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.json.JSONArray;
import org.json.JSONObject;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.unsorted.Settings;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import me.vkryl.core.StringUtils;

/**
 * 在线代理（MTProto 订阅源）的拉取、缓存与本地测速。
 *
 * <p>数据来自 consoleaiden/telegram-mtproto-proxy-list 的 top100.json，结构为
 * <pre>{ "generated_at": "...", "proxies": [ { "server", "port", "secret", "rank", "latency_ms", ... } ] }</pre>
 *
 * <p>设计要点：
 * <ul>
 *   <li>该列表<strong>不写入</strong> {@link Settings} 的代理列表：它是可被覆盖刷新的临时数据，
 *       只有用户主动「添加到常用」时，才会通过 Settings.addOrUpdateProxy 进入常规代理列表。</li>
 *   <li>测速不调用 {@code Tdlib.pingProxy}：后者内部的 {@code TdApi.AddProxy} 会把每个被测试的
 *       代理注册进 TDLib 的持久化代理表，100 条会污染本地配置，且重复拉取后再测可能因
 *       「代理已存在」返回错误而被误判为不可用。这里用 TCP 握手 RTT 衡量延迟/连通性，
 *       结果仍复用页面既有的 pingMs 语义（PROXY_TIME_LOADING / >=0 / PROXY_TIME_EMPTY）与文案颜色。</li>
 *   <li>所有可变状态只在主线程被读写（拉取在后台线程，结果通过 {@link #applyResult(FetchResult)} 回主线程落地）。</li>
 * </ul>
 */
public final class OnlineProxies {
  private OnlineProxies () { }

/** top100 精选列表：对象数组，含 rank，条数少但质量高。 */
  public static final int SOURCE_TOP = 0;
  /** 全量列表：行式数组（列由顶层 fields 描述），约 1000 条，用于 top100 中没有优质代理时兜底。 */
  public static final int SOURCE_ALL = 1;

  public static final String SOURCE_URL_TOP =
    "https://raw.githubusercontent.com/consoleaiden/telegram-mtproto-proxy-list/main/top100.json";
  public static final String SOURCE_URL_ALL =
    "https://raw.githubusercontent.com/consoleaiden/telegram-mtproto-proxy-list/main/proxies.json";

  /**
   * 每个数据源按顺序尝试的地址：raw.githubusercontent.com 在部分网络（尤其国内直连）下不可达，
   * 因此附带 jsDelivr 的 GitHub 镜像作为兜底，任意一个成功即停止。
   */
  private static final String[][] SOURCE_URLS = {
    {
      SOURCE_URL_TOP,
      "https://cdn.jsdelivr.net/gh/consoleaiden/telegram-mtproto-proxy-list@main/top100.json",
      "https://fastly.jsdelivr.net/gh/consoleaiden/telegram-mtproto-proxy-list@main/top100.json"
    },
    {
      SOURCE_URL_ALL,
      "https://cdn.jsdelivr.net/gh/consoleaiden/telegram-mtproto-proxy-list@main/proxies.json",
      "https://fastly.jsdelivr.net/gh/consoleaiden/telegram-mtproto-proxy-list@main/proxies.json"
    }
  };

  public static final int STATE_IDLE = 0;
  public static final int STATE_LOADING = 1;
  public static final int STATE_READY = 2;
  public static final int STATE_EMPTY = 3;
  public static final int STATE_ERROR = 4;

  /** 单条测速的 TCP 连接超时（毫秒）。DNS 解析耗时不受此限制。 */
  public static final int DEFAULT_TIMEOUT_MS = 4000;
  /** 默认并发数：兼顾速度与低端机的调度压力。 */
  public static final int DEFAULT_CONCURRENCY = 6;

  public static final class Entry {
    public final TdApi.Proxy proxy;
    /** server:port:secret 去重键（server 已小写） */
    public final String key;
    public final int rank;
    /** 订阅源服务器侧测得的延迟，-1 表示未知。仅作参考，不代表本机情况。 */
    public final long serverLatencyMs;
    public final String mode;

    /** 与 Settings.Proxy 保持同一套取值语义 */
    public long pingMs = Settings.PROXY_TIME_UNSET;
    public @Nullable String pingError;

    Entry (TdApi.Proxy proxy, String key, int rank, long serverLatencyMs, String mode) {
      this.proxy = proxy;
      this.key = key;
      this.rank = rank;
      this.serverLatencyMs = serverLatencyMs;
      this.mode = mode;
    }

    public String getAddress () {
      return proxy.server + ":" + proxy.port;
    }

    public boolean isTested () {
      return pingMs >= 0 || pingMs == Settings.PROXY_TIME_EMPTY;
    }

    public boolean isAlive () {
      return pingMs >= 0;
    }
  }

  /** 后台线程产出的拉取结果，不含任何共享状态。 */
  public static final class FetchResult {
    /** null 表示拉取失败，此时不会覆盖上一次成功的数据 */
    public final @Nullable List<Entry> entries;
    public final @Nullable String generatedAt;
    public final int rawCount;
    public final @Nullable String error;

    FetchResult (@Nullable List<Entry> entries, @Nullable String generatedAt, int rawCount, @Nullable String error) {
      this.entries = entries;
      this.generatedAt = generatedAt;
      this.rawCount = rawCount;
      this.error = error;
    }
  }

  // 唯一一份进程内缓存：离开页面再回来仍然是同一份数据，重启后清空
  private static int state = STATE_IDLE;
  private static @Nullable String error;
  private static @Nullable String generatedAt;
  private static final List<Entry> entries = new ArrayList<>();

  public static int getState () {
    return state;
  }

  public static @Nullable String getError () {
    return error;
  }

  public static @Nullable String getGeneratedAt () {
    return generatedAt;
  }

  public static @NonNull List<Entry> getEntries () {
    return entries;
  }

  public static boolean isLoading () {
    return state == STATE_LOADING;
  }

  public static void setLoading () {
    state = STATE_LOADING;
    error = null;
  }

  public static boolean removeEntry (@NonNull Entry entry) {
    return entries.remove(entry);
  }

  /**
   * 把后台拉取结果落地到缓存。必须在主线程调用。
   * 落地后由调用方根据 {@link #getState()} / {@link #getError()} 决定提示文案。
   */
  public static void applyResult (@NonNull FetchResult result) {
    if (result.entries == null) {
      state = STATE_ERROR;
      error = result.error;
      // 失败时保留上一次成功的数据，用户仍可继续使用/测试旧列表
      return;
    }
    generatedAt = result.generatedAt;
    entries.clear();
    entries.addAll(result.entries);
    if (entries.isEmpty()) {
      state = STATE_EMPTY;
      error = result.error;
      return;
    }
    state = STATE_READY;
    error = null;
  }

  // region 拉取

  /**
   * 同步拉取并解析。**必须在后台线程调用**，不修改任何共享状态。
   */
  public static @NonNull FetchResult fetchSync () {
    return fetchSync(SOURCE_TOP);
  }

  /**
   * 同步拉取并解析。**必须在后台线程调用**，不修改任何共享状态。
   *
   * @param source {@link #SOURCE_TOP} 拉 top100.json，{@link #SOURCE_ALL} 拉 proxies.json
   */
  public static @NonNull FetchResult fetchSync (int source) {
    final String[] urls = source == SOURCE_ALL ? SOURCE_URLS[1] : SOURCE_URLS[0];
    String lastError = null;
    for (int i = 0; i < urls.length; i++) {
      final FetchResult result = fetchFrom(urls[i]);
      if (result.entries != null) {
        if (i > 0) {
          Log.i("Online proxy list fetched from mirror %d", i);
        }
        return result;
      }
      lastError = result.error;
      Log.i("Online proxy source %d failed: %s", i, lastError);
    }
    return new FetchResult(null, null, 0, lastError != null ? lastError : "未知错误");
  }

  private static @NonNull FetchResult fetchFrom (@NonNull String url) {
    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection) new URL(url).openConnection();
      connection.setConnectTimeout(10000);
      connection.setReadTimeout(15000);
      connection.setRequestProperty("Accept", "application/json");
      connection.setRequestProperty("User-Agent", "Lite-X");
      connection.setUseCaches(false);
      connection.setInstanceFollowRedirects(true);
      final int responseCode = connection.getResponseCode();
      if (responseCode < 200 || responseCode >= 300) {
        Log.i("Online proxy list responded with %d", responseCode);
        return new FetchResult(null, null, 0, "HTTP " + responseCode);
      }
      final String body;
      InputStream stream = connection.getInputStream();
      try {
        body = readAll(stream);
      } finally {
        stream.close();
      }
      return parse(body);
    } catch (Throwable t) {
      Log.i("Unable to fetch online proxy list", t);
      return new FetchResult(null, null, 0, describeError(t));
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }
  }

  /**
   * 兼容两种订阅文件格式：
   * <ul>
   *   <li>top100.json：{@code proxies} 是对象数组，字段按名字读取，带 rank</li>
   *   <li>proxies.json：{@code proxies} 是行式数组，列的含义由顶层 {@code fields} 描述，没有 rank</li>
   * </ul>
   */
  private static @NonNull FetchResult parse (@NonNull String body) {
    final JSONObject root;
    final JSONArray array;
    try {
      root = new JSONObject(body);
      array = root.optJSONArray("proxies");
    } catch (Throwable t) {
      return new FetchResult(null, null, 0, "返回内容不是合法 JSON");
    }
    if (array == null) {
      return new FetchResult(null, null, 0, "格式异常：缺少 proxies 数组");
    }

    // 行式数据的列索引（对象行不需要）；缺失的字段回退到已知默认顺序
    final int serverColumn = columnIndex(root, "server", 0);
    final int portColumn = columnIndex(root, "port", 1);
    final int secretColumn = columnIndex(root, "secret", 2);
    final int latencyColumn = columnIndex(root, "latency_ms", 6);

    final String generatedAt = root.optString("generated_at", null);
    final List<Entry> parsed = new ArrayList<>(array.length());
    final Set<String> keys = new HashSet<>(array.length());
    int skipped = 0;
    boolean hasRank = false;
    for (int i = 0; i < array.length(); i++) {
      Object rawRow = array.opt(i);
      final String server;
      final int port;
      final String secret;
      final long serverLatency;
      final int rank;
      final String mode;
      if (rawRow instanceof JSONObject) {
        JSONObject item = (JSONObject) rawRow;
        server = trimToEmpty(item.optString("server", ""));
        port = item.optInt("port", 0);
        secret = trimToEmpty(item.optString("secret", ""));
        serverLatency = item.has("latency_ms") && !item.isNull("latency_ms")
          ? Math.round(item.optDouble("latency_ms", -1d)) : -1;
        rank = item.has("rank") ? item.optInt("rank", parsed.size() + 1) : parsed.size() + 1;
        if (item.has("rank")) {
          hasRank = true;
        }
        mode = item.optString("mode", null);
      } else if (rawRow instanceof JSONArray) {
        JSONArray row = (JSONArray) rawRow;
        server = trimToEmpty(row.optString(serverColumn, ""));
        port = row.optInt(portColumn, 0);
        secret = trimToEmpty(row.optString(secretColumn, ""));
        double latency = row.optDouble(latencyColumn, -1d);
        serverLatency = !Double.isNaN(latency) ? Math.round(latency) : -1;
        rank = parsed.size() + 1;
        mode = null;
      } else {
        skipped++;
        continue;
      }
      if (server.isEmpty() || port <= 0 || port > 65535 || secret.isEmpty()) {
        skipped++;
        continue;
      }
      final String key = server.toLowerCase(Locale.US) + ":" + port + ":" + secret;
      if (!keys.add(key)) {
        // 源数据内部重复
        continue;
      }
      final TdApi.Proxy proxy = new TdApi.Proxy(server, port, new TdApi.ProxyTypeMtproto(secret));
      parsed.add(new Entry(proxy, key, rank, serverLatency, mode));
    }

    if (parsed.isEmpty()) {
      final String reason = skipped > 0 ?
        "返回了 " + array.length() + " 条数据，但没有可用的 MTProto 代理（格式异常）" :
        "返回了 0 条代理（数据为空）";
      return new FetchResult(Collections.emptyList(), generatedAt, array.length(), reason);
    }
    if (hasRank) {
      // 保留源站排名顺序，测速前按 rank 展示
      Collections.sort(parsed, (a, b) -> Integer.compare(a.rank, b.rank));
    } else {
      // 没有 rank 的源（全量列表）按源站实测延迟展示，未知延迟垫底
      Collections.sort(parsed, (a, b) -> {
        final long la = a.serverLatencyMs >= 0 ? a.serverLatencyMs : Long.MAX_VALUE;
        final long lb = b.serverLatencyMs >= 0 ? b.serverLatencyMs : Long.MAX_VALUE;
        return la != lb ? Long.compare(la, lb) : Integer.compare(a.rank, b.rank);
      });
    }
    return new FetchResult(parsed, generatedAt, array.length(), null);
  }

  /** 从顶层 fields 描述里取某一列的下标，找不到时用兜底顺序，并容忍 columns 顺序变化。 */
  private static int columnIndex (@NonNull JSONObject root, @NonNull String name, int fallbackIndex) {
    JSONArray fields = root.optJSONArray("fields");
    if (fields != null) {
      for (int i = 0; i < fields.length(); i++) {
        if (name.equals(fields.optString(i, null))) {
          return i;
        }
      }
    }
    return fallbackIndex;
  }

  private static @NonNull String readAll (@NonNull InputStream inputStream) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream(64 * 1024);
    byte[] buffer = new byte[16 * 1024];
    int read;
    while ((read = inputStream.read(buffer)) != -1) {
      output.write(buffer, 0, read);
    }
    return output.toString("UTF-8");
  }

  private static String describeError (@NonNull Throwable t) {
    String message = t.getMessage();
    String type = t.getClass().getSimpleName();
    return StringUtils.isEmpty(message) ? type : type + ": " + message;
  }

  private static @NonNull String trimToEmpty (@Nullable String value) {
    return value == null ? "" : value.trim();
  }

  // endregion

  // region 测速

  /** 一次批量测速会话，可中断。 */
  public static final class PingTask {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicInteger done = new AtomicInteger();
    private final int total;
    private final ExecutorService executor;

    private PingTask (int total, int concurrency) {
      this.total = total;
      this.executor = Executors.newFixedThreadPool(concurrency);
    }

    public int getProgress () {
      return done.get();
    }

    public int getTotal () {
      return total;
    }

    public boolean isCancelled () {
      return cancelled.get();
    }

    public boolean isFinished () {
      return done.get() >= total;
    }

    void cancel () {
      cancelled.set(true);
      executor.shutdownNow();
    }
  }

  private static @Nullable PingTask activeTask;

  /**
   * 批量测速：所有条目先置为「检查中」，再以固定并发逐个 TCP 探测。
   *
   * @param onProgress 每完成一条回调一次（主线程）
   * @param onDone     全部完成回调（主线程）；被中断时不会回调
   */
  public static @NonNull PingTask startPing (@NonNull List<Entry> list, int concurrency, int timeoutMs,
                                             @NonNull Runnable onProgress, @NonNull Runnable onDone) {
    cancelPing();
    final PingTask task = new PingTask(list.size(), Math.max(1, concurrency));
    activeTask = task;
    for (Entry entry : list) {
      entry.pingMs = Settings.PROXY_TIME_LOADING;
      entry.pingError = null;
    }
    for (final Entry entry : list) {
      task.executor.execute(() -> {
        if (task.cancelled.get()) {
          return;
        }
        try {
          measure(entry, timeoutMs);
        } finally {
          task.done.incrementAndGet();
          UI.post(() -> {
            if (task.cancelled.get()) {
              return;
            }
            onProgress.run();
            if (task.isFinished()) {
              task.cancel();
              onDone.run();
            }
          });
        }
      });
    }
    task.executor.shutdown();
    return task;
  }

  /** 中断正在进行的测速。 */
  public static void cancelPing () {
    final PingTask task = activeTask;
    activeTask = null;
    if (task != null) {
      task.cancel();
    }
  }

  /** 中断后把仍处于「检查中」的条目还原成「未测试」。 */
  public static void resetLoadingState (@NonNull List<Entry> list) {
    for (Entry entry : list) {
      if (entry.pingMs == Settings.PROXY_TIME_LOADING) {
        entry.pingMs = Settings.PROXY_TIME_UNSET;
        entry.pingError = null;
      }
    }
  }

  /**
   * TCP 握手测速：只做 connect，不发业务数据。
   * 对 MTProxy 而言这等价于「端口可达 + 网络往返」，是设备侧最能真实反映快慢的指标。
   */
  private static void measure (@NonNull Entry entry, int timeoutMs) {
    final long start = android.os.SystemClock.uptimeMillis();
    Socket socket = new Socket();
    try {
      // 先解析再 connect：Socket.connect 不接受未解析地址，DNS 耗时也计入延迟
      InetAddress address = InetAddress.getByName(entry.proxy.server);
      socket.connect(new InetSocketAddress(address, entry.proxy.port), timeoutMs);
      entry.pingMs = android.os.SystemClock.uptimeMillis() - start;
      entry.pingError = null;
    } catch (Throwable t) {
      entry.pingMs = Settings.PROXY_TIME_EMPTY;
      entry.pingError = describeError(t);
    } finally {
      try {
        socket.close();
      } catch (Throwable ignored) { }
    }
  }

  /** 按本机测速结果排序：可用的按延迟升序，未测/失败的按订阅源排名垫底。 */
  public static void sortByPing (@NonNull List<Entry> list) {
    Collections.sort(list, (a, b) -> {
      final long pa = a.pingMs >= 0 ? a.pingMs : Long.MAX_VALUE;
      final long pb = b.pingMs >= 0 ? b.pingMs : Long.MAX_VALUE;
      if (pa != pb) {
        return Long.compare(pa, pb);
      }
      final long la = a.serverLatencyMs >= 0 ? a.serverLatencyMs : Long.MAX_VALUE;
      final long lb = b.serverLatencyMs >= 0 ? b.serverLatencyMs : Long.MAX_VALUE;
      if (la != lb) {
        return Long.compare(la, lb);
      }
      return Integer.compare(a.rank, b.rank);
    });
  }

  // endregion

  /** 判断两个 TdApi.Proxy 是否指向同一个代理（用于「已添加到常用」标记）。 */
  public static boolean isSameProxy (@Nullable TdApi.Proxy a, @Nullable TdApi.Proxy b) {
    if (a == null || b == null) {
      return false;
    }
    if (a.port != b.port || !StringUtils.equalsOrBothEmpty(a.server, b.server)) {
      return false;
    }
    return isSameProxyType(a.type, b.type);
  }

  private static boolean isSameProxyType (@Nullable TdApi.ProxyType a, @Nullable TdApi.ProxyType b) {
    if (a == null || b == null || a.getConstructor() != b.getConstructor()) {
      return false;
    }
    if (a instanceof TdApi.ProxyTypeMtproto && b instanceof TdApi.ProxyTypeMtproto) {
      return StringUtils.equalsOrBothEmpty(
        ((TdApi.ProxyTypeMtproto) a).secret,
        ((TdApi.ProxyTypeMtproto) b).secret
      );
    }
    return true;
  }

  /** 生成标准的 t.me 代理链接（不依赖 TDLib 往返）。 */
  public static @NonNull String buildProxyLink (@NonNull TdApi.Proxy proxy) {
    final String secret;
    if (proxy.type instanceof TdApi.ProxyTypeMtproto) {
      secret = ((TdApi.ProxyTypeMtproto) proxy.type).secret;
    } else {
      secret = "";
    }
    return "https://t.me/proxy?server=" + proxy.server + "&port=" + proxy.port + "&secret=" + secret;
  }

  // region 优质代理持久化

  /** 每次「拉取 + 全部测试」后保留的最快代理条数上限。 */
  public static final int BEST_LIMIT = 20;

  private static final String KEY_BEST_PROXIES = "online_best_proxies";

  /** 已持久化的优质代理：写入 Settings 的 key-value 存储（LevelDB），进程重启后仍可读。 */
  private static final List<SavedEntry> bestEntries = new ArrayList<>();
  private static long bestUpdatedAt;
  private static boolean bestLoaded;

  public static final class SavedEntry {
    public final TdApi.Proxy proxy;
    /** 本机测得的 TCP 握手延迟（毫秒） */
    public final long pingMs;
    /** 该次测试完成的时刻（System.currentTimeMillis） */
    public final long testedAtMs;

    SavedEntry (@NonNull TdApi.Proxy proxy, long pingMs, long testedAtMs) {
      this.proxy = proxy;
      this.pingMs = pingMs;
      this.testedAtMs = testedAtMs;
    }

    public @NonNull String getAddress () {
      return proxy.server + ":" + proxy.port;
    }
  }

  public static @NonNull List<SavedEntry> getBestEntries () {
    ensureBestLoaded();
    return bestEntries;
  }

  public static long getBestUpdatedAt () {
    ensureBestLoaded();
    return bestUpdatedAt;
  }

  public static boolean removeBestEntry (@NonNull SavedEntry entry) {
    ensureBestLoaded();
    if (!bestEntries.remove(entry)) {
      return false;
    }
    persistBest();
    return true;
  }

  /**
   * 从一次完整的测速结果里挑出最快的前 {@link #BEST_LIMIT} 条持久化。必须在主线程调用。
   *
   * @return 实际写入的条数；**返回 0 表示本次没有连通的代理，旧数据被完整保留**（不会被清空）
   */
  public static int saveBestFrom (@NonNull List<Entry> tested) {
    ensureBestLoaded();
    final List<Entry> alive = new ArrayList<>();
    for (Entry entry : tested) {
      if (entry.isAlive()) {
        alive.add(entry);
      }
    }
    if (alive.isEmpty()) {
      return 0;
    }
    Collections.sort(alive, (a, b) -> Long.compare(a.pingMs, b.pingMs));
    final int count = Math.min(BEST_LIMIT, alive.size());
    final long now = System.currentTimeMillis();
    final List<SavedEntry> next = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      final Entry entry = alive.get(i);
      next.add(new SavedEntry(entry.proxy, entry.pingMs, now));
    }
    // 整体替换：只有走到这里（本次有有效结果）才覆盖上一批
    bestEntries.clear();
    bestEntries.addAll(next);
    bestUpdatedAt = now;
    persistBest();
    return count;
  }

  private static void persistBest () {
    try {
      final JSONArray items = new JSONArray();
      for (SavedEntry entry : bestEntries) {
        final String secret = entry.proxy.type instanceof TdApi.ProxyTypeMtproto
          ? ((TdApi.ProxyTypeMtproto) entry.proxy.type).secret : "";
        JSONObject item = new JSONObject();
        item.put("server", entry.proxy.server);
        item.put("port", entry.proxy.port);
        item.put("secret", secret);
        item.put("ping", entry.pingMs);
        item.put("at", entry.testedAtMs);
        items.put(item);
      }
      final JSONObject root = new JSONObject();
      root.put("updated_at", bestUpdatedAt);
      root.put("items", items);
      Settings.instance().putString(KEY_BEST_PROXIES, root.toString());
    } catch (Throwable t) {
      Log.i("Unable to persist best proxies", t);
    }
  }

  private static void ensureBestLoaded () {
    if (bestLoaded) {
      return;
    }
    bestLoaded = true;
    try {
      final String json = Settings.instance().getString(KEY_BEST_PROXIES, null);
      if (StringUtils.isEmpty(json)) {
        return;
      }
      final JSONObject root = new JSONObject(json);
      bestUpdatedAt = root.optLong("updated_at", 0);
      final JSONArray items = root.optJSONArray("items");
      if (items == null) {
        return;
      }
      for (int i = 0; i < items.length(); i++) {
        JSONObject item = items.optJSONObject(i);
        if (item == null) {
          continue;
        }
        final String server = trimToEmpty(item.optString("server", ""));
        final int port = item.optInt("port", 0);
        final String secret = trimToEmpty(item.optString("secret", ""));
        if (server.isEmpty() || port <= 0 || port > 65535 || secret.isEmpty()) {
          continue;
        }
        final TdApi.Proxy proxy = new TdApi.Proxy(server, port, new TdApi.ProxyTypeMtproto(secret));
        bestEntries.add(new SavedEntry(proxy, item.optLong("ping", Settings.PROXY_TIME_UNSET), item.optLong("at", 0)));
      }
    } catch (Throwable t) {
      Log.i("Unable to restore best proxies", t);
    }
  }

  // endregion
}
