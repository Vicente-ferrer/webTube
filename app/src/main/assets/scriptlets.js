// Scriptlets for "##+js(name, args)" filter rules, compatible with the uBlock Origin names
// filter lists use. ScriptBuilder.java wraps this expression with the calls for one site and
// injects it at document start. run(name, args) ignores unknown scriptlets.
(function () {
    'use strict';

    var NEVER = /(?!)/;

    function toRegExp(pattern, keepFlags) {
        var match = /^\/(.+)\/([dgimsuy]*)$/.exec(pattern);
        if (match) {
            try {
                return new RegExp(match[1], keepFlags ? match[2] : match[2].replace('g', ''));
            } catch (e) {
                return NEVER;
            }
        }
        return new RegExp(pattern.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), keepFlags ? 'g' : '');
    }

    // "" matches everything, "/re/" is a regular expression, anything else a substring;
    // with allowNegation, a leading "!" inverts the result
    function toMatcher(pattern, allowNegation) {
        pattern = pattern === undefined ? '' : String(pattern);
        var negated = allowNegation && pattern.charAt(0) === '!';
        if (negated) {
            pattern = pattern.slice(1);
        }
        if (pattern === '') {
            return function () { return !negated; };
        }
        var re = toRegExp(pattern, false);
        return function (text) { return re.test(String(text)) !== negated; };
    }

    // Named arguments after the positional ones: [..., 'propsToMatch', '/player/']
    function namedArgs(args, from) {
        var named = {};
        for (var i = from; i + 1 < args.length; i += 2) {
            named[args[i]] = args[i + 1];
        }
        return named;
    }

    // "url:/re/ method:POST", or just "/re/" or "substring" for the URL
    function requestMatcher(propsToMatch) {
        var tests = [];
        String(propsToMatch || '').split(/\s+/).forEach(function (prop) {
            if (prop === '') {
                return;
            }
            var match = /^(url|method):(.*)$/.exec(prop);
            var key = match ? match[1] : 'url';
            var matcher = toMatcher(match ? match[2] : prop, false);
            tests.push(function (details) { return matcher(details[key]); });
        });
        return function (details) {
            return tests.every(function (test) { return test(details); });
        };
    }

    // Calls onLeaf(owner, property) for the last property of "a.b.c", once the objects exist
    function trapChain(owner, chain, onLeaf) {
        var dot = chain.indexOf('.');
        if (dot === -1) {
            onLeaf(owner, chain);
            return;
        }
        var property = chain.slice(0, dot);
        var rest = chain.slice(dot + 1);
        var value;
        try {
            value = owner[property];
        } catch (e) {
            return;
        }
        if (value !== null && (typeof value === 'object' || typeof value === 'function')) {
            trapChain(value, rest, onLeaf);
            return;
        }
        var descriptor = Object.getOwnPropertyDescriptor(owner, property);
        if (descriptor && !descriptor.configurable) {
            return;
        }
        // Another rule may already trap this property (a.b and a.c): keep its setter working
        var previous = descriptor && descriptor.set ? descriptor : null;
        Object.defineProperty(owner, property, {
            configurable: true,
            enumerable: true,
            get: function () { return previous ? previous.get.call(this) : value; },
            set: function (newValue) {
                if (previous) {
                    previous.set.call(this, newValue);
                }
                value = newValue;
                if (newValue !== null && (typeof newValue === 'object' || typeof newValue === 'function')) {
                    trapChain(newValue, rest, onLeaf);
                }
            }
        });
    }

    function defineLeaf(owner, property, getter, setter) {
        var descriptor = Object.getOwnPropertyDescriptor(owner, property);
        if (descriptor && !descriptor.configurable) {
            return;
        }
        Object.defineProperty(owner, property, {
            configurable: true,
            enumerable: descriptor ? descriptor.enumerable : true,
            get: getter,
            set: setter
        });
    }

    function constantValue(raw, trusted) {
        switch (raw) {
            case 'undefined': return { value: undefined };
            case 'false': return { value: false };
            case 'true': return { value: true };
            case 'null': return { value: null };
            case '': case "''": case 'emptyStr': return { value: '' };
            case 'noopFunc': return { value: function () {} };
            case 'trueFunc': return { value: function () { return true; } };
            case 'falseFunc': return { value: function () { return false; } };
            case 'throwFunc': return { value: function () { throw new Error(); } };
            case 'noopCallbackFunc': return { value: function () { return function () {}; } };
            case 'noopPromiseResolve': return { value: function () { return Promise.resolve(); } };
            case 'noopPromiseReject': return { value: function () { return Promise.reject(); } };
            case '{}': case 'emptyObj': return { value: {} };
            case '[]': case 'emptyArr': return { value: [] };
        }
        if (/^-?\d+$/.test(raw) && Math.abs(Number(raw)) <= 32767) {
            return { value: Number(raw) };
        }
        if (trusted) {
            try {
                return { value: JSON.parse(raw) };
            } catch (e) {
                return { value: raw };
            }
        }
        return null;
    }

    // --- JSON paths: "a.b.c", with "[]", "[-]", "{-}" or "*" for every element ---

    function isObject(value) {
        return value !== null && typeof value === 'object';
    }

    function isWildcard(segment) {
        return segment === '[]' || segment === '[-]' || segment === '{-}' || segment === '*';
    }

    function hasPath(node, segments, index) {
        if (index === segments.length) {
            return true;
        }
        if (!isObject(node)) {
            return false;
        }
        var segment = segments[index];
        if (isWildcard(segment)) {
            return Object.keys(node).some(function (key) { return hasPath(node[key], segments, index + 1); });
        }
        return Object.prototype.hasOwnProperty.call(node, segment) && hasPath(node[segment], segments, index + 1);
    }

    function prunePath(node, segments, index) {
        if (!isObject(node)) {
            return false;
        }
        var segment = segments[index];
        var last = index === segments.length - 1;
        var keys = isWildcard(segment) ? Object.keys(node)
            : Object.prototype.hasOwnProperty.call(node, segment) ? [segment] : [];
        var pruned = false;
        keys.forEach(function (key) {
            if (last) {
                delete node[key];
                pruned = true;
            } else if (prunePath(node[key], segments, index + 1)) {
                pruned = true;
            }
        });
        return pruned;
    }

    function splitPaths(raw) {
        return String(raw || '').split(/\s+/).filter(Boolean).map(function (path) { return path.split('.'); });
    }

    // Deletes the prune paths, only if every needle path exists. True when something was deleted.
    function pruner(rawPrunePaths, rawNeedlePaths) {
        var prunePaths = splitPaths(rawPrunePaths);
        var needlePaths = splitPaths(rawNeedlePaths);
        return function (object) {
            if (!isObject(object) || !needlePaths.every(function (path) { return hasPath(object, path, 0); })) {
                return false;
            }
            var pruned = false;
            prunePaths.forEach(function (path) {
                if (prunePath(object, path, 0)) {
                    pruned = true;
                }
            });
            return pruned;
        };
    }

    // --- JSONPath subset of uBlock Origin's json-edit: "..a.b.*[?.c]", "$.a[0]" ---

    function parseJsonPath(path) {
        var steps = [];
        var i = path.charAt(0) === '$' ? 1 : 0;
        var name = /^[^.[\]=]+/;
        while (i < path.length) {
            var match;
            if (path.startsWith('..', i)) {
                match = name.exec(path.slice(i + 2));
                if (!match) {
                    return null;
                }
                steps.push({ type: 'descendant', name: match[0] });
                i += 2 + match[0].length;
            } else if (path.charAt(i) === '[') {
                var depth = 0;
                var end = i;
                for (; end < path.length; end++) {
                    if (path.charAt(end) === '[') {
                        depth++;
                    } else if (path.charAt(end) === ']' && --depth === 0) {
                        break;
                    }
                }
                var inner = path.slice(i + 1, end);
                i = end + 1;
                if (inner === '*' || inner === '-' || inner === '') {
                    steps.push({ type: 'all' });
                } else if (/^\d+$/.test(inner)) {
                    steps.push({ type: 'child', name: inner });
                } else if (inner.charAt(0) === '?') {
                    var condition = inner.slice(1);
                    var equals = condition.indexOf('==');
                    var subPath = parseJsonPath(equals === -1 ? condition : condition.slice(0, equals));
                    if (!subPath) {
                        return null;
                    }
                    var expected;
                    if (equals !== -1) {
                        try {
                            expected = { value: JSON.parse(condition.slice(equals + 2)) };
                        } catch (e) {
                            return null;
                        }
                    }
                    steps.push({ type: 'filter', path: subPath, expected: expected });
                } else if (/^(['"]).*\1$/.test(inner)) {
                    steps.push({ type: 'child', name: inner.slice(1, -1) });
                } else {
                    return null;
                }
            } else {
                if (path.charAt(i) === '.') {
                    i++;
                }
                match = name.exec(path.slice(i));
                if (!match) {
                    return null; // assignments ("=" / "+=") are not supported
                }
                steps.push(match[0] === '*' ? { type: 'all' } : { type: 'child', name: match[0] });
                i += match[0].length;
            }
        }
        return steps;
    }

    function evaluateJsonPath(root, steps) {
        var nodes = [{ parent: null, key: null, value: root }];
        steps.forEach(function (step) {
            var next = [];
            nodes.forEach(function (node) {
                var value = node.value;
                if (step.type === 'filter') {
                    var found = evaluateJsonPath(value, step.path);
                    if (found.length && (!step.expected || found.some(function (n) { return n.value === step.expected.value; }))) {
                        next.push(node);
                    }
                    return;
                }
                if (!isObject(value)) {
                    return;
                }
                if (step.type === 'child') {
                    if (Object.prototype.hasOwnProperty.call(value, step.name)) {
                        next.push({ parent: value, key: step.name, value: value[step.name] });
                    }
                } else if (step.type === 'all') {
                    Object.keys(value).forEach(function (key) {
                        next.push({ parent: value, key: key, value: value[key] });
                    });
                } else {
                    (function walk(object) {
                        Object.keys(object).forEach(function (key) {
                            if (key === step.name) {
                                next.push({ parent: object, key: key, value: object[key] });
                            }
                            if (isObject(object[key])) {
                                walk(object[key]);
                            }
                        });
                    })(value);
                }
            });
            nodes = next;
        });
        return nodes;
    }

    // Deletes what the path selects. True when something was deleted.
    function jsonEditor(path) {
        var steps = parseJsonPath(String(path || ''));
        return function (object) {
            if (!steps || !steps.length || !isObject(object)) {
                return false;
            }
            var seen = new Map();
            var nodes = evaluateJsonPath(object, steps).filter(function (node) {
                if (node.parent === null) {
                    return false;
                }
                var keys = seen.get(node.parent) || new Set();
                seen.set(node.parent, keys);
                return !keys.has(node.key) && keys.add(node.key);
            });
            // Remove array elements from the end so the other indexes stay valid
            var index = function (node) { return Array.isArray(node.parent) ? Number(node.key) : -1; };
            nodes.sort(function (a, b) { return index(b) - index(a); });
            nodes.forEach(function (node) {
                if (Array.isArray(node.parent)) {
                    node.parent.splice(Number(node.key), 1);
                } else {
                    delete node.parent[node.key];
                }
            });
            return nodes.length > 0;
        };
    }

    // Turns an object editor into a text editor for fetch/XHR responses
    function jsonTextEditor(edit) {
        return function (text) {
            var object;
            try {
                object = JSON.parse(text);
            } catch (e) {
                return text;
            }
            return edit(object) ? JSON.stringify(object) : text;
        };
    }

    function textReplacer(pattern, replacement) {
        var re = toRegExp(pattern, true);
        return function (text) {
            return text.replace(re, replacement || '');
        };
    }

    // --- Hooks shared by the scriptlets ---

    var jsonParseHooks = null;

    function onJsonParsed(edit) {
        if (jsonParseHooks === null) {
            jsonParseHooks = [];
            var apply = function (object) {
                jsonParseHooks.forEach(function (hook) {
                    try {
                        hook(object);
                    } catch (e) {
                        // A broken rule must not break the page
                    }
                });
                return object;
            };
            JSON.parse = new Proxy(JSON.parse, {
                apply: function (target, thisArg, args) {
                    return apply(Reflect.apply(target, thisArg, args));
                }
            });
            Response.prototype.json = new Proxy(Response.prototype.json, {
                apply: function (target, thisArg, args) {
                    return Reflect.apply(target, thisArg, args).then(apply);
                }
            });
        }
        jsonParseHooks.push(edit);
    }

    function requestDetails(input, init) {
        var isRequest = typeof Request !== 'undefined' && input instanceof Request;
        return {
            url: isRequest ? input.url : String(input),
            method: String((init && init.method) || (isRequest ? input.method : 'GET')).toUpperCase()
        };
    }

    var fetchHooks = null;

    // Rewrites the text of fetch() responses for matching requests
    function onFetchResponse(matcher, editText) {
        if (fetchHooks === null) {
            fetchHooks = [];
            window.fetch = new Proxy(window.fetch, {
                apply: function (target, thisArg, args) {
                    var details = requestDetails(args[0], args[1]);
                    var hooks = fetchHooks.filter(function (hook) { return hook.matcher(details); });
                    var promise = Reflect.apply(target, thisArg, args);
                    if (!hooks.length) {
                        return promise;
                    }
                    return promise.then(function (response) {
                        return response.clone().text().then(function (text) {
                            var edited = text;
                            hooks.forEach(function (hook) {
                                try {
                                    edited = hook.editText(edited);
                                } catch (e) {
                                    // Keep the response as it was
                                }
                            });
                            if (edited === text) {
                                return response;
                            }
                            var replaced = new Response(edited, {
                                status: response.status,
                                statusText: response.statusText,
                                headers: response.headers
                            });
                            Object.defineProperties(replaced, {
                                url: { value: response.url },
                                type: { value: response.type },
                                redirected: { value: response.redirected }
                            });
                            return replaced;
                        }, function () {
                            return response;
                        });
                    });
                }
            });
        }
        fetchHooks.push({ matcher: matcher, editText: editText });
    }

    var xhrHooks = null;

    // Rewrites the text of XMLHttpRequest responses for matching requests
    function onXhrResponse(matcher, editText) {
        if (xhrHooks === null) {
            xhrHooks = [];
            var proto = XMLHttpRequest.prototype;
            var open = proto.open;
            proto.open = function (method, url) {
                this.__webTubeRequest = { method: String(method).toUpperCase(), url: String(url), edited: {} };
                return open.apply(this, arguments);
            };
            ['response', 'responseText'].forEach(function (property) {
                var descriptor = Object.getOwnPropertyDescriptor(proto, property);
                Object.defineProperty(proto, property, {
                    configurable: true,
                    enumerable: descriptor.enumerable,
                    get: function () {
                        var value = descriptor.get.call(this);
                        var request = this.__webTubeRequest;
                        if (!request || this.readyState !== 4) {
                            return value;
                        }
                        if (property in request.edited) {
                            return request.edited[property];
                        }
                        var hooks = xhrHooks.filter(function (hook) { return hook.matcher(request); });
                        var result = value;
                        var asJson = property === 'response' && this.responseType === 'json' && isObject(value);
                        if (hooks.length && (typeof value === 'string' || asJson)) {
                            var text = asJson ? JSON.stringify(value) : value;
                            var edited = text;
                            hooks.forEach(function (hook) {
                                try {
                                    edited = hook.editText(edited);
                                } catch (e) {
                                    // Keep the response as it was
                                }
                            });
                            if (edited !== text) {
                                result = asJson ? JSON.parse(edited) : edited;
                            }
                        }
                        request.edited[property] = result;
                        return result;
                    }
                });
            });
        }
        xhrHooks.push({ matcher: matcher, editText: editText });
    }

    function timerScriptlet(timerName, rawNeedle, rawDelay, transform) {
        var needle = toMatcher(rawNeedle, true);
        var delayFilter = String(rawDelay || '');
        var delayNegated = delayFilter.charAt(0) === '!';
        var delay = parseInt(delayNegated ? delayFilter.slice(1) : delayFilter, 10);
        window[timerName] = new Proxy(window[timerName], {
            apply: function (target, thisArg, args) {
                var callback = String(args[0]);
                var matchesDelay = isNaN(delay) || ((Number(args[1]) === delay) !== delayNegated);
                if (needle(callback) && matchesDelay) {
                    var replaced = transform(args);
                    if (replaced === null) {
                        return 0;
                    }
                    args = replaced;
                }
                return Reflect.apply(target, thisArg, args);
            }
        });
    }

    function onDomReady(callback) {
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', callback, { once: true });
        } else {
            callback();
        }
    }

    function observeElements(selector, callback) {
        var run = function () {
            document.querySelectorAll(selector).forEach(callback);
        };
        onDomReady(run);
        new MutationObserver(run).observe(document, { childList: true, subtree: true, attributes: true });
    }

    function observeNodeText(nodeName, editText) {
        var name = String(nodeName || '').toUpperCase();
        new MutationObserver(function (mutations) {
            mutations.forEach(function (mutation) {
                mutation.addedNodes.forEach(function (node) {
                    var target = node.nodeType === 3 ? node.parentNode : node;
                    if (!target || target.nodeName !== name) {
                        return;
                    }
                    var text = node.textContent;
                    var edited = editText(text);
                    if (edited !== text) {
                        node.textContent = edited;
                    }
                });
            });
        }).observe(document, { childList: true, subtree: true });
    }

    function storageScriptlet(storageName, key, rawValue) {
        if (!key) {
            return;
        }
        var allowed = ['undefined', 'false', 'true', 'null', '', 'emptyArr', 'emptyObj', 'yes', 'no', 'on', 'off', '$remove$'];
        if (allowed.indexOf(rawValue) === -1 && !/^\d+$/.test(rawValue)) {
            return;
        }
        try {
            var storage = window[storageName];
            if (rawValue === '$remove$') {
                storage.removeItem(key);
            } else {
                storage.setItem(key, rawValue === 'emptyArr' ? '[]' : rawValue === 'emptyObj' ? '{}' : rawValue);
            }
        } catch (e) {
            // Storage disabled
        }
    }

    function blockFetch(propsToMatch) {
        var matcher = requestMatcher(propsToMatch);
        window.fetch = new Proxy(window.fetch, {
            apply: function (target, thisArg, args) {
                if (matcher(requestDetails(args[0], args[1]))) {
                    return Promise.resolve(new Response(''));
                }
                return Reflect.apply(target, thisArg, args);
            }
        });
    }

    function blockXhr(propsToMatch) {
        var matcher = requestMatcher(propsToMatch);
        var proto = XMLHttpRequest.prototype;
        var open = proto.open;
        var send = proto.send;
        proto.open = function (method, url) {
            this.__webTubeBlocked = matcher({ method: String(method).toUpperCase(), url: String(url) });
            return open.apply(this, arguments);
        };
        proto.send = function () {
            if (!this.__webTubeBlocked) {
                return send.apply(this, arguments);
            }
            var xhr = this;
            Object.defineProperties(xhr, {
                readyState: { value: 4 },
                status: { value: 200 },
                statusText: { value: 'OK' },
                response: { value: '' },
                responseText: { value: '' }
            });
            setTimeout(function () {
                ['readystatechange', 'load', 'loadend'].forEach(function (type) {
                    xhr.dispatchEvent(new Event(type));
                });
            }, 1);
        };
    }

    var scriptlets = {
        'set-constant': function (chain, rawValue) {
            var constant = constantValue(rawValue, false);
            if (!chain || !constant) {
                return;
            }
            trapChain(window, chain, function (owner, property) {
                defineLeaf(owner, property, function () { return constant.value; }, function () {});
            });
        },
        'trusted-set-constant': function (chain, rawValue) {
            var constant = constantValue(rawValue, true);
            if (!chain || !constant) {
                return;
            }
            trapChain(window, chain, function (owner, property) {
                defineLeaf(owner, property, function () { return constant.value; }, function () {});
            });
        },
        'abort-on-property-read': function (chain) {
            if (!chain) {
                return;
            }
            trapChain(window, chain, function (owner, property) {
                var value = owner[property];
                defineLeaf(owner, property, function () { throw new ReferenceError(property); }, function (v) { value = v; });
            });
        },
        'abort-on-property-write': function (chain) {
            if (!chain) {
                return;
            }
            trapChain(window, chain, function (owner, property) {
                defineLeaf(owner, property, function () { return undefined; }, function () { throw new ReferenceError(property); });
            });
        },
        'abort-current-script': function (chain, rawNeedle) {
            if (!chain) {
                return;
            }
            var needle = toMatcher(rawNeedle, false);
            var check = function () {
                var script = document.currentScript;
                if (script && needle(script.src || script.textContent)) {
                    throw new ReferenceError(chain);
                }
            };
            trapChain(window, chain, function (owner, property) {
                var value = owner[property];
                defineLeaf(owner, property, function () { check(); return value; }, function (v) { check(); value = v; });
            });
        },
        'json-prune': function (rawPrunePaths, rawNeedlePaths) {
            onJsonParsed(pruner(rawPrunePaths, rawNeedlePaths));
        },
        'json-prune-fetch-response': function (rawPrunePaths, rawNeedlePaths) {
            var named = namedArgs(arguments, 2);
            onFetchResponse(requestMatcher(named.propsToMatch), jsonTextEditor(pruner(rawPrunePaths, rawNeedlePaths)));
        },
        'json-prune-xhr-response': function (rawPrunePaths, rawNeedlePaths) {
            var named = namedArgs(arguments, 2);
            onXhrResponse(requestMatcher(named.propsToMatch), jsonTextEditor(pruner(rawPrunePaths, rawNeedlePaths)));
        },
        'json-edit': function (path) {
            onJsonParsed(jsonEditor(path));
        },
        'json-edit-fetch-response': function (path) {
            var named = namedArgs(arguments, 1);
            onFetchResponse(requestMatcher(named.propsToMatch), jsonTextEditor(jsonEditor(path)));
        },
        'json-edit-xhr-response': function (path) {
            var named = namedArgs(arguments, 1);
            onXhrResponse(requestMatcher(named.propsToMatch), jsonTextEditor(jsonEditor(path)));
        },
        'trusted-replace-fetch-response': function (pattern, replacement, propsToMatch) {
            if (pattern) {
                onFetchResponse(requestMatcher(propsToMatch), textReplacer(pattern, replacement));
            }
        },
        'trusted-replace-xhr-response': function (pattern, replacement, propsToMatch) {
            if (pattern) {
                onXhrResponse(requestMatcher(propsToMatch), textReplacer(pattern, replacement));
            }
        },
        'no-fetch-if': blockFetch,
        'no-xhr-if': blockXhr,
        'no-setTimeout-if': function (needle, delay) {
            timerScriptlet('setTimeout', needle, delay, function () { return null; });
        },
        'no-setInterval-if': function (needle, delay) {
            timerScriptlet('setInterval', needle, delay, function () { return null; });
        },
        'adjust-setTimeout': function (needle, delay, boost) {
            var factor = parseFloat(boost);
            factor = isNaN(factor) ? 0.05 : factor;
            timerScriptlet('setTimeout', needle, delay || '1000', function (args) {
                args[1] = Number(args[1]) * factor;
                return args;
            });
        },
        'adjust-setInterval': function (needle, delay, boost) {
            var factor = parseFloat(boost);
            factor = isNaN(factor) ? 0.05 : factor;
            timerScriptlet('setInterval', needle, delay || '1000', function (args) {
                args[1] = Number(args[1]) * factor;
                return args;
            });
        },
        'addEventListener-defuser': function (rawType, rawNeedle) {
            var type = toMatcher(rawType, false);
            var needle = toMatcher(rawNeedle, false);
            EventTarget.prototype.addEventListener = new Proxy(EventTarget.prototype.addEventListener, {
                apply: function (target, thisArg, args) {
                    if (type(args[0]) && needle(String(args[1]))) {
                        return undefined;
                    }
                    return Reflect.apply(target, thisArg, args);
                }
            });
        },
        'no-window-open-if': function (rawNeedle) {
            var needle = toMatcher(rawNeedle, true);
            window.open = new Proxy(window.open, {
                apply: function (target, thisArg, args) {
                    if (needle(String(args[0] || ''))) {
                        return null;
                    }
                    return Reflect.apply(target, thisArg, args);
                }
            });
        },
        'remove-attr': function (rawAttributes, selector) {
            var attributes = String(rawAttributes || '').split(/\s*\|\s*/).filter(Boolean);
            if (!attributes.length) {
                return;
            }
            observeElements(selector || attributes.map(function (a) { return '[' + a + ']'; }).join(','), function (element) {
                attributes.forEach(function (attribute) { element.removeAttribute(attribute); });
            });
        },
        'remove-class': function (rawClasses, selector) {
            var classes = String(rawClasses || '').split(/\s*\|\s*/).filter(Boolean);
            if (!classes.length) {
                return;
            }
            observeElements(selector || classes.map(function (c) { return '.' + c; }).join(','), function (element) {
                classes.forEach(function (name) { element.classList.remove(name); });
            });
        },
        'remove-node-text': function (nodeName, pattern) {
            var matcher = toMatcher(pattern, false);
            observeNodeText(nodeName, function (text) { return matcher(text) ? '' : text; });
        },
        'replace-node-text': function (nodeName, pattern, replacement) {
            if (pattern) {
                observeNodeText(nodeName, textReplacer(pattern, replacement));
            }
        },
        'set-local-storage-item': function (key, value) {
            storageScriptlet('localStorage', key, value);
        },
        'set-session-storage-item': function (key, value) {
            storageScriptlet('sessionStorage', key, value);
        }
    };

    var aliases = {
        'set': 'set-constant',
        'trusted-set': 'trusted-set-constant',
        'aopr': 'abort-on-property-read',
        'aopw': 'abort-on-property-write',
        'acs': 'abort-current-script',
        'abort-current-inline-script': 'abort-current-script',
        'acis': 'abort-current-script',
        'jpfr': 'json-prune-fetch-response',
        'jpxr': 'json-prune-xhr-response',
        'trusted-rpfr': 'trusted-replace-fetch-response',
        'trusted-rpxr': 'trusted-replace-xhr-response',
        'prevent-fetch': 'no-fetch-if',
        'prevent-xhr': 'no-xhr-if',
        'nostif': 'no-setTimeout-if',
        'prevent-setTimeout': 'no-setTimeout-if',
        'setTimeout-defuser': 'no-setTimeout-if',
        'nosiif': 'no-setInterval-if',
        'prevent-setInterval': 'no-setInterval-if',
        'setInterval-defuser': 'no-setInterval-if',
        'nano-stb': 'adjust-setTimeout',
        'nano-setTimeout-booster': 'adjust-setTimeout',
        'nano-sib': 'adjust-setInterval',
        'nano-setInterval-booster': 'adjust-setInterval',
        'aeld': 'addEventListener-defuser',
        'prevent-addEventListener': 'addEventListener-defuser',
        'nowoif': 'no-window-open-if',
        'prevent-window-open': 'no-window-open-if',
        'ra': 'remove-attr',
        'rc': 'remove-class',
        'rmnt': 'remove-node-text',
        'rpnt': 'replace-node-text',
        'trusted-rpnt': 'replace-node-text'
    };

    function hide(selectors) {
        var sheet;
        try {
            sheet = new CSSStyleSheet();
        } catch (e) {
            return;
        }
        // One rule per batch of selectors; if one is invalid, insert them one by one
        for (var i = 0; i < selectors.length; i += 50) {
            var batch = selectors.slice(i, i + 50);
            try {
                sheet.insertRule(batch.join(',\n') + ' { display: none !important; }', sheet.cssRules.length);
            } catch (e) {
                batch.forEach(function (selector) {
                    try {
                        sheet.insertRule(selector + ' { display: none !important; }', sheet.cssRules.length);
                    } catch (ignored) {
                        // Not a valid selector in this browser
                    }
                });
            }
        }
        document.adoptedStyleSheets = document.adoptedStyleSheets.concat([sheet]);
    }

    return {
        run: function (name, args) {
            var scriptlet = scriptlets[aliases[name] || name];
            if (!scriptlet) {
                return;
            }
            try {
                scriptlet.apply(null, args);
            } catch (e) {
                // A broken rule must not break the page
            }
        },
        hide: hide
    };
})();
