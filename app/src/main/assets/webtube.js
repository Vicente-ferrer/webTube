// Injected at document start into youtube.com pages (see PlayerBridge.java).
// Only uses APIs allowed under YouTube's Trusted Types policy: no innerHTML/outerHTML.
(function () {
    if (window.top !== window || window.__webTube) return;

    var bridge = window.WebTubeBridge;
    var actionHandlers = {};
    var lastReport = '';

    function send(message) {
        if (!bridge) return;
        var json = JSON.stringify(message);
        if (json === lastReport) return;
        lastReport = json;
        bridge.postMessage(json);
    }

    function addStyle(css) {
        try {
            var sheet = new CSSStyleSheet();
            sheet.replaceSync(css);
            document.adoptedStyleSheets = document.adoptedStyleSheets.concat([sheet]);
        } catch (e) {
            // CSSStyleSheet constructor not supported (very old WebView)
        }
    }

    // Get rid of orange outlines
    addStyle('*, *:focus { outline: none !important; -webkit-tap-highlight-color: transparent !important; }');

    // The app decides when to pause (MainActivity.onPause), so the page must never
    // see itself as hidden, or YouTube pauses the video when the screen turns off.
    function visible(name, value) {
        Object.defineProperty(Document.prototype, name, {
            configurable: true,
            get: function () { return value; }
        });
    }
    visible('hidden', false);
    visible('webkitHidden', false);
    visible('visibilityState', 'visible');
    visible('webkitVisibilityState', 'visible');
    ['visibilitychange', 'webkitvisibilitychange'].forEach(function (type) {
        window.addEventListener(type, function (e) { e.stopImmediatePropagation(); }, true);
    });

    // YouTube registers Media Session handlers for next/previous; keep them so the
    // notification and lock screen buttons can call them.
    if (window.MediaSession) {
        var setActionHandler = MediaSession.prototype.setActionHandler;
        MediaSession.prototype.setActionHandler = function (action, handler) {
            actionHandlers[action] = handler;
            try {
                return setActionHandler.call(this, action, handler);
            } catch (e) {
                // Action not supported by this WebView
            }
        };

        var metadata = Object.getOwnPropertyDescriptor(MediaSession.prototype, 'metadata');
        if (metadata && metadata.set) {
            Object.defineProperty(MediaSession.prototype, 'metadata', {
                configurable: true,
                get: function () { return metadata.get.call(this); },
                set: function (value) {
                    metadata.set.call(this, value);
                    setTimeout(report, 0);
                }
            });
        }
    }

    function video() {
        return document.querySelector('video');
    }

    function videoId() {
        var match = location.href.match(/[?&]v=([\w-]{11})/) || location.pathname.match(/\/shorts\/([\w-]{11})/);
        return match ? match[1] : '';
    }

    function isAd() {
        return !!document.querySelector('.ad-showing, .ad-interrupting');
    }

    function title() {
        var meta = navigator.mediaSession && navigator.mediaSession.metadata;
        return (meta && meta.title) || document.title.replace(/ - YouTube$/, '');
    }

    var lastTitle = '';

    function report() {
        var v = video();
        if (!v) return;
        var meta = navigator.mediaSession && navigator.mediaSession.metadata;
        var artwork = meta && meta.artwork && meta.artwork.length ? meta.artwork[meta.artwork.length - 1].src : '';
        lastTitle = title();
        send({
            type: 'state',
            playing: !v.paused && !v.ended,
            ended: v.ended,
            position: Math.round(v.currentTime * 1000),
            duration: isFinite(v.duration) ? Math.round(v.duration * 1000) : 0,
            rate: v.playbackRate,
            width: v.videoWidth,
            height: v.videoHeight,
            ad: isAd(),
            videoId: videoId(),
            url: location.href,
            title: lastTitle,
            artist: (meta && meta.artist) || '',
            artwork: artwork
        });
    }

    ['play', 'playing', 'pause', 'ended', 'seeked', 'ratechange', 'loadedmetadata', 'durationchange', 'emptied'].forEach(function (type) {
        document.addEventListener(type, function (e) {
            if (e.target instanceof HTMLVideoElement) report();
        }, true);
    });

    // YouTube renames the page after the video starts, and when autoplay moves to the next one
    document.addEventListener('timeupdate', function (e) {
        if (e.target instanceof HTMLVideoElement && title() !== lastTitle) report();
    }, true);

    function callAction(action, details) {
        var handler = actionHandlers[action];
        if (!handler) return false;
        details = details || {};
        details.action = action;
        handler(details);
        return true;
    }

    // Called from PlayerController.java
    window.__webTube = {
        play: function () { var v = video(); if (v) v.play(); },
        pause: function () { var v = video(); if (v) v.pause(); },
        toggle: function () { var v = video(); if (v) { if (v.paused) v.play(); else v.pause(); } },
        seekTo: function (ms) { var v = video(); if (v) v.currentTime = ms / 1000; },
        next: function () { callAction('nexttrack'); },
        previous: function () {
            var v = video();
            if (v && v.currentTime > 3) v.currentTime = 0;
            else if (!callAction('previoustrack')) history.back();
        },
        report: function () { lastReport = ''; report(); },
        addStyle: addStyle
    };
})();
