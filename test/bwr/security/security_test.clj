(ns bwr.security.security-test
  "Tests for security middleware, live IP containment, and rate limiting (§9):
   - Shared security headers & strict CSP without unsafe-inline
   - Live IP banning containment lever and security event logging
   - Sliding-window rate limiting per IP and 429 rejection."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [bwr.store.node :as store-node]
            [bwr.store.query :as store-query]
            [bwr.security.middleware :as sec]))

(def ^:dynamic *node* nil)

(defn with-clean-security [f]
  (let [node (store-node/start-node! {:topology :in-memory})]
    (try
      (sec/reset-banned-ips!)
      (binding [*node* node]
        (f))
      (finally
        (sec/reset-banned-ips!)
        (store-node/stop-node! node)))))

(use-fixtures :each with-clean-security)

;; ============================================================================
;; 1. Reusable Security Headers Middleware (§9.3)
;; ============================================================================

(deftest wrap-security-headers-test
  (testing "Applies strict Content-Security-Policy without unsafe-inline and standard security headers"
    (let [base-handler (fn [_] {:status 200 :headers {"Content-Type" "text/plain"} :body "ok"})
          wrapped-handler (sec/wrap-security-headers base-handler)
          resp (wrapped-handler {:request-method :get :uri "/"})
          headers (:headers resp)
          csp (get headers "Content-Security-Policy")]

      (is (= 200 (:status resp)))
      (is (contains? headers "Content-Security-Policy"))
      (is (str/includes? csp "default-src 'self'"))
      (is (str/includes? csp "script-src 'self'"))
      (is (str/includes? csp "style-src 'self'"))
      (is (not (str/includes? csp "unsafe-inline")))
      (is (= "nosniff" (get headers "X-Content-Type-Options")))
      (is (= "DENY" (get headers "X-Frame-Options")))
      (is (= "strict-origin-when-cross-origin" (get headers "Referrer-Policy"))))))

;; ============================================================================
;; 2. Live IP Containment Levers (§9.4, §9.7)
;; ============================================================================

(deftest ip-containment-test
  (testing "Banned IP receives HTTP 403 Forbidden with security headers and logs containment event"
    (let [base-handler (fn [_] {:status 200 :body "welcome"})
          wrapped-handler (sec/wrap-ip-containment base-handler *node*)
          normal-req {:remote-addr "192.168.1.100" :request-method :get :uri "/"}
          attacker-req {:remote-addr "198.51.100.25" :request-method :get :uri "/admin"}]

      ;; 1. Normal access permitted
      (let [resp (wrapped-handler normal-req)]
        (is (= 200 (:status resp)))
        (is (= "welcome" (:body resp))))

      ;; 2. Activate Containment Lever: Ban attacker IP
      (sec/ban-ip! *node* "198.51.100.25" "Repeated malicious auth probes")
      (is (true? (sec/ip-banned? "198.51.100.25")))

      ;; 3. Attacker access blocked immediately
      (let [resp (wrapped-handler attacker-req)]
        (is (= 403 (:status resp)))
        (is (str/includes? (:body resp) "Forbidden"))
        (is (contains? (:headers resp) "Content-Security-Policy")))

      ;; 4. Verify security event audit trail
      (let [db (store-query/db-at *node*)
            ban-events (store-query/find-security-events db {:type :sec.type/ip-banned})
            blocked-events (store-query/find-security-events db {:type :sec.type/containment-blocked})]
        (is (= 1 (count ban-events)))
        (is (= "198.51.100.25" (:sec-event/client-ip (first ban-events))))
        (is (= 1 (count blocked-events)))
        (is (= "198.51.100.25" (:sec-event/client-ip (first blocked-events))))
        (is (= :reason/ip-banned (:sec-event/reason (first blocked-events)))))

      ;; 5. Unban restores access
      (sec/unban-ip! *node* "198.51.100.25")
      (is (false? (sec/ip-banned? "198.51.100.25")))
      (let [resp (wrapped-handler attacker-req)]
        (is (= 200 (:status resp)))))))

;; ============================================================================
;; 3. Rate Limiting Containment Lever (§9.4)
;; ============================================================================

(deftest rate-limiting-test
  (testing "Rate limiter permits requests within budget and blocks excess with HTTP 429"
    (let [limiter (sec/create-rate-limiter {:max-requests 3 :window-seconds 60})
          base-handler (fn [_] {:status 200 :body "ok"})
          wrapped-handler (sec/wrap-rate-limit base-handler limiter *node*)
          req {:remote-addr "203.0.113.42" :request-method :get :uri "/auth/request"}]

      ;; First 3 requests succeed
      (dotimes [_ 3]
        (let [resp (wrapped-handler req)]
          (is (= 200 (:status resp)))))

      ;; 4th request exceeds rate limit: HTTP 429
      (let [resp (wrapped-handler req)]
        (is (= 429 (:status resp)))
        (is (str/includes? (:body resp) "Rate limit exceeded"))
        (is (contains? (:headers resp) "Retry-After"))
        (is (contains? (:headers resp) "Content-Security-Policy")))

      ;; Audit log records rate limit trip
      (let [db (store-query/db-at *node*)
            events (store-query/find-security-events db {:type :sec.type/rate-limit-exceeded})]
        (is (= 1 (count events)))
        (is (= "203.0.113.42" (:sec-event/client-ip (first events))))
        (is (= :reason/rate-limit (:sec-event/reason (first events))))))))
