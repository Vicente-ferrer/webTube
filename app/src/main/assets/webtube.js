// Injected at document start into youtube.com pages (see PlayerBridge.java).
// Only uses APIs allowed under YouTube's Trusted Types policy: no innerHTML/outerHTML.
(function () {
    if (window.top !== window || window.__webTube) return;

    var bridge = window.WebTubeBridge;
    // Which WebView this is (MiniPlayerController.java): the "player" shows videos,
    // the "browser" every other page. Set by PlayerBridge.java.
    var role = '__WEBTUBE_ROLE__';
    var actionHandlers = {};
    var lastReport = '';

    function post(message) {
        if (bridge) bridge.postMessage(JSON.stringify(message));
    }

    // Only when the state changed
    function send(message) {
        var json = JSON.stringify(message);
        if (json === lastReport) return;
        lastReport = json;
        post(message);
    }

    // Same test as YouTubeUrls.isVideoUrl
    function isVideoPath(path) {
        return /^\/(watch|shorts|live)(\/|$)/.test(path);
    }

    // Links to a video open in the player, and from the player, links to any other
    // YouTube page open in the browser. Before YouTube's own click handlers.
    window.addEventListener('click', function (e) {
        var link = e.target instanceof Element ? e.target.closest('a[href]') : null;
        if (!link || e.defaultPrevented || e.button !== 0) return;
        var url = new URL(link.href, location.href);
        if (url.protocol !== 'https:' || !/(^|\.)youtube\.com$/.test(url.hostname)) return;
        if (isVideoPath(url.pathname) === (role === 'player')) return;
        e.preventDefault();
        e.stopImmediatePropagation();
        post({type: 'open', url: url.href});
    }, true);

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

    // Picture-in-picture and the mini player: only the video, filling the window.
    // Sizes in % of the viewport, since vh units are 0 in a wrap_content WebView.
    addStyle('html.webtube-video-only, html.webtube-video-only body { overflow: hidden !important; background: #000 !important; }' +
        ' html.webtube-video-only body, html.webtube-video-only body * { visibility: hidden !important; }' +
        ' html.webtube-video-only body video { visibility: visible !important; position: fixed !important; top: 0 !important; left: 0 !important;' +
        ' width: 100% !important; height: 100% !important; object-fit: contain !important; transform: none !important;' +
        ' z-index: 2147483647 !important; background: #000 !important; }');
    var pictureInPicture = false;
    var mini = false;

    function updateVideoOnly() {
        document.documentElement.classList.toggle('webtube-video-only', pictureInPicture || mini);
    }

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

    // YouTube compares the window to the screen size, and pauses when the window is small
    // (the picture-in-picture window) the same way it does in the background
    Object.defineProperty(window, 'outerWidth', {configurable: true, get: function () { return screen.width; }});
    Object.defineProperty(window, 'outerHeight', {configurable: true, get: function () { return screen.height; }});

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
        if (!v || role !== 'player') return;
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
            artwork: artwork,
            skipVideoId: skip.videoId
        });
    }

    // SponsorBlock segments of a video, from SponsorBlock.java: [[start, end, category], ...] in seconds
    var skip = {videoId: '', segments: [], skipped: {}};

    function skipSegments(v) {
        if (!skip.segments.length || skip.videoId !== videoId() || isAd()) return;
        for (var i = 0; i < skip.segments.length; i++) {
            var segment = skip.segments[i];
            // Once each, so seeking back into a segment plays it
            if (!skip.skipped[i] && v.currentTime >= segment[0] && v.currentTime < segment[1] - 0.5) {
                skip.skipped[i] = true;
                v.currentTime = segment[1];
                post({type: 'skipped', category: segment[2]});
            }
        }
    }

    ['play', 'playing', 'pause', 'ended', 'seeked', 'ratechange', 'loadedmetadata', 'durationchange', 'emptied'].forEach(function (type) {
        document.addEventListener(type, function (e) {
            if (e.target instanceof HTMLVideoElement) report();
        }, true);
    });

    // YouTube renames the page after the video starts, and when autoplay moves to the next one
    document.addEventListener('timeupdate', function (e) {
        if (!(e.target instanceof HTMLVideoElement)) return;
        if (title() !== lastTitle) report();
        skipSegments(e.target);
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
        setPictureInPicture: function (enabled) {
            pictureInPicture = enabled;
            updateVideoOnly();
        },
        setMini: function (enabled) {
            mini = enabled;
            updateVideoOnly();
        },
        // The browser's feed previews, while the player covers it
        pauseAll: function () {
            document.querySelectorAll('video').forEach(function (v) { v.pause(); });
        },
        setSkipSegments: function (id, segments) {
            if (id === skip.videoId) return;
            skip = {videoId: id, segments: segments, skipped: {}};
            lastReport = '';
            report();
        },
        addStyle: addStyle
    };
})();
