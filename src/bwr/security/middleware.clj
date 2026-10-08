(ns bwr.security.middleware
  "Security middleware, containment levers, and audit logging for Ring (§9).
   Provides strict Content-Security-Policy (without unsafe-inline), standard
   security headers, live IP banning, and rate-limiting containment controls."
  (:require [clojure.string :as str]
            [bwr.store.query :as store-query]))

(set! *warn-on-reflection* true)

;; ============================================================================
;; Security Headers & Content Security Policy (§9.3)
;; ============================================================================

(def csp-policy
  "Strict Content-Security-Policy denying inline script and inline style (§9.3)."
  (str "default-src 'self'; "
       "script-src 'self'; "
       "style-src 'self'; "
       "img-src 'self' data:; "
       "font-src 'self'; "
       "connect-src 'self'; "
       "frame-ancestors 'none'; "
       "base-uri 'self'; "
       "form-action 'self'"))

(def security-headers
  "Standard security response headers to accompany every HTTP response (§9)."
  {"Content-Security-Policy" csp-policy
   "X-Content-Type-Options" "nosniff"
   "X-Frame-Options" "DENY"
   "Referrer-Policy" "strict-origin-when-cross-origin"
   "Permissions-Policy" "camera=(), microphone=(), geolocation=()"})

(defn wrap-security-headers
  "Ring middleware that attaches strict CSP and standard security headers to every response."
  [handler]
  (fn [request]
    (when-let [response (handler request)]
      (update response :headers merge security-headers))))

;; ============================================================================
;; IP Literal Validation & Normalization (ADR-003, §9.4)
;; Never calls InetAddress/getByName on header text (no DNS resolution)
;; ============================================================================

(defn- strip-brackets
  [^String s]
  (if (and (.startsWith s "[") (.endsWith s "]") (> (.length s) 1))
    (.substring s 1 (dec (.length s)))
    s))

