(function () {
    // ---------- 辅助工具 ----------
    function headerJson(v) {
        if (typeof v !== 'string') {
            try { return JSON.stringify(v || {}); } catch (e) { return '{}'; }
        }
        return v;
    }

    function parseJson(v, d) {
        try { return JSON.parse(v); } catch (e) { return d; }
    }

    // ---------- 基础桥接 ----------
    window.ku9 = {
        // -------- 网络请求 --------
        get: function (u, h) { return ku9Bridge.get(String(u), headerJson(h)); },
        post: function (u, b, h) { return ku9Bridge.post(String(u), b == null ? '' : String(b), headerJson(h)); },
        request: function (u, m, h, b, f) {
            var v = ku9Bridge.request(String(u), m == null ? 'GET' : String(m), headerJson(h), b == null ? '' : String(b), f !== false);
            return parseJson(v, {});
        },
        getHeaders: function (u, h, redirect, method, body) {
            var v = ku9Bridge.getHeaders(String(u), headerJson(h), redirect !== false, method == null ? 'GET' : String(method), body == null ? '' : String(body));
            return parseJson(v, {});
        },

        // -------- 缓存 --------
        getCache: function (k) { return ku9Bridge.getCache(String(k)); },
        setCache: function (k, v, t) { ku9Bridge.setCache(String(k), v == null ? '' : String(v), Number(t) || 0); },

        // -------- URL 解析 --------
        uri: function (url) {
            var u = String(url);
            var a = document.createElement('a');
            a.href = u;
            var result = {
                Scheme: a.protocol.replace(':', ''),
                Host: a.hostname,
                Port: a.port ? parseInt(a.port, 10) : (a.protocol === 'https:' ? 443 : 80),
                Path: a.pathname.substring(0, a.pathname.lastIndexOf('/') + 1),
                FullPath: a.pathname,
                Query: a.search,
                Fragment: a.hash,
                UserInfo: a.username + (a.password ? ':' + a.password : ''),
                Params: {}
            };
            // 解析查询参数
            var search = a.search;
            if (search && search.length > 1) {
                var parts = search.substring(1).split('&');
                for (var i = 0; i < parts.length; i++) {
                    var kv = parts[i].split('=');
                    if (kv[0]) {
                        result.Params[decodeURIComponent(kv[0])] = decodeURIComponent(kv[1] || '');
                    }
                }
            }
            return result; 
        },
        getQuery: function (u, n) {
            try {
                var ps = (String(u).split('?')[1] || '').split('&'), r = {}, i, k;
                for (i = 0; i < ps.length; i++) {
                    k = ps[i].split('=');
                    if (k[0]) r[decodeURIComponent(k[0])] = decodeURIComponent(k[1] || '');
                }
                return n === undefined ? r : (r[String(n)] || '');
            } catch (e) {
                return n === undefined ? {} : '';
            }
        },

        // -------- 加密与哈希 --------
        md5: function (v) { return ku9Bridge.md5(String(v)); },
        sha1: function (v) { return ku9Bridge.sha1(String(v)); },
        sha256: function (v) { return ku9Bridge.sha256(String(v)); },
        sha512: function (v) { return ku9Bridge.sha512(String(v)); },

        opensslEncrypt: function (data, enType, key, outputType, iv) {
            return ku9Bridge.opensslEncrypt(String(data), String(enType), String(key), Number(outputType) || 0, iv == null ? '' : String(iv));
        },
        opensslDecrypt: function (data, deType, key, inputType, iv) {
            return ku9Bridge.opensslDecrypt(String(data), String(deType), String(key), Number(inputType) || 0, iv == null ? '' : String(iv));
        },

        rc4Encrypt: function (data, key, inputFormat, outputFormat, charset) {
            return ku9Bridge.rc4Encrypt(String(data), String(key), Number(inputFormat) || 0, Number(outputFormat) || 0, charset == null ? 'UTF-8' : String(charset));
        },
        rc4Decrypt: function (data, key, inputFormat, outputFormat, charset) {
            return ku9Bridge.rc4Decrypt(String(data), String(key), Number(inputFormat) || 0, Number(outputFormat) || 0, charset == null ? 'UTF-8' : String(charset));
        },

        // -------- Base64 编解码 --------
        encodeBase64: function (v) { return ku9Bridge.encodeBase64(String(v)); },
        decodeBase64: function (v) { return ku9Bridge.decodeBase64(String(v)); },

        // -------- 类型判断 --------
        isBase64: function (v) { return ku9Bridge.isBase64(String(v)); },
        isJsonObject: function (v) { return ku9Bridge.isJsonObject(String(v)); },
        isJsonArray: function (v) { return ku9Bridge.isJsonArray(String(v)); },

        // -------- 时间处理 --------
        toTimestamp: function (dateStr, inputFormat, timezone) {
            return Number(ku9Bridge.toTimestamp(String(dateStr), String(inputFormat), timezone == null ? '' : String(timezone)));
        },
        toDate: function (timestamp, outputFormat, timezone) {
            return ku9Bridge.toDate(Number(timestamp), String(outputFormat), timezone == null ? '' : String(timezone));
        },
        formatDateTime: function (dateStr, inputFormat, outputFormat, daysOffset, inputTimezone, outputTimezone) {
            return ku9Bridge.formatDateTime(
                String(dateStr),
                String(inputFormat),
                String(outputFormat),
                Number(daysOffset) || 0,
                inputTimezone == null ? '' : String(inputTimezone),
                outputTimezone == null ? '' : String(outputTimezone)
            );
        },

        // -------- WebSocket（占位/桥接） --------
        websocket: function () {
            var bridge = ku9Bridge.websocket ? ku9Bridge.websocket() : null;
            var wsObj = {
                connect: function (url, headers, name) {
                    if (bridge && bridge.connect) {
                        return bridge.connect(String(url), headerJson(headers), name == null ? '' : String(name));
                    }
                    // 降级：返回一个空对象
                    return { send: function () {}, close: function () {}, task: function () {}, removetask: function () {} };
                },
                get: function (name) {
                    if (bridge && bridge.get) return bridge.get(String(name));
                    return null;
                },
                has: function (name) {
                    if (bridge && bridge.has) return bridge.has(String(name));
                    return false;
                }
            };
            return wsObj;
        },

        // -------- 日志 --------
        log: function (v) { ku9Bridge.log(String(v)); }
    };

    // 将 console.log 映射到 ku9.log
    if (typeof console === 'undefined') window.console = {};
    console.log = function () {
        var args = Array.prototype.slice.call(arguments);
        ku9Bridge.log(args.map(function (a) { return a === undefined ? 'undefined' : String(a); }).join(' '));
    };

    // -------- 简易 require（用于加载 crypto、jsencrypt 等） --------
    if (typeof require === 'undefined') {
        window.require = function (moduleName) {
            // 如果已经挂载在全局，直接返回
            if (moduleName === 'crypto') {
                return window.CryptoJS || null;
            }
            if (moduleName === 'jsencrypt') {
                return window.JSEncrypt || null;
            }
            // 其他模块可自行扩展
            return null;
        };
    }

    // -------- 兼容旧浏览器 --------
    if (!String.prototype.startsWith) {
        Object.defineProperty(String.prototype, 'startsWith', { value: function (s) { return this.indexOf(String(s)) === 0; } });
    }
    if (!String.prototype.endsWith) {
        Object.defineProperty(String.prototype, 'endsWith', { value: function (s) { var t = String(s); return this.lastIndexOf(t) === this.length - t.length; } });
    }
    if (!String.prototype.includes) {
        Object.defineProperty(String.prototype, 'includes', { value: function (s) { return this.indexOf(String(s)) !== -1; } });
    }
    if (!Array.prototype.includes) {
        Object.defineProperty(Array.prototype, 'includes', { value: function (v) { return this.indexOf(v) !== -1; } });
    }

    // -------- 主流程 --------
    function done(v) {
        if (v === undefined || v === null) v = '';
        try { ku9Bridge.complete(JSON.stringify(v)); } catch (e) { ku9Bridge.fail(String(e && e.stack ? e.stack : e)); }
    }

    function fail(e) { ku9Bridge.fail(String(e && e.stack ? e.stack : e)); }

    try {
        /*__KU9_SCRIPT__*/
        if (typeof main !== 'function') { throw new Error('script no main(item)'); }
        var __item = /*__KU9_ITEM__*/;
        var __result = main(__item);
        if (__result && typeof __result.then === 'function') {
            __result.then(done).catch(fail);
        } else {
            done(__result);
        }
    } catch (e) {
        fail(e);
    }
})();