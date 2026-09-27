/*
 * ZeroTrace privacy shim.
 *
 * Injected at document start into every frame, before any page script runs.
 * Goals:
 *  - Kill APIs that can leak your real IP or location (WebRTC, geolocation).
 *  - Pin values that differ between devices to common, boring values
 *    (CPU cores, memory, language, time zone, touch points, storage quota).
 *  - Add per-session random noise to canvas / WebGL / audio read-backs, so the
 *    "fingerprint" a site computes changes every session and cannot be linked.
 *  - Remove device-info APIs (battery, network type, media devices, GPU model).
 *
 * The SEED placeholder below is replaced with a fresh random number every session.
 */
(function () {
  'use strict';
  var SEED = __SEED__ | 0;
  var patchedWindows = new WeakSet();
  var fakeSource = new WeakMap();

  function hash(x) {
    x = (x ^ 61) ^ (x >>> 16);
    x = (x + (x << 3)) | 0;
    x = x ^ (x >>> 4);
    x = Math.imul(x, 0x27d4eb2d);
    x = x ^ (x >>> 15);
    return x >>> 0;
  }

  // Make a replacement function look native when a site inspects it.
  function nativeLike(fn, name) {
    fakeSource.set(fn, 'function ' + name + '() { [native code] }');
    try { Object.defineProperty(fn, 'name', { value: name.replace(/^get /, ''), configurable: true }); } catch (e) {}
    return fn;
  }

  function patchWindow(w) {
    try {
      if (!w || patchedWindows.has(w)) return;
      patchedWindows.add(w);
    } catch (e) { return; }
    try { patchAll(w); } catch (e) { /* never break the page */ }
  }

  function patchAll(w) {
    var O = w.Object;

    function method(proto, name, make) {
      if (!proto) return;
      var orig = proto[name];
      if (typeof orig !== 'function') return;
      var d = O.getOwnPropertyDescriptor(proto, name) || { writable: true, enumerable: false, configurable: true };
      try {
        O.defineProperty(proto, name, {
          value: nativeLike(make(orig), name),
          writable: d.writable !== false, enumerable: !!d.enumerable, configurable: true
        });
      } catch (e) {}
    }

    function getter(proto, name, get) {
      if (!proto) return;
      var d = O.getOwnPropertyDescriptor(proto, name);
      if (d && !d.configurable) return;
      var origGet = d && d.get;
      try {
        O.defineProperty(proto, name, {
          get: nativeLike(function () { return get.call(this, origGet); }, 'get ' + name),
          set: d && d.set, enumerable: d ? !!d.enumerable : true, configurable: true
        });
      } catch (e) {}
    }

    function remove(obj, name) {
      try { delete obj[name]; } catch (e) {}
      try { if (name in obj) O.defineProperty(obj, name, { value: undefined, configurable: true }); } catch (e) {}
    }

    // --- Function.prototype.toString: hide our patches -------------------
    var FP = w.Function.prototype;
    var origToString = FP.toString;
    var toStr = function toString() {
      if (fakeSource.has(this)) return fakeSource.get(this);
      return origToString.call(this);
    };
    fakeSource.set(toStr, 'function toString() { [native code] }');
    O.defineProperty(FP, 'toString', { value: toStr, writable: true, enumerable: false, configurable: true });

    // --- WebRTC: the classic real-IP leak that bypasses proxies ----------
    ['RTCPeerConnection', 'webkitRTCPeerConnection', 'RTCDataChannel', 'RTCSessionDescription',
     'RTCIceCandidate', 'RTCRtpSender', 'RTCRtpReceiver', 'RTCRtpTransceiver', 'RTCDtlsTransport',
     'RTCIceTransport', 'RTCSctpTransport', 'RTCCertificate', 'RTCEncodedAudioFrame',
     'RTCEncodedVideoFrame', 'RTCDTMFSender', 'RTCStatsReport', 'RTCTrackEvent',
     'RTCPeerConnectionIceEvent', 'RTCDataChannelEvent', 'RTCError', 'RTCErrorEvent'
    ].forEach(function (n) { remove(w, n); });

    // --- Navigator: pin device-specific values ---------------------------
    var NP = w.Navigator && w.Navigator.prototype;
    var langs = O.freeze(['en-US', 'en']);
    getter(NP, 'hardwareConcurrency', function () { return 4; });
    getter(NP, 'deviceMemory', function () { return 4; });
    getter(NP, 'language', function () { return 'en-US'; });
    getter(NP, 'languages', function () { return langs; });
    getter(NP, 'maxTouchPoints', function () { return 5; });
    getter(NP, 'doNotTrack', function () { return null; });
    ['getBattery', 'connection', 'bluetooth', 'usb', 'serial', 'hid', 'nfc', 'keyboard',
     'getInstalledRelatedApps', 'setAppBadge', 'clearAppBadge', 'getGamepads', 'wakeLock',
     'virtualKeyboard', 'ink', 'contacts', 'login', 'credentials', 'xr', 'presentation']
      .forEach(function (n) { if (NP) remove(NP, n); });

    // Geolocation: always "permission denied", never a position.
    var GP = w.Geolocation && w.Geolocation.prototype;
    function denyGeo(orig) {
      return function (success, error) {
        if (typeof error === 'function') {
          w.setTimeout(function () {
            error({ code: 1, message: 'User denied Geolocation', PERMISSION_DENIED: 1, POSITION_UNAVAILABLE: 2, TIMEOUT: 3 });
          }, 0);
        }
        return 0;
      };
    }
    method(GP, 'getCurrentPosition', denyGeo);
    method(GP, 'watchPosition', denyGeo);

    // Cameras / microphones: no enumeration, no access.
    var MDP = w.MediaDevices && w.MediaDevices.prototype;
    method(MDP, 'enumerateDevices', function () { return function () { return w.Promise.resolve([]); }; });
    function denyMedia() {
      return function () {
        return w.Promise.reject(new w.DOMException('Permission denied', 'NotAllowedError'));
      };
    }
    method(MDP, 'getUserMedia', denyMedia);
    method(MDP, 'getDisplayMedia', denyMedia);

    // Storage quota is derived from free disk space -> fingerprint.
    var SMP = w.StorageManager && w.StorageManager.prototype;
    method(SMP, 'estimate', function (orig) {
      return function () {
        return orig.apply(this, arguments).then(function (r) {
          return { quota: 2147483648, usage: (r && r.usage) || 0 };
        }, function () { return { quota: 2147483648, usage: 0 }; });
      };
    });

    // --- Time zone: everyone is in UTC ----------------------------------
    var OD = w.Date, DP = OD.prototype;
    var days = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
    var mons = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    var getTime = DP.getTime;
    function pad(n, l) { n = String(n); while (n.length < l) n = '0' + n; return n; }
    function year(y) { return y < 0 ? '-' + pad(-y, 6) : pad(y, 4); }
    function datePart(d) {
      return days[d.getUTCDay()] + ' ' + mons[d.getUTCMonth()] + ' ' + pad(d.getUTCDate(), 2) + ' ' + year(d.getUTCFullYear());
    }
    function timePart(d) {
      return pad(d.getUTCHours(), 2) + ':' + pad(d.getUTCMinutes(), 2) + ':' + pad(d.getUTCSeconds(), 2) +
        ' GMT+0000 (Coordinated Universal Time)';
    }
    function invalid(d) { return getTime.call(d) !== getTime.call(d); }
    method(DP, 'getTimezoneOffset', function () { return function () { return invalid(this) ? NaN : 0; }; });
    method(DP, 'toString', function () {
      return function () { return invalid(this) ? 'Invalid Date' : datePart(this) + ' ' + timePart(this); };
    });
    method(DP, 'toDateString', function () { return function () { return invalid(this) ? 'Invalid Date' : datePart(this); }; });
    method(DP, 'toTimeString', function () { return function () { return invalid(this) ? 'Invalid Date' : timePart(this); }; });
    ['FullYear', 'Month', 'Date', 'Day', 'Hours', 'Minutes', 'Seconds', 'Milliseconds'].forEach(function (u) {
      var utcGet = DP['getUTC' + u];
      method(DP, 'get' + u, function () { return function () { return utcGet.call(this); }; });
      if (u !== 'Day') {
        var utcSet = DP['setUTC' + u];
        method(DP, 'set' + u, function () { return function () { return utcSet.apply(this, arguments); }; });
      }
    });
    method(DP, 'getYear', function () { return function () { return invalid(this) ? NaN : this.getUTCFullYear() - 1900; }; });
    function utcOpts(locale, opts) {
      var o = {};
      if (opts != null) { for (var k in opts) o[k] = opts[k]; }
      if (o.timeZone === undefined) o.timeZone = 'UTC';
      return [locale === undefined ? 'en-US' : locale, o];
    }
    ['toLocaleString', 'toLocaleDateString', 'toLocaleTimeString'].forEach(function (m) {
      method(DP, m, function (orig) {
        return function (locale, opts) { return orig.apply(this, utcOpts(locale, opts)); };
      });
    });
    // new Date(y, m, d, ...) interprets its arguments as local time -> UTC.
    try {
      var DateProxy = new w.Proxy(OD, {
        construct: function (t, args, nt) {
          if (args.length > 1) args = [OD.UTC.apply(null, args)];
          return w.Reflect.construct(t, args, nt);
        },
        apply: function () { return DP.toString.call(new OD()); }
      });
      O.defineProperty(DP, 'constructor', { value: DateProxy, writable: true, configurable: true, enumerable: false });
      O.defineProperty(w, 'Date', { value: DateProxy, writable: true, configurable: true, enumerable: false });
    } catch (e) {}
    try {
      var ODTF = w.Intl.DateTimeFormat;
      var DTFProxy = new w.Proxy(ODTF, {
        construct: function (t, args, nt) { return w.Reflect.construct(t, utcOpts(args[0], args[1]), nt); },
        apply: function (t, self, args) { return w.Reflect.apply(t, self, utcOpts(args[0], args[1])); }
      });
      O.defineProperty(ODTF.prototype, 'constructor', { value: DTFProxy, writable: true, configurable: true, enumerable: false });
      O.defineProperty(w.Intl, 'DateTimeFormat', { value: DTFProxy, writable: true, configurable: true, enumerable: false });
    } catch (e) {}

    // --- Canvas: per-session noise on every read-back --------------------
    function noisify(data) {
      for (var i = 0; i < data.length; i += 4) {
        var r = hash(SEED ^ Math.imul(i + 1, 0x9e3779b1));
        if ((r & 15) === 0) {
          data[i] ^= (r >>> 8) & 1;
          data[i + 1] ^= (r >>> 9) & 1;
          data[i + 2] ^= (r >>> 10) & 1;
        }
      }
    }
    var C2D = w.CanvasRenderingContext2D && w.CanvasRenderingContext2D.prototype;
    var origGetImageData = C2D && C2D.getImageData;
    var createElement = w.Document.prototype.createElement;
    function noisyCopy(canvas) {
      try {
        var cw = canvas.width, ch = canvas.height;
        if (!cw || !ch || !origGetImageData) return null;
        var c = createElement.call(w.document, 'canvas');
        c.width = cw; c.height = ch;
        var ctx = c.getContext('2d');
        ctx.drawImage(canvas, 0, 0);
        var img = origGetImageData.call(ctx, 0, 0, cw, ch);
        noisify(img.data);
        ctx.putImageData(img, 0, 0);
        return c;
      } catch (e) { return null; }
    }
    method(C2D, 'getImageData', function (orig) {
      return function () { var img = orig.apply(this, arguments); noisify(img.data); return img; };
    });
    var HCE = w.HTMLCanvasElement && w.HTMLCanvasElement.prototype;
    method(HCE, 'toDataURL', function (orig) {
      return function () { return orig.apply(noisyCopy(this) || this, arguments); };
    });
    method(HCE, 'toBlob', function (orig) {
      return function () { return orig.apply(noisyCopy(this) || this, arguments); };
    });
    var OCP = w.OffscreenCanvas && w.OffscreenCanvas.prototype;
    var OC2D = w.OffscreenCanvasRenderingContext2D && w.OffscreenCanvasRenderingContext2D.prototype;
    method(OC2D, 'getImageData', function (orig) {
      return function () { var img = orig.apply(this, arguments); noisify(img.data); return img; };
    });
    method(OCP, 'convertToBlob', function (orig) {
      return function () {
        try {
          var ctx = this.getContext('2d');
          if (ctx) {
            var img = ctx.getImageData(0, 0, this.width, this.height); // noised by the patch above
            ctx.putImageData(img, 0, 0);
          }
        } catch (e) {}
        return orig.apply(this, arguments);
      };
    });

    // --- Stable hardware signals that survive a new identity --------------
    // These don't change when cookies are wiped, so they're what a site would use to
    // tie two accounts to the same phone. Remove or flatten them.

    // WebGL / WebGPU expose the GPU model and dozens of GPU-specific limits.
    var webglRe = /^(experimental-)?webgl2?$/i;
    function noWebGL(orig) {
      return function (type) {
        if (webglRe.test(String(type))) return null;
        return orig.apply(this, arguments);
      };
    }
    method(w.HTMLCanvasElement && w.HTMLCanvasElement.prototype, 'getContext', noWebGL);
    method(w.OffscreenCanvas && w.OffscreenCanvas.prototype, 'getContext', noWebGL);
    if (NP) remove(NP, 'gpu');

    // Installed text-to-speech voices differ per phone, language pack and vendor.
    var SSP = w.SpeechSynthesis && w.SpeechSynthesis.prototype;
    method(SSP, 'getVoices', function () { return function () { return []; }; });

    // Screen: report the browser window, not the exact panel and system-bar sizes.
    var SP = w.Screen && w.Screen.prototype;
    getter(SP, 'width', function () { return w.outerWidth || w.innerWidth; });
    getter(SP, 'availWidth', function () { return w.outerWidth || w.innerWidth; });
    getter(SP, 'height', function () { return w.outerHeight || w.innerHeight; });
    getter(SP, 'availHeight', function () { return w.outerHeight || w.innerHeight; });
    getter(SP, 'availTop', function () { return 0; });
    getter(SP, 'availLeft', function () { return 0; });
    getter(SP, 'colorDepth', function () { return 24; });
    getter(SP, 'pixelDepth', function () { return 24; });

    // --- WebGL (if a page gets a context some other way): hide GPU, add noise
    [w.WebGLRenderingContext, w.WebGL2RenderingContext].forEach(function (GL) {
      if (!GL) return;
      var P = GL.prototype;
      method(P, 'getExtension', function (orig) {
        return function (name) {
          if (String(name).toLowerCase() === 'webgl_debug_renderer_info') return null;
          return orig.apply(this, arguments);
        };
      });
      method(P, 'getSupportedExtensions', function (orig) {
        return function () {
          var list = orig.apply(this, arguments);
          return list && list.filter(function (n) { return n !== 'WEBGL_debug_renderer_info'; });
        };
      });
      method(P, 'getParameter', function (orig) {
        return function (p) {
          if (p === 0x9245 || p === 0x9246) return null; // UNMASKED_VENDOR/RENDERER_WEBGL
          return orig.apply(this, arguments);
        };
      });
      method(P, 'readPixels', function (orig) {
        return function () {
          var r = orig.apply(this, arguments);
          var px = arguments[6];
          if (px && px.length && px.BYTES_PER_ELEMENT === 1) noisify(px);
          return r;
        };
      });
    });

    // --- Audio: per-session noise on rendered audio ----------------------
    var ABP = w.AudioBuffer && w.AudioBuffer.prototype;
    var noisedAudio = new WeakSet();
    method(ABP, 'getChannelData', function (orig) {
      return function () {
        var d = orig.apply(this, arguments);
        if (!noisedAudio.has(d)) {
          noisedAudio.add(d);
          for (var i = 0; i < d.length; i += 53) {
            d[i] += ((hash(SEED + i) % 2001) - 1000) * 1e-10;
          }
        }
        return d;
      };
    });
    var ANP = w.AnalyserNode && w.AnalyserNode.prototype;
    method(ANP, 'getFloatFrequencyData', function (orig) {
      return function (arr) {
        var r = orig.apply(this, arguments);
        if (arr) for (var i = 0; i < arr.length; i += 7) arr[i] += ((hash(SEED ^ i) % 201) - 100) * 1e-6;
        return r;
      };
    });

    // --- Same-origin iframes get the same treatment immediately ----------
    var IFP = w.HTMLIFrameElement && w.HTMLIFrameElement.prototype;
    getter(IFP, 'contentWindow', function (orig) {
      var win = orig.call(this);
      try { patchWindow(win); } catch (e) {}
      return win;
    });
    getter(IFP, 'contentDocument', function (orig) {
      var doc = orig.call(this);
      try { if (doc) patchWindow(doc.defaultView); } catch (e) {}
      return doc;
    });
  }

  patchWindow(window);
})();