(defn valid-ipv4?
  "Returns true if s is a valid IPv4 literal (dotted quad, 0-255 per octet, no leading zeros).
   Always returns a boolean."
  [s]
  (if-not (string? s)
    false
    (let [s (str/trim ^String s)]
      (boolean
       (when-let [[_ a b c d] (re-matches #"^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$" s)]
         (every? (fn [^String octet]
                   (and (or (= (.length octet) 1) (not (.startsWith octet "0")))
                        (<= 0 (Long/parseLong octet) 255)))
                 [a b c d]))))))

(defn valid-ipv6?
  "Returns true if s is a valid IPv6 literal (hex words separated by colons, optional :: compression).
   Always returns a boolean."
  [s]
  (if-not (string? s)
    false
    (let [clean (-> ^String s str/trim strip-brackets)]
      (boolean
       (cond
         (not (re-matches #"^[0-9a-fA-F:.]+$" clean)) false
         (not (.contains ^String clean ":")) false
         (.contains ^String clean ":::") false
         (> (count (re-seq #"::" clean)) 1) false
         (= clean "::") true

         :else
         (let [has-double-colon? (.contains ^String clean "::")
               [v6-part v4-suffix] (if-let [[_ p1 p2] (re-matches #"^(.*:)(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})$" clean)]
                                     [p1 p2]
                                     [clean nil])]
           (if (and v4-suffix (not (valid-ipv4? v4-suffix)))
             false
             (let [v6-raw ^String v6-part
                   v6-str (if (and (not (.endsWith v6-raw "::")) (.endsWith v6-raw ":"))
                            (.substring v6-raw 0 (dec (.length v6-raw)))
                            v6-raw)
                   raw-tokens (str/split v6-str #":" -1)
                   tokens (cond
                            (= v6-str "::") []
                            (and (.startsWith v6-str "::") (.endsWith v6-str "::"))
                            (subvec (vec raw-tokens) 2 (- (count raw-tokens) 2))
                            (.startsWith v6-str "::")
                            (subvec (vec raw-tokens) 2)
                            (.endsWith v6-str "::")
                            (subvec (vec raw-tokens) 0 (- (count raw-tokens) 2))
                            :else
                            raw-tokens)
                   empty-tokens (filter #(= % "") tokens)
                   non-empty-tokens (remove #(= % "") tokens)
                   hex-valid? (every? #(re-matches #"^[0-9a-fA-F]{1,4}$" ^String %) non-empty-tokens)
                   total-groups (+ (count non-empty-tokens) (if v4-suffix 2 0))]
               (and hex-valid?
                    (if has-double-colon?
                      (and (<= (count empty-tokens) 1)
                           (< total-groups 8))
                      (and (zero? (count empty-tokens))
                           (= total-groups 8))))))))))))

(defn valid-ip-literal?
  "Returns true if s is a valid IPv4 or IPv6 literal string.
   Never calls InetAddress/getByName and never performs DNS resolution."
  [s]
  (boolean (or (valid-ipv4? s) (valid-ipv6? s))))

(defn normalize-ip
  "Normalizes an IP string: strips brackets, trims, and lowercases for consistent comparison."
  [s]
  (when (string? s)
    (-> ^String s str/trim strip-brackets str/lower-case)))

(defn loopback-ip?
  "Returns true if the IP string is a valid loopback address (127.0.0.0/8 or ::1)."
  [ip]
  (if-not (valid-ip-literal? ip)
    false
    (let [norm ^String (normalize-ip ip)]
      (boolean
       (or (= norm "::1")
           (= norm "0:0:0:0:0:0:0:1")
           (.startsWith norm "127."))))))

(defn extract-ip-literal
  "Extracts, normalizes, and validates an IP literal from a candidate string.
   Handles optional brackets for IPv6 and optional port numbers (e.g. 1.2.3.4:5678 or [::1]:5678).
   Returns the normalized valid IP literal string, or nil if invalid."
  [s]
  (when (string? s)
    (let [trimmed (str/trim ^String s)
          candidate (cond
                      (valid-ip-literal? trimmed) trimmed
                      (re-matches #"^\[([0-9a-fA-F:.]+)\]:\d+$" trimmed)
                      (second (re-matches #"^\[([0-9a-fA-F:.]+)\]:\d+$" trimmed))
                      (re-matches #"^(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}):\d+$" trimmed)
                      (second (re-matches #"^(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}):\d+$" trimmed))
                      :else trimmed)]
      (when (valid-ip-literal? candidate)
        (normalize-ip candidate)))))

;; ============================================================================
;; Request Attribution & Edge Client-IP Resolution (§9.4, ADR-003)
;; ============================================================================

(defn resolve-client-ip
  "Resolves the client IP for a request given a set of trusted proxy IP literals.
   Algorithm (§9.4, ADR-003):
     1. If :remote-addr is not a trusted proxy, use :remote-addr and ignore
        X-Forwarded-For and X-Real-IP entirely.
     2. If :remote-addr is a trusted proxy, walk X-Forwarded-For from the right,
        skipping trusted proxies, and take the first valid untrusted IP literal.
        If X-Forwarded-For is absent or yields no untrusted address, check X-Real-IP.
     3. If no valid untrusted address is found, fall back to :remote-addr.
     4. Store only validated IP literals, with 'unknown' as final fallback."
  ([request] (resolve-client-ip request #{}))
  ([request trusted-proxies]
   (let [trusted-set (if (set? trusted-proxies)
                       (into #{} (keep normalize-ip trusted-proxies))
                       #{})
         remote-ip (extract-ip-literal (:remote-addr request))
         remote-trusted? (and (some? remote-ip) (contains? trusted-set remote-ip))]
     (if-not remote-trusted?
       (or remote-ip "unknown")
       ;; :remote-addr is a trusted proxy: walk X-Forwarded-For from the right
       (let [xf (get-in request [:headers "x-forwarded-for"])
             candidates (when (string? xf)
                          (keep extract-ip-literal (reverse (str/split xf #","))))
             first-untrusted (first (remove #(contains? trusted-set %) candidates))]
         (or first-untrusted
             (when-let [xrip (extract-ip-literal (get-in request [:headers "x-real-ip"]))]
               (when-not (contains? trusted-set xrip)
                 xrip))
             remote-ip
             "unknown"))))))

(defn wrap-client-ip
  "Edge middleware that computes the client IP once using resolve-client-ip
   and attaches it to the request under :bwr/client-ip.
   Every downstream middleware and handler reads (:bwr/client-ip request)."
  ([handler] (wrap-client-ip handler #{}))
  ([handler trusted-proxies]
   (fn [request]
     (let [client-ip (resolve-client-ip request trusted-proxies)]
       (handler (assoc request :bwr/client-ip client-ip))))))

;; ============================================================================
;; Containment Levers: Live IP Banning (§9.4, §9.7)
;; ============================================================================

(defonce ^:private banned-ips
  (atom #{}))

(defn ban-ip!
  "Bans an IP address dynamically. If XTDB node is provided, logs a security event (§9.4)."
  ([ip] (ban-ip! nil ip "Manual administrator containment action"))
  ([node ip reason]
   (swap! banned-ips conj ip)
   (when node
     (store-query/log-security-event!
      node
      {:sec-event/type :sec.type/ip-banned
       :sec-event/client-ip ip
       :sec-event/status :status/success
       :sec-event/reason :reason/admin-action
       :sec-event/detail {:reason reason}}))
   ip))

(defn unban-ip!
  "Removes an IP address from the banned set."
  ([ip] (unban-ip! nil ip))
  ([node ip]
   (swap! banned-ips disj ip)
   (when node
     (store-query/log-security-event!
      node
      {:sec-event/type :sec.type/ip-unbanned
       :sec-event/client-ip ip
       :sec-event/status :status/success
       :sec-event/detail {}}))
   ip))

(defn ip-banned?
  "Returns true if the given IP address is currently banned."
  [ip]
  (contains? @banned-ips ip))

(defn reset-banned-ips!
  "Clears all banned IPs. Primarily used for test cleanup."
  []
  (reset! banned-ips #{}))

(defn wrap-ip-containment
  "Middleware that checks if the client IP is banned. If banned, returns HTTP 403 Forbidden
   and logs a containment block event."
  ([handler] (wrap-ip-containment handler nil))
  ([handler node]
   (fn [request]
     (let [ip (or (:bwr/client-ip request) (extract-ip-literal (:remote-addr request)) "unknown")]
       (if (ip-banned? ip)
         (do
           (when node
             (store-query/log-security-event!
              node
              {:sec-event/type :sec.type/containment-blocked
               :sec-event/client-ip ip
               :sec-event/status :status/failure
               :sec-event/reason :reason/ip-banned
               :sec-event/detail {:uri (:uri request)
                                  :method (:request-method request)}}))
           {:status 403
            :headers (merge security-headers {"Content-Type" "text/plain; charset=utf-8"})
            :body "Forbidden: Access Denied by Security Policy"})
         (handler request))))))

;; ============================================================================
;; Containment Levers: Rate Limiting (§9.4, §9.7)
;; ============================================================================

(defn create-rate-limiter
  "Creates a thread-safe sliding window rate limiter.
   Options:
     :max-requests    - maximum requests allowed within window (default: 10)
     :window-seconds  - window duration in seconds (default: 60)"
  ([] (create-rate-limiter {}))
  ([opts]
   (let [max-reqs (get opts :max-requests 10)
         window-ms (* 1000 (long (get opts :window-seconds 60)))
         state (atom {})]
     {:max-reqs max-reqs
      :window-ms window-ms
      :state state})))

(defn check-rate-limit!
  "Checks and updates request count for an IP under a rate limiter.
   Returns {:allowed? boolean :remaining-requests int :reset-seconds int}."
  [limiter ip]
  (let [now (System/currentTimeMillis)
        window-ms (:window-ms limiter)
        max-reqs (:max-reqs limiter)
        cutoff (- now window-ms)]
    (let [allowed? (atom false)
          remaining (atom 0)]
      (swap! (:state limiter)
             (fn [m]
               (let [timestamps (filterv #(> % cutoff) (get m ip []))
                     cnt (count timestamps)]
                 (if (< cnt max-reqs)
                   (do
                     (reset! allowed? true)
                     (reset! remaining (- max-reqs (inc cnt)))
                     (assoc m ip (conj timestamps now)))
                   (do
                     (reset! allowed? false)
                     (reset! remaining 0)
                     (assoc m ip timestamps))))))
      {:allowed? @allowed?
       :remaining-requests @remaining
       :reset-seconds (int (Math/ceil (/ (double window-ms) 1000.0)))})))

(defn wrap-rate-limit
  "Ring middleware that enforces rate limiting per client IP using the given limiter.
   Returns HTTP 429 Too Many Requests when rate limit is exceeded."
  ([handler limiter] (wrap-rate-limit handler limiter nil))
  ([handler limiter node]
   (fn [request]
     (let [ip (or (:bwr/client-ip request) (extract-ip-literal (:remote-addr request)) "unknown")
           {:keys [allowed? reset-seconds]} (check-rate-limit! limiter ip)]
       (if allowed?
         (handler request)
         (do
           (when node
             (store-query/log-security-event!
              node
              {:sec-event/type :sec.type/rate-limit-exceeded
               :sec-event/client-ip ip
               :sec-event/status :status/failure
               :sec-event/reason :reason/rate-limit
               :sec-event/detail {:uri (:uri request)
                                  :reset-seconds reset-seconds}}))
           {:status 429
            :headers (merge security-headers
                            {"Content-Type" "text/plain; charset=utf-8"
                             "Retry-After" (str reset-seconds)})
            :body "Too Many Requests: Rate limit exceeded. Please try again later."}))))))
