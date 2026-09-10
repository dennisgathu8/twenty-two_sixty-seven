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
;; Request Attribution Helpers
;; ============================================================================

(defn extract-client-ip
  "Extracts the client IP from request headers (x-forwarded-for, x-real-ip) or :remote-addr."
  [request]
  (or (when-let [xf (get-in request [:headers "x-forwarded-for"])]
        (str/trim (first (str/split xf #","))))
      (get-in request [:headers "x-real-ip"])
      (:remote-addr request)
      "127.0.0.1"))

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
     (let [ip (extract-client-ip request)]
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
     (let [ip (extract-client-ip request)
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
